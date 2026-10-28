package io.github.ultra0magnus.easyguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class EasyGuardConfigTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/123/abc";

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(text);
        return cfg;
    }

    @Test
    void pingsNobodyByDefault() throws Exception {
        assertEquals(List.of(), EasyGuardConfig.loadPingUserIds(yaml("discord: {}")));
    }

    @Test
    void readsPingUserIdsQuotedOrNot() throws Exception {
        // YAML turns an unquoted 18-digit ID into a number; both forms must work.
        List<String> ids = EasyGuardConfig.loadPingUserIds(yaml("""
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
                    () -> EasyGuardConfig.loadPingUserIds(cfg), bad);
            assertTrue(error.getMessage().contains("discord.ping_user_ids"), error.getMessage());
        }
    }

    @Test
    void loadsAMinimalConfigWithSafeDefaults() throws Exception {
        assumeTrue(System.getenv(EasyGuardConfig.WEBHOOK_ENV_VAR) == null, "the env var overrides the file");
        EasyGuardConfig cfg = EasyGuardConfig.load(yaml("discord:\n  webhook_url: \"" + WEBHOOK + "\"\n"));

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
        assertThrows(IllegalArgumentException.class, () -> EasyGuardConfig.load(yaml(
                "discord:\n  webhook_url: \"" + WEBHOOK + "\"\ncode:\n  length: 3\n")));
        assertThrows(IllegalArgumentException.class, () -> EasyGuardConfig.load(yaml(
                "discord:\n  webhook_url: \"" + WEBHOOK + "\"\ncode:\n  max_attempts: 50\n")));
    }

    @Test
    void refusesCodesShortEnoughToBruteForce() throws Exception {
        assumeTrue(System.getenv(EasyGuardConfig.WEBHOOK_ENV_VAR) == null, "the env var overrides the file");
        // 31^5 codes at the most generous limits falls in under two days; 6 is the floor.
        assertThrows(IllegalArgumentException.class, () -> EasyGuardConfig.load(yaml(
                "discord:\n  webhook_url: \"" + WEBHOOK + "\"\ncode:\n  length: 5\n")));
        assertEquals(6, EasyGuardConfig.load(yaml(
                "discord:\n  webhook_url: \"" + WEBHOOK + "\"\ncode:\n  length: 6\n")).codeLength());
    }

    @Test
    void refusesWebhookNamesDiscordWouldRejectOnEveryPost() {
        for (String bad : List.of("My Discord Bot", "clyde", "", "   ")) {
            assertThrows(IllegalArgumentException.class, () -> EasyGuardConfig.webhookUsername(bad), bad);
        }
        assertEquals("EasyGuard", EasyGuardConfig.webhookUsername("  EasyGuard "));
    }

    @Test
    void refusesAMissingWebhook() throws Exception {
        assumeTrue(System.getenv(EasyGuardConfig.WEBHOOK_ENV_VAR) == null, "the env var overrides the file");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EasyGuardConfig.load(yaml("enabled: true\n")));
        assertFalse(error.getMessage().isBlank());
    }
}
