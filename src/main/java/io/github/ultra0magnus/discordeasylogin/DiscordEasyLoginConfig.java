package io.github.ultra0magnus.discordeasylogin;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of config.yml, validated at load time.
 * A validation failure is fatal on purpose: the plugin locks the server down rather than
 * running with a gate it cannot trust.
 */
public record DiscordEasyLoginConfig(
        boolean enabled,
        URI webhookEndpoint,
        String webhookUsername,
        Duration webhookTimeout,
        String discordMentionPrefix,
        List<String> pingUserIds,
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
            String kickManualLockdown,
            String discordTemplate) {
    }

    public static final String WEBHOOK_ENV_VAR = "DISCORDEASYLOGIN_WEBHOOK";

    /** The variable from when this plugin was called EasyGuard, still honoured so an upgrade does not break. */
    public static final String LEGACY_WEBHOOK_ENV_VAR = "EASYGUARD_DISCORD_WEBHOOK";

    /** Discord user IDs are snowflakes: 17 to 20 digits. */
    private static final Pattern DISCORD_USER_ID = Pattern.compile("\\d{17,20}");

    /** Discord rejects an allowed_mentions.users list longer than this. */
    private static final int MAX_PING_USERS = 100;

    public static DiscordEasyLoginConfig load(FileConfiguration cfg) {
        boolean enabled = cfg.getBoolean("enabled", true);
        boolean allowNonDiscordHost = cfg.getBoolean("discord.allow_non_discord_host", false);

        String envUrl = webhookFromEnvironment();
        String rawUrl = envUrl != null && !envUrl.isBlank() ? envUrl : cfg.getString("discord.webhook_url", "");
        URI endpoint = DiscordWebhookNotifier.validateEndpoint(rawUrl, allowNonDiscordHost);

        // At least 6: with the most generous attempt and rate limits, a 4-character code could be
        // brute-forced in about an hour and a half.
        int codeLength = requireRange(cfg.getInt("code.length", 8), 6, 32, "code.length");
        int maxAttempts = requireRange(cfg.getInt("code.max_attempts", 3), 1, 10, "code.max_attempts");
        int maxPerMinute = requireRange(cfg.getInt("code.max_per_minute", 20), 1, 500, "code.max_per_minute");
        int trustHours = requireRange(cfg.getInt("trust.duration_hours", 24), 0, 24 * 365, "trust.duration_hours");

        return new DiscordEasyLoginConfig(
                enabled,
                endpoint,
                webhookUsername(cfg.getString("discord.username", "EasyLogin")),
                Duration.ofSeconds(requireRange(cfg.getInt("discord.timeout_seconds", 5), 1, 30, "discord.timeout_seconds")),
                cfg.getString("discord.mention_prefix", ""),
                loadPingUserIds(cfg),
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
                msg(cfg, "prompt", "<gold>DiscordEasyLogin</gold> <gray>|</gray> <white>A single-use code was sent to Discord. Paste it in chat to play.</white>"),
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
                msg(cfg, "kick_lockdown", "<red>This server is locked down.</red><newline><gray>DiscordEasyLogin could not start, so logins are blocked.</gray>"),
                msg(cfg, "kick_manual_lockdown", "<red>This server is locked down.</red><newline><gray>An administrator has paused all logins.</gray>"),
                cfg.getString("messages.discord_template",
                        "**Join request**\nPlayer: `<player>`\nUUID: `<uuid>`\nCode: **<code>**\nExpires in <ttl>."));
    }

    /** The webhook URL from the environment, preferring the current variable over the legacy one. */
    static String webhookFromEnvironment() {
        String current = System.getenv(WEBHOOK_ENV_VAR);
        if (current != null && !current.isBlank()) {
            return current;
        }
        return System.getenv(LEGACY_WEBHOOK_ENV_VAR);
    }

    /**
     * Discord rejects every post (HTTP 400) from a webhook name containing "discord" or "clyde",
     * which would turn a cosmetic setting into a server nobody can join. Refuse it at load instead.
     */
    static String webhookUsername(String raw) {
        String name = raw == null ? "" : raw.trim();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (name.isEmpty() || name.length() > 80 || lower.contains("discord") || lower.contains("clyde")) {
            throw new IllegalArgumentException("discord.username must be 1-80 characters and must not contain "
                    + "'discord' or 'clyde' (Discord rejects those), got '" + name + "'");
        }
        return name;
    }

    /**
     * The people to ping for every join request. Validated strictly: these go into the webhook's
     * allowed_mentions list, which is the only thing that lets a message ping anyone at all.
     */
    static List<String> loadPingUserIds(FileConfiguration cfg) {
        List<String> ids = new ArrayList<>();
        for (Object raw : cfg.getList("discord.ping_user_ids", List.of())) {
            String id = String.valueOf(raw).trim();
            if (!DISCORD_USER_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("discord.ping_user_ids must contain Discord user IDs "
                        + "(17-20 digits; right-click your name with Developer Mode on -> Copy User ID), got '"
                        + id + "'");
            }
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.size() > MAX_PING_USERS) {
            throw new IllegalArgumentException("discord.ping_user_ids can list at most " + MAX_PING_USERS
                    + " users, got " + ids.size());
        }
        return List.copyOf(ids);
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
