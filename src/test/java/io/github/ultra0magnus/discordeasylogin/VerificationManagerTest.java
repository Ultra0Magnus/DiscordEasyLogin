package io.github.ultra0magnus.discordeasylogin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ultra0magnus.discordeasylogin.VerificationManager.Outcome;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerificationManagerTest {

    private final long[] now = {1_000_000L};
    private final UUID player = UUID.randomUUID();

    private VerificationManager manager(long ttlMillis, int attempts, long cooldownMillis, int perMinute) {
        return new VerificationManager(
                new VerificationManager.Limits(ttlMillis, attempts, cooldownMillis, perMinute), () -> now[0]);
    }

    @Test
    void acceptsTheCorrectCodeExactlyOnce() {
        VerificationManager manager = manager(60_000, 3, 0, 100);
        manager.issue(player, "ABCD1234");

        assertEquals(Outcome.SUCCESS, manager.verify(player, "ABCD1234"));
        assertEquals(Outcome.NO_PENDING, manager.verify(player, "ABCD1234"));
        assertNull(manager.peek(player));
    }

    @Test
    void normalisesCaseAndSurroundingWhitespace() {
        VerificationManager manager = manager(60_000, 3, 0, 100);
        manager.issue(player, "ABCD1234");

        assertEquals(Outcome.SUCCESS, manager.verify(player, "  abcd1234 "));
    }

    @Test
    void spendsAttemptsAndThenGivesUp() {
        VerificationManager manager = manager(60_000, 3, 0, 100);
        manager.issue(player, "ABCD1234");

        assertEquals(Outcome.WRONG_CODE, manager.verify(player, "WRONG111"));
        assertEquals(2, manager.attemptsLeft(player));
        assertEquals(Outcome.WRONG_CODE, manager.verify(player, "WRONG222"));
        assertEquals(1, manager.attemptsLeft(player));
        assertEquals(Outcome.OUT_OF_ATTEMPTS, manager.verify(player, "WRONG333"));

        // The code is gone, so the real one no longer works either.
        assertEquals(Outcome.NO_PENDING, manager.verify(player, "ABCD1234"));
    }

    @Test
    void expiresCodesAfterTheirTtl() {
        VerificationManager manager = manager(60_000, 3, 0, 100);
        manager.issue(player, "ABCD1234");

        now[0] += 59_999;
        assertEquals(Outcome.SUCCESS, manager.verify(player, "ABCD1234"));

        manager.issue(player, "EFGH5678");
        now[0] += 60_001;
        assertEquals(Outcome.EXPIRED, manager.verify(player, "EFGH5678"));
        assertNull(manager.peek(player));
    }

    @Test
    void rejectsEmptyAndNullSubmissions() {
        VerificationManager manager = manager(60_000, 3, 0, 100);
        manager.issue(player, "ABCD1234");

        assertEquals(Outcome.WRONG_CODE, manager.verify(player, null));
        assertEquals(Outcome.WRONG_CODE, manager.verify(player, ""));
    }

    @Test
    void enforcesPerPlayerCooldown() {
        VerificationManager manager = manager(60_000, 3, 10_000, 100);
        assertTrue(manager.canIssue(player));
        manager.issue(player, "ABCD1234");

        assertFalse(manager.canIssue(player));
        now[0] += 9_999;
        assertFalse(manager.canIssue(player));
        now[0] += 2;
        assertTrue(manager.canIssue(player));
    }

    @Test
    void enforcesGlobalCodesPerMinuteCeiling() {
        VerificationManager manager = manager(60_000, 3, 0, 3);
        for (int i = 0; i < 3; i++) {
            UUID other = UUID.randomUUID();
            assertTrue(manager.canIssue(other));
            manager.issue(other, "ABCD123" + i);
        }

        assertFalse(manager.canIssue(UUID.randomUUID()));

        now[0] += 60_001;
        assertTrue(manager.canIssue(UUID.randomUUID()));
    }

    @Test
    void cancelDiscardsAPendingCode() {
        VerificationManager manager = manager(60_000, 3, 0, 100);
        manager.issue(player, "ABCD1234");
        manager.cancel(player);

        assertEquals(Outcome.NO_PENDING, manager.verify(player, "ABCD1234"));
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void cooldownSurvivesCancelSoReconnectingCannotMintCodes() {
        // Regression: cancel() on quit used to erase the cooldown, so one account could
        // reconnect in a loop and post a fresh code to Discord every time.
        VerificationManager manager = manager(60_000, 3, 10_000, 100);
        manager.issue(player, "ABCD1234");
        manager.cancel(player);

        assertFalse(manager.canIssue(player));
        now[0] += 10_001;
        assertTrue(manager.canIssue(player));
    }

    @Test
    void successClearsTheCooldown() {
        VerificationManager manager = manager(60_000, 3, 10_000, 100);
        manager.issue(player, "ABCD1234");
        assertEquals(Outcome.SUCCESS, manager.verify(player, "ABCD1234"));

        assertTrue(manager.canIssue(player));
    }

    @Test
    void reconfigureKeepsPendingCodesAndCooldowns() {
        VerificationManager manager = manager(60_000, 3, 10_000, 100);
        manager.issue(player, "ABCD1234");

        // A config reload swaps the limits; it must not strand a player whose code is already in Discord.
        manager.configure(new VerificationManager.Limits(30_000, 5, 20_000, 100));

        assertFalse(manager.canIssue(player));
        assertEquals(Outcome.SUCCESS, manager.verify(player, "ABCD1234"));

        UUID other = UUID.randomUUID();
        manager.issue(other, "EFGH5678");
        assertEquals(5, manager.attemptsLeft(other));
    }
}
