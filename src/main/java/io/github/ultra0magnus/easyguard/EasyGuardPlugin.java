package io.github.ultra0magnus.easyguard;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * EasyGuard: nobody plays until they hand back a single-use code posted to Discord.
 */
public final class EasyGuardPlugin extends JavaPlugin {

    private volatile EasyGuardConfig config;

    // Two separate reasons to deny every login. Only a successful load clears the first, and only
    // an administrator clears the second: a reload must not lift a lockdown someone turned on on
    // purpose, and "lockdown off" must not open a server whose config never loaded.
    // Starts true: until a config has actually loaded, the safe answer to any login is no.
    private volatile boolean configFailed = true;
    private volatile boolean manualLockdown;

    private final CodeGenerator codeGenerator = new CodeGenerator();
    private volatile VerificationManager verification;
    private volatile TrustedSessionStore trustedSessions;
    private volatile DiscordWebhookNotifier notifier;
    private LimboManager limbo;
    private GateService gate;

    @Override
    public void onEnable() {
        limbo = new LimboManager(this, this::config);
        gate = new GateService(this);

        // Registered before anything can fail, so a broken EasyGuard blocks logins
        // instead of silently leaving the server unguarded.
        getServer().getPluginManager().registerEvents(new LockdownListener(this), this);
        getServer().getPluginManager().registerEvents(new GateListener(this, gate), this);
        registerCommands();

        String error = loadEverything();
        if (error != null) {
            configFailed = true;
            getLogger().severe("EasyGuard could not start: " + error);
            getLogger().severe("The server is now LOCKED DOWN - all logins are denied until this is fixed.");
            return;
        }
        configFailed = false;
        announceState();

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pruneSessions,
                20L * 600L, 20L * 600L);
    }

    @Override
    public void onDisable() {
        // Anyone still unverified must not be left behind unguarded once the listeners are gone.
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (limbo != null && limbo.isHeld(player.getUniqueId())) {
                player.kick(Component.text("EasyGuard is shutting down; verification cancelled.",
                        NamedTextColor.RED), PlayerKickEvent.Cause.PLUGIN);
            }
        }
        if (limbo != null) {
            limbo.releaseAll();
        }
        if (verification != null) {
            verification.clearAll();
        }
        if (trustedSessions != null) {
            try {
                trustedSessions.save();
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Could not persist trusted sessions on shutdown", e);
            }
        }
    }

    /** @return null on success, or a human-readable reason why the config was rejected */
    public String reloadEasyGuard() {
        reloadConfig();
        String error = loadEverything();
        if (error != null) {
            configFailed = true;
            getLogger().severe("EasyGuard reload failed: " + error + " - server LOCKED DOWN.");
            return error;
        }
        configFailed = false;
        announceState();
        if (manualLockdown) {
            getLogger().warning("EasyGuard reloaded, but the manual lockdown is still on - "
                    + "run '/easyguard lockdown off' to allow logins.");
        }
        return null;
    }

    /** Said at startup and on every reload, so the gate can never be off without anyone noticing. */
    private void announceState() {
        if (!config.enabled()) {
            getLogger().warning("EasyGuard is installed but 'enabled: false' in config.yml - "
                    + "players can join WITHOUT a Discord code.");
        } else {
            getLogger().info("EasyGuard active: codes go to " + config.webhookEndpoint().getHost()
                    + ", trust window " + config.trustDuration().toHours() + "h"
                    + (config.pingUserIds().isEmpty() ? "" : ", pinging " + config.pingUserIds().size()
                    + " user(s)") + ".");
        }
    }

    private String loadEverything() {
        try {
            saveDefaultConfig();
            EasyGuardConfig loaded = EasyGuardConfig.load(getConfig());

            // Loaded from disk once. A reload keeps the live store: re-reading the file would drop
            // any trust granted since the last save finished.
            TrustedSessionStore sessions = this.trustedSessions;
            if (sessions == null) {
                sessions = new TrustedSessionStore(
                        getDataFolder().toPath().resolve("sessions.properties"), System::currentTimeMillis);
                sessions.load();
            }

            this.config = loaded;
            this.trustedSessions = sessions;
            this.notifier = new DiscordWebhookNotifier(
                    loaded.webhookEndpoint(), loaded.webhookTimeout(), loaded.webhookUsername(),
                    loaded.pingUserIds());
            VerificationManager.Limits limits = new VerificationManager.Limits(
                    loaded.codeTtl().toMillis(),
                    loaded.maxAttempts(),
                    loaded.perPlayerCooldown().toMillis(),
                    loaded.maxCodesPerMinute());
            if (this.verification == null) {
                this.verification = new VerificationManager(limits, System::currentTimeMillis);
            } else {
                // Keep the existing manager: codes already in Discord and running cooldowns survive a reload.
                this.verification.configure(limits);
            }
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (IOException e) {
            return "could not read sessions file: " + e.getMessage();
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Unexpected error while loading EasyGuard", e);
            return e.toString();
        }
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("verify", "Submit your EasyGuard code", List.of(),
                    new VerifyCommand(this, gate));
            event.registrar().register("easyguard", "EasyGuard administration", List.of("eg"),
                    new EasyGuardCommand(this));
        });
    }

    private void pruneSessions() {
        if (trustedSessions == null) {
            return;
        }
        if (trustedSessions.prune() > 0) {
            try {
                trustedSessions.save();
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Could not persist trusted sessions", e);
            }
        }
    }

    public EasyGuardConfig config() {
        EasyGuardConfig current = config;
        if (current == null) {
            throw new IllegalStateException("EasyGuard config is not loaded");
        }
        return current;
    }

    public EasyGuardConfig configOrNull() {
        return config;
    }

    public boolean isLockedDown() {
        return configFailed || manualLockdown;
    }

    /** True when the lockdown is because the config could not be loaded, as opposed to an admin's choice. */
    public boolean isConfigFailed() {
        return configFailed;
    }

    public boolean isManualLockdown() {
        return manualLockdown;
    }

    /** Only touches the administrator's lockdown; a config failure can only be cleared by fixing the config. */
    public void setManualLockdown(boolean on) {
        this.manualLockdown = on;
    }

    public CodeGenerator codeGenerator() {
        return codeGenerator;
    }

    public VerificationManager verification() {
        return verification;
    }

    public TrustedSessionStore trustedSessions() {
        return trustedSessions;
    }

    public DiscordWebhookNotifier notifier() {
        return notifier;
    }

    public LimboManager limbo() {
        return limbo;
    }
}
