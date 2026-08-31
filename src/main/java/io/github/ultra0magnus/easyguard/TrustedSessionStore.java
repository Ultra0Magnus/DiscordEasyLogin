package io.github.ultra0magnus.easyguard;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    public void save() throws IOException {
        prune();
        Properties props = new Properties();
        expiries.forEach((uuid, expiry) -> props.setProperty(uuid.toString(), Long.toString(expiry)));

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, "EasyGuard trusted sessions - uuid=expiryEpochMillis");
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
