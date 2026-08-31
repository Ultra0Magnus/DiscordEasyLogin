package io.github.ultra0magnus.easyguard;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;

/**
 * The gate itself: decides who must verify, issues codes, and acts on the answers.
 */
public final class GateService {

    private final EasyGuardPlugin plugin;

    public GateService(EasyGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @return true if the player was put into limbo (and the join message should be suppressed)
     */
    public boolean onJoin(Player player) {
        EasyGuardConfig cfg = plugin.config();
        if (!cfg.enabled() || player.hasPermission("easyguard.bypass")) {
            return false;
        }
        if (plugin.trustedSessions().isTrusted(player.getUniqueId())) {
            return false;
        }
        if (!plugin.verification().canIssue(player.getUniqueId())) {
            kickLater(player, Text.render(cfg.messages().kickRateLimited()), PlayerKickEvent.Cause.PLUGIN);
            return false;
        }

        plugin.limbo().hold(player, cfg.verifyTimeout(), this::onTimeout);
        player.sendMessage(Text.render(cfg.messages().prompt()));
        player.sendMessage(Text.render(cfg.messages().promptWaiting()));

        String code = plugin.codeGenerator().generate(cfg.codeLength());
        plugin.verification().issue(player.getUniqueId(), code);
        String address = player.getAddress() == null ? "unknown"
                : player.getAddress().getAddress().getHostAddress();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deliver(player, code, address));
        return true;
    }

    private void deliver(Player player, String code, String address) {
        EasyGuardConfig cfg = plugin.config();
        String body = cfg.discordMentionPrefix()
                + Text.fill(cfg.messages().discordTemplate(),
                "player", player.getName(),
                "uuid", player.getUniqueId().toString(),
                "ip", address,
                "code", code,
                "ttl", cfg.codeTtl().toMinutes() + " minute(s)");

        boolean sent = plugin.notifier().send(body);
        if (sent) {
            return;
        }

        // Fail closed: no code delivered means no login. The console copy is the documented
        // escape hatch for the server owner during a Discord outage.
        plugin.getLogger().warning("Could not deliver a verification code to Discord for " + player.getName() + ".");
        if (cfg.logCodeToConsoleOnFailure()) {
            plugin.getLogger().warning("Verification code for " + player.getName() + " is: " + code
                    + " (valid " + cfg.codeTtl().toMinutes() + " minute(s))");
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.limbo().isHeld(player.getUniqueId())) {
                return;
            }
            if (cfg.logCodeToConsoleOnFailure()) {
                // The code exists in the console, so keep the player waiting rather than kicking them.
                player.sendMessage(Text.render(cfg.messages().discordDown()));
                return;
            }
            plugin.verification().cancel(player.getUniqueId());
            plugin.limbo().discard(player.getUniqueId());
            player.kick(Text.render(cfg.messages().kickUnavailable()), PlayerKickEvent.Cause.PLUGIN);
        });
    }

    /** Handles a code submitted through chat or {@code /verify}. Must run on the main thread. */
    public void submit(Player player, String input) {
        EasyGuardConfig cfg = plugin.config();
        UUID uuid = player.getUniqueId();
        if (!plugin.limbo().isHeld(uuid)) {
            player.sendMessage(Text.render(cfg.messages().noPending()));
            return;
        }

        switch (plugin.verification().verify(uuid, input)) {
            case SUCCESS -> {
                plugin.limbo().release(player);
                plugin.trustedSessions().trust(uuid, cfg.trustDuration().toMillis());
                saveSessionsAsync();
                player.sendMessage(Text.render(cfg.messages().success(), "player", player.getName()));
                if (cfg.suppressJoinMessage()) {
                    Bukkit.broadcast(Component.translatable("multiplayer.player.joined", player.displayName())
                            .color(NamedTextColor.YELLOW));
                }
                plugin.getLogger().info(player.getName() + " passed EasyGuard verification.");
            }
            case WRONG_CODE -> player.sendMessage(Text.render(cfg.messages().wrongCode(),
                    "attempts", Integer.toString(plugin.verification().attemptsLeft(uuid))));
            case EXPIRED -> {
                plugin.limbo().discard(uuid);
                player.kick(Text.render(cfg.messages().expired()), PlayerKickEvent.Cause.PLUGIN);
            }
            case OUT_OF_ATTEMPTS -> {
                plugin.limbo().discard(uuid);
                plugin.getLogger().warning(player.getName() + " was kicked after too many wrong EasyGuard codes.");
                player.kick(Text.render(cfg.messages().kickOutOfAttempts()), PlayerKickEvent.Cause.PLUGIN);
            }
            case NO_PENDING -> player.sendMessage(Text.render(cfg.messages().noPending()));
        }
    }

    public void onQuit(Player player) {
        plugin.limbo().discard(player.getUniqueId());
        plugin.verification().cancel(player.getUniqueId());
    }

    private void onTimeout(Player player) {
        EasyGuardConfig cfg = plugin.config();
        plugin.verification().cancel(player.getUniqueId());
        plugin.limbo().discard(player.getUniqueId());
        if (player.isOnline()) {
            player.kick(Text.render(cfg.messages().kickTimeout()), PlayerKickEvent.Cause.PLUGIN);
        }
    }

    private void kickLater(Player player, Component reason, PlayerKickEvent.Cause cause) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.kick(reason, cause);
            }
        });
    }

    private void saveSessionsAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.trustedSessions().save();
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not persist trusted sessions", e);
            }
        });
    }
}
