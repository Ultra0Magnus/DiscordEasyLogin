package io.github.ultra0magnus.discordeasylogin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DiscordEasyLoginConfigTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/123/abc";

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(text);
        return cfg;
    }

    @Test
    void pingsNobodyByDefault() throws Exception {
        assertEquals(List.of(), DiscordEasyLoginConfig.loadPingUserIds(yaml("discord: {}")));
    }

    @Test
    void readsPingUserIdsQuotedOrNot() throws Exception {
        // YAML turns an unquoted 18-digit ID into a number; both forms must work.
        List<String> ids = DiscordEasyLoginConfig.loadPingUserIds(yaml("""
                discord:
                  ping_user_ids: ["123456789012345678", 234567890123456789, "123456789012345678"]
                """));
        assertEquals(List.of("123456789012345678", "234567890123456789"), ids);
    }

    @Test
    void rejectsAnythingThatIsNotAUserId() throws Exception {
        // These IDs go into allowed_mentions, the one thing that lets a message ping anyone.
        for (String bad : List.of("\"@everyone\"", "\"<@123456789012345678>\"", "\"12345\"", "\"here\"")) {
            YamlConfiguration cfg = yaml("discord:\n  ping_user_ids: [" + bad + "]\n");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DiscordEasyLoginConfig.loadPingUserIds(cfg), bad);
            assertTrue(error.getMessage().contains("discord.ping_user_ids"), error.getMessage());
        }
    }

    @Test
    void loadsAMinimalConfigWithSafeDefaults() throws Exception {
        assumeTrue(System.getenv(DiscordEasyLoginConfig.WEBHOOK_ENV_VAR) == null, "the env var overrides the file");
        DiscordEasyLoginConfig cfg = DiscordEasyLoginConfig.load(yaml("discord:\n  webhook_url: \"" + WEBHOOK + "\"\n"));

        assertTrue(cfg.enabled());
        assertEquals("discord.com", cfg.webhookEndpoint().getHost());
        assertEquals(List.of(), cfg.pingUserIds());
        assertEquals(3, cfg.maxAttempts());
        assertTrue(cfg.freezeMovement());
        assertTrue(cfg.hideFromOthers());
        assertTrue(cfg.messages().kickManualLockdown().contains("administrator"));
    }

    @Test
    void refusesOutOfRangeValuesRatherThanRunningAWeakGate() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> DiscordEasyLoginConfig.load(yaml(
                "discord:\n  webhook_url: \"" + WEBHOOK + "\"\ncode:\n  length: 3\n")));
        assertThrows(IllegalArgumentException.class, () -> DiscordEasyLoginConfig.load(yaml(
                "discord:\n  webhook_url: \"" + WEBHOOK + "\"\ncode:\n  max_attempts: 50\n")));
    }

    @Test
    void sendsNoIpAddressToDiscordByDefault() throws Exception {
        assumeTrue(System.getenv(DiscordEasyLoginConfig.WEBHOOK_ENV_VAR) == null, "the env var overrides the file");
        assumeTrue(System.getenv(DiscordEasyLoginConfig.LEGACY_WEBHOOK_ENV_VAR) == null, "the env var overrides the file");
        // A player's IP is personal data, and the default message goes to a third party (Discord).
        DiscordEasyLoginConfig cfg = DiscordEasyLoginConfig.load(yaml("discord:\n  webhook_url: \"" + WEBHOOK + "\"\n"));
        assertFalse(cfg.messages().discordTemplate().contains("<ip>"), cfg.messages().discordTemplate());
        assertTrue(cfg.messages().discordTemplate().contains("<code>"), cfg.messages().discordTemplate());
    }

    @Test
    void refusesCodesShortEnoughToBruteForce() throws Exception {
        assumeTrue(System.getenv(DiscordEasyLoginConfig.WEBHOOK_ENV_VAR) == null, "the env var overrides the file");
        // 31^5 codes at the most generous limits falls in under two days; 6 is the floor.
        assertThrows(IllegalArgumentException.class, () -> DiscordEasyLoginConfig.load(yaml(
                "discord:\n  webhook_url: \"" + WEBHOOK + "\"\ncode:\n  length: 5\n")));
        assertEquals(6, DiscordEasyLoginConfig.load(yaml(
                "discord:\n  webhook_url: \"" + WEBHOOK + "\"\ncode:\n  length: 6\n")).codeLength());
    }

    @Test
    void refusesWebhookNamesDiscordWouldRejectOnEveryPost() {
        for (String bad : List.of("My Discord Bot", "clyde", "", "   ")) {
            assertThrows(IllegalArgumentException.class, () -> DiscordEasyLoginConfig.webhookUsername(bad), bad);
        }
        assertEquals("EasyLogin", DiscordEasyLoginConfig.webhookUsername("  EasyLogin "));
        // The plugin's own name contains "discord", so it can never be the webhook name.
        assertThrows(IllegalArgumentException.class, () -> DiscordEasyLoginConfig.webhookUsername("DiscordEasyLogin"));
    }

    @Test
    void refusesAMissingWebhook() throws Exception {
        assumeTrue(System.getenv(DiscordEasyLoginConfig.WEBHOOK_ENV_VAR) == null, "the env var overrides the file");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DiscordEasyLoginConfig.load(yaml("enabled: true\n")));
        assertFalse(error.getMessage().isBlank());
    }
}
