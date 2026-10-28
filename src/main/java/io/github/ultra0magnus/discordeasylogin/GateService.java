package io.github.ultra0magnus.discordeasylogin;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;

/**
 * The gate itself: decides who must verify, issues codes, and acts on the answers.
 *
 * <p>Kicks never drop the hold themselves. The hold is removed only when the player actually
 * leaves ({@link #onQuit}) or verifies: another plugin can cancel a kick, and a player whose kick
 * was cancelled must still be held, not set free.
 */
public final class GateService {

    /** How long a just-used code is kept out of public chat, for the habitual second paste. */
    private static final long ECHO_WINDOW_MILLIS = 5000L;

    private record UsedCode(String code, long until) {
    }

    private final DiscordEasyLoginPlugin plugin;
    private final Map<UUID, UsedCode> recentlyUsed = new ConcurrentHashMap<>();

    public GateService(DiscordEasyLoginPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @return true if the player was put into limbo (and the join message should be suppressed)
     */
    public boolean onJoin(Player player) {
        DiscordEasyLoginConfig cfg = plugin.config();
        if (!cfg.enabled() || player.hasPermission("discordeasylogin.bypass")) {
            return false;
        }
        if (plugin.trustedSessions().isTrusted(player.getUniqueId())) {
            return false;
        }
        if (!plugin.verification().canIssue(player.getUniqueId())) {
            // Hold before kicking: the kick lands a tick later, and nothing may happen in between.
            plugin.limbo().hold(player, cfg.verifyTimeout(), this::onTimeout);
            kickLater(player, Text.render(cfg.messages().kickRateLimited()), PlayerKickEvent.Cause.PLUGIN);
            return true;
        }

        Object session = plugin.limbo().hold(player, cfg.verifyTimeout(), this::onTimeout);
        player.sendMessage(Text.render(cfg.messages().prompt()));
        player.sendMessage(Text.render(cfg.messages().promptWaiting()));

        String code = plugin.codeGenerator().generate(cfg.codeLength());
        plugin.verification().issue(player.getUniqueId(), code);
        String address = player.getAddress() == null ? "unknown"
                : player.getAddress().getAddress().getHostAddress();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deliver(player, session, code, address));
        return true;
    }

    private void deliver(Player player, Object session, String code, String address) {
        DiscordEasyLoginConfig cfg = plugin.config();
        String body = cfg.discordMentionPrefix()
                + Text.fill(cfg.messages().discordTemplate(),
                "player", player.getName(),
                "uuid", player.getUniqueId().toString(),
                "ip", address,
                "code", code,
                "ttl", cfg.codeTtl().toMinutes() + " minute(s)");

        DiscordWebhookNotifier.Delivery delivery = plugin.notifier().send(body);
        if (delivery.delivered()) {
            return;
        }

        // Fail closed: no code delivered means no login. The console copy is the documented
        // escape hatch for the server owner during a Discord outage.
        plugin.getLogger().warning("Could not deliver a verification code to Discord for " + player.getName()
                + ": " + delivery.detail() + ".");
        if (cfg.logCodeToConsoleOnFailure()) {
            plugin.getLogger().warning("Verification code for " + player.getName() + " is: " + code
                    + " (valid " + cfg.codeTtl().toMinutes() + " minute(s))");
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            // The failure can arrive seconds later. By then this account may have quit and
            // rejoined; acting on the *new* session here once dropped its hold and set it free.
            if (!player.isConnected() || !plugin.limbo().isCurrentSession(player.getUniqueId(), session)) {
                return;
            }
            if (cfg.logCodeToConsoleOnFailure()) {
                // The code exists in the console, so keep the player waiting rather than kicking them.
                player.sendMessage(Text.render(cfg.messages().discordDown()));
                return;
            }
            plugin.verification().cancel(player.getUniqueId());
            player.kick(Text.render(cfg.messages().kickUnavailable()), PlayerKickEvent.Cause.PLUGIN);
        });
    }

    /** Handles a code submitted through chat or {@code /verify}. Must run on the main thread. */
    public void submit(Player player, String input) {
        DiscordEasyLoginConfig cfg = plugin.config();
        UUID uuid = player.getUniqueId();
        if (!plugin.limbo().isHeld(uuid)) {
            player.sendMessage(Text.render(cfg.messages().noPending()));
            return;
        }

        switch (plugin.verification().verify(uuid, input)) {
            case SUCCESS -> {
                recentlyUsed.put(uuid, new UsedCode(normalise(input), System.currentTimeMillis() + ECHO_WINDOW_MILLIS));
                plugin.limbo().release(player);
                plugin.trustedSessions().trust(uuid, cfg.trustDuration().toMillis());
                saveSessionsAsync();
                player.sendMessage(Text.render(cfg.messages().success(), "player", player.getName()));
                if (cfg.suppressJoinMessage()) {
                    Bukkit.broadcast(Component.translatable("multiplayer.player.joined", player.displayName())
                            .color(NamedTextColor.YELLOW));
                }
                plugin.getLogger().info(player.getName() + " passed DiscordEasyLogin verification.");
            }
            case WRONG_CODE -> player.sendMessage(Text.render(cfg.messages().wrongCode(),
                    "attempts", Integer.toString(plugin.verification().attemptsLeft(uuid))));
            case EXPIRED -> player.kick(Text.render(cfg.messages().expired()), PlayerKickEvent.Cause.PLUGIN);
            case OUT_OF_ATTEMPTS -> {
                plugin.getLogger().warning(player.getName() + " was kicked after too many wrong DiscordEasyLogin codes.");
                player.kick(Text.render(cfg.messages().kickOutOfAttempts()), PlayerKickEvent.Cause.PLUGIN);
            }
            case NO_PENDING -> player.sendMessage(Text.render(cfg.messages().noPending()));
        }
    }

    /**
     * True if a just-verified player is pasting the code they already used. A double paste is
     * common, and the second copy arrives after the release, so it would otherwise land in public
     * chat. The code is dead by then, but it does not belong there.
     */
    public boolean isEchoOfUsedCode(Player player, String text) {
        UsedCode used = recentlyUsed.get(player.getUniqueId());
        if (used == null) {
            return false;
        }
        if (System.currentTimeMillis() > used.until()) {
            recentlyUsed.remove(player.getUniqueId(), used);
            return false;
        }
        return used.code().equals(normalise(text));
    }

    public void onQuit(Player player) {
        plugin.limbo().forget(player);
        plugin.verification().cancel(player.getUniqueId());
    }

    public void forgetRecentCode(UUID uuid) {
        recentlyUsed.remove(uuid);
    }

    private void onTimeout(Player player) {
        DiscordEasyLoginConfig cfg = plugin.config();
        plugin.verification().cancel(player.getUniqueId());
        if (player.isConnected()) {
            player.kick(Text.render(cfg.messages().kickTimeout()), PlayerKickEvent.Cause.PLUGIN);
        }
    }

    private void kickLater(Player player, Component reason, PlayerKickEvent.Cause cause) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isConnected()) {
                player.kick(reason, cause);
            }
        });
    }

    private static String normalise(String input) {
        return input == null ? "" : input.trim().toUpperCase(Locale.ROOT);
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
