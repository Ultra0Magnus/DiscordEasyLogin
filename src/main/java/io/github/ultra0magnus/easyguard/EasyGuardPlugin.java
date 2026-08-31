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
    private volatile boolean lockedDown;

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
            lockedDown = true;
            getLogger().severe("EasyGuard could not start: " + error);
            getLogger().severe("The server is now LOCKED DOWN - all logins are denied until this is fixed.");
            return;
        }

        if (!config.enabled()) {
            getLogger().warning("EasyGuard is installed but 'enabled: false' in config.yml - "
                    + "players can join WITHOUT a Discord code.");
        } else {
            getLogger().info("EasyGuard active: codes go to " + config.webhookEndpoint().getHost()
                    + ", trust window " + config.trustDuration().toHours() + "h.");
        }

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
            lockedDown = true;
            getLogger().severe("EasyGuard reload failed: " + error + " - server LOCKED DOWN.");
            return error;
        }
        lockedDown = false;
        return null;
    }

    private String loadEverything() {
        try {
            saveDefaultConfig();
            EasyGuardConfig loaded = EasyGuardConfig.load(getConfig());

            TrustedSessionStore sessions = new TrustedSessionStore(
                    getDataFolder().toPath().resolve("sessions.properties"), System::currentTimeMillis);
            sessions.load();

            this.config = loaded;
            this.trustedSessions = sessions;
            this.notifier = new DiscordWebhookNotifier(
                    loaded.webhookEndpoint(), loaded.webhookTimeout(), loaded.webhookUsername());
            this.verification = new VerificationManager(
                    loaded.codeTtl().toMillis(),
                    loaded.maxAttempts(),
                    loaded.perPlayerCooldown().toMillis(),
                    loaded.maxCodesPerMinute(),
                    System::currentTimeMillis);
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
        return lockedDown;
    }

    public void setLockedDown(boolean lockedDown) {
        this.lockedDown = lockedDown;
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
