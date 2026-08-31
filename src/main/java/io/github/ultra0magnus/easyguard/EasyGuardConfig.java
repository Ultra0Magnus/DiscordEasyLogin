package io.github.ultra0magnus.easyguard;

import java.net.URI;
import java.time.Duration;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of config.yml, validated at load time.
 * A validation failure is fatal on purpose: the plugin locks the server down rather than
 * running with a gate it cannot trust.
 */
public record EasyGuardConfig(
        boolean enabled,
        URI webhookEndpoint,
        String webhookUsername,
        Duration webhookTimeout,
        String discordMentionPrefix,
        int codeLength,
        Duration codeTtl,
        int maxAttempts,
        Duration perPlayerCooldown,
        int maxCodesPerMinute,
        Duration verifyTimeout,
        boolean freezeMovement,
        boolean applyBlindness,
        boolean hideFromOthers,
        boolean suppressJoinMessage,
        Duration trustDuration,
        boolean logCodeToConsoleOnFailure,
        Messages messages) {

    public record Messages(
            String prompt,
            String promptWaiting,
            String success,
            String wrongCode,
            String expired,
            String noPending,
            String usage,
            String blockedAction,
            String discordDown,
            String kickTimeout,
            String kickOutOfAttempts,
            String kickUnavailable,
            String kickRateLimited,
            String kickLockdown,
            String discordTemplate) {
    }

    public static final String WEBHOOK_ENV_VAR = "EASYGUARD_DISCORD_WEBHOOK";

    public static EasyGuardConfig load(FileConfiguration cfg) {
        boolean enabled = cfg.getBoolean("enabled", true);
        boolean allowNonDiscordHost = cfg.getBoolean("discord.allow_non_discord_host", false);

        String envUrl = System.getenv(WEBHOOK_ENV_VAR);
        String rawUrl = envUrl != null && !envUrl.isBlank() ? envUrl : cfg.getString("discord.webhook_url", "");
        URI endpoint = DiscordWebhookNotifier.validateEndpoint(rawUrl, allowNonDiscordHost);

        int codeLength = requireRange(cfg.getInt("code.length", 8), 4, 32, "code.length");
        int maxAttempts = requireRange(cfg.getInt("code.max_attempts", 3), 1, 10, "code.max_attempts");
        int maxPerMinute = requireRange(cfg.getInt("code.max_per_minute", 20), 1, 500, "code.max_per_minute");
        int trustHours = requireRange(cfg.getInt("trust.duration_hours", 24), 0, 24 * 365, "trust.duration_hours");

        return new EasyGuardConfig(
                enabled,
                endpoint,
                cfg.getString("discord.username", "EasyGuard"),
                Duration.ofSeconds(requireRange(cfg.getInt("discord.timeout_seconds", 5), 1, 30, "discord.timeout_seconds")),
                cfg.getString("discord.mention_prefix", ""),
                codeLength,
                Duration.ofSeconds(requireRange(cfg.getInt("code.ttl_seconds", 300), 30, 3600, "code.ttl_seconds")),
                maxAttempts,
                Duration.ofSeconds(requireRange(cfg.getInt("code.cooldown_seconds", 10), 0, 600, "code.cooldown_seconds")),
                maxPerMinute,
                Duration.ofSeconds(requireRange(cfg.getInt("verification.timeout_seconds", 120), 15, 600, "verification.timeout_seconds")),
                cfg.getBoolean("verification.freeze_movement", true),
                cfg.getBoolean("verification.apply_blindness", true),
                cfg.getBoolean("verification.hide_from_others", true),
                cfg.getBoolean("verification.suppress_join_message", true),
                Duration.ofHours(trustHours),
                cfg.getBoolean("fallback.log_code_to_console_on_failure", true),
                loadMessages(cfg));
    }

    private static Messages loadMessages(FileConfiguration cfg) {
        return new Messages(
                msg(cfg, "prompt", "<gold>EasyGuard</gold> <gray>|</gray> <white>A single-use code was sent to Discord. Paste it in chat to play.</white>"),
                msg(cfg, "prompt_waiting", "<gray>Contacting Discord...</gray>"),
                msg(cfg, "success", "<green>Verified. Welcome, <white><player></white>!</green>"),
                msg(cfg, "wrong_code", "<red>Wrong code.</red> <gray><attempts> attempt(s) left.</gray>"),
                msg(cfg, "expired", "<red>That code expired. Reconnect to get a new one.</red>"),
                msg(cfg, "no_pending", "<gray>You have nothing to verify.</gray>"),
                msg(cfg, "usage", "<gray>Usage: /verify <code></gray>"),
                msg(cfg, "blocked_action", "<red>Verify first: paste the code from Discord in chat.</red>"),
                msg(cfg, "discord_down", "<red>Discord could not be reached.</red><newline><gray>Your code was written to the server console - ask the server owner for it.</gray>"),
                msg(cfg, "kick_timeout", "<red>Verification timed out.</red><newline><gray>Reconnect and paste the code from Discord.</gray>"),
                msg(cfg, "kick_out_of_attempts", "<red>Too many wrong codes.</red><newline><gray>Reconnect to request a new one.</gray>"),
                msg(cfg, "kick_unavailable", "<red>Verification is unavailable right now.</red><newline><gray>The server could not reach Discord. Try again shortly.</gray>"),
                msg(cfg, "kick_rate_limited", "<red>Too many verification requests.</red><newline><gray>Wait a moment and reconnect.</gray>"),
                msg(cfg, "kick_lockdown", "<red>This server is locked down.</red><newline><gray>EasyGuard could not start, so logins are blocked.</gray>"),
                cfg.getString("messages.discord_template",
                        "**Join request**\nPlayer: `<player>`\nUUID: `<uuid>`\nIP: `<ip>`\nCode: **<code>**\nExpires in <ttl>."));
    }

    private static String msg(FileConfiguration cfg, String key, String fallback) {
        return cfg.getString("messages." + key, fallback);
    }

    private static int requireRange(int value, int min, int max, String key) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ", got " + value);
        }
        return value;
    }
}
