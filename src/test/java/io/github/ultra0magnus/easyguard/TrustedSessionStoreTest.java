package io.github.ultra0magnus.easyguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustedSessionStoreTest {

    private final long[] now = {1_700_000_000_000L};
    private final UUID player = UUID.randomUUID();

    private TrustedSessionStore store(Path dir) {
        return new TrustedSessionStore(dir.resolve("sessions.properties"), () -> now[0]);
    }

    @Test
    void trustsForTheGivenWindowThenExpires(@TempDir Path dir) {
        TrustedSessionStore store = store(dir);
        assertFalse(store.isTrusted(player));

        store.trust(player, 3600_000L);
        assertTrue(store.isTrusted(player));

        now[0] += 3599_999L;
        assertTrue(store.isTrusted(player));
        now[0] += 2;
        assertFalse(store.isTrusted(player));
    }

    @Test
    void zeroDurationNeverTrusts(@TempDir Path dir) {
        TrustedSessionStore store = store(dir);
        store.trust(player, 0);
        assertFalse(store.isTrusted(player));
    }

    @Test
    void survivesARestart(@TempDir Path dir) throws IOException {
        TrustedSessionStore first = store(dir);
        first.trust(player, 86_400_000L);
        first.save();

        TrustedSessionStore second = store(dir);
        second.load();
        assertTrue(second.isTrusted(player));
    }

    @Test
    void doesNotReloadExpiredSessions(@TempDir Path dir) throws IOException {
        TrustedSessionStore first = store(dir);
        first.trust(player, 60_000L);
        first.save();

        now[0] += 60_001L;
        TrustedSessionStore second = store(dir);
        second.load();
        assertFalse(second.isTrusted(player));
        assertEquals(0, second.size());
    }

    @Test
    void ignoresCorruptLinesInsteadOfFailingToStart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sessions.properties");
        Files.writeString(file, "not-a-uuid=123\n" + player + "=not-a-number\n");

        TrustedSessionStore store = store(dir);
        store.load();
        assertEquals(0, store.size());
        assertFalse(store.isTrusted(player));
    }

    @Test
    void revokeAndPruneDropSessions(@TempDir Path dir) {
        TrustedSessionStore store = store(dir);
        UUID other = UUID.randomUUID();
        store.trust(player, 60_000L);
        store.trust(other, 60_000L);

        store.revoke(player);
        assertFalse(store.isTrusted(player));
        assertEquals(1, store.size());

        now[0] += 60_001L;
        assertEquals(1, store.prune());
        assertEquals(0, store.size());
    }

    @Test
    void loadOnMissingFileIsNotAnError(@TempDir Path dir) throws IOException {
        TrustedSessionStore store = store(dir);
        store.load();
        assertEquals(0, store.size());
    }
}
