package io.github.ultra0magnus.discordeasylogin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Remembers which players already proved themselves, so a reconnect inside the trust window
 * does not send another code to Discord. Persisted as {@code uuid=expiryEpochMillis}.
 */
public final class TrustedSessionStore {

    /** Static: there is one sessions file, whatever the number of store instances. */
    private static final Object SAVE_LOCK = new Object();

    private final Path file;
    private final LongSupplier clock;
    private final Map<UUID, Long> expiries = new ConcurrentHashMap<>();

    public TrustedSessionStore(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
    }

    public void load() throws IOException {
        expiries.clear();
        if (!Files.exists(file)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        long now = clock.getAsLong();
        for (String key : props.stringPropertyNames()) {
            try {
                long expiry = Long.parseLong(props.getProperty(key).trim());
                if (expiry > now) {
                    expiries.put(UUID.fromString(key), expiry);
                }
            } catch (IllegalArgumentException ignored) {
                // A corrupt line must not stop the gate from starting; dropping it just forces re-verification.
            }
        }
    }

    public boolean isTrusted(UUID player) {
        Long expiry = expiries.get(player);
        if (expiry == null) {
            return false;
        }
        if (expiry <= clock.getAsLong()) {
            expiries.remove(player);
            return false;
        }
        return true;
    }

    public void trust(UUID player, long durationMillis) {
        if (durationMillis <= 0) {
            return;
        }
        expiries.put(player, clock.getAsLong() + durationMillis);
    }

    public void revoke(UUID player) {
        expiries.remove(player);
    }

    public void revokeAll() {
        expiries.clear();
    }

    public int prune() {
        long now = clock.getAsLong();
        int before = expiries.size();
        expiries.values().removeIf(expiry -> expiry <= now);
        return before - expiries.size();
    }

    public int size() {
        return expiries.size();
    }

    /**
     * Saves are called from several threads (the async save after a verification, the prune timer,
     * {@code trust clear} on the main thread). They are serialized, and each takes its snapshot
     * inside the lock: otherwise a save that read the sessions before a {@code trust clear all} could
     * finish writing after it and bring revoked trust back on the next restart.
     */
    public void save() throws IOException {
        synchronized (SAVE_LOCK) {
            prune();
            Properties props = new Properties();
            expiries.forEach((uuid, expiry) -> props.setProperty(uuid.toString(), Long.toString(expiry)));

            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                 OutputStream out = Channels.newOutputStream(channel)) {
                props.store(out, "DiscordEasyLogin trusted sessions - uuid=expiryEpochMillis");
                out.flush();
                // On disk before the rename, so a crash cannot leave an empty file in its place.
                channel.force(true);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
