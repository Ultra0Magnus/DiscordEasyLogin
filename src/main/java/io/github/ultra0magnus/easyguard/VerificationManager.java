package io.github.ultra0magnus.easyguard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Holds the outstanding verification codes and enforces their lifetime, attempt budget and issue rate.
 * Codes live in memory only and never touch disk.
 *
 * <p>One instance lives for the whole plugin lifetime: a config reload swaps the {@link Limits}
 * with {@link #configure}, so codes already sent to Discord and cooldowns already running survive it.
 */
public final class VerificationManager {

    public enum Outcome { SUCCESS, WRONG_CODE, NO_PENDING, EXPIRED, OUT_OF_ATTEMPTS }

    public record Pending(String code, long issuedAt, long expiresAt, int attemptsLeft) {

        public Pending withAttemptSpent() {
            return new Pending(code, issuedAt, expiresAt, attemptsLeft - 1);
        }
    }

    public record Limits(long codeTtlMillis, int maxAttempts, long perPlayerCooldownMillis, int maxCodesPerMinute) {
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastIssued = new ConcurrentHashMap<>();
    private final Deque<Long> recentIssues = new ArrayDeque<>();
    private final LongSupplier clock;

    private volatile Limits limits;

    public VerificationManager(Limits limits, LongSupplier clock) {
        this.limits = limits;
        this.clock = clock;
    }

    /** Applies new limits to future codes. Codes already issued keep the TTL and attempts they were issued with. */
    public void configure(Limits limits) {
        this.limits = limits;
    }

    /**
     * @return true if a new code may be issued to this player right now.
     */
    public boolean canIssue(UUID player) {
        Limits current = limits;
        long now = clock.getAsLong();
        Long last = lastIssued.get(player);
        if (last != null && now - last < current.perPlayerCooldownMillis()) {
            return false;
        }
        synchronized (recentIssues) {
            pruneRecentIssues(now);
            return recentIssues.size() < current.maxCodesPerMinute();
        }
    }

    public Pending issue(UUID player, String code) {
        Limits current = limits;
        long now = clock.getAsLong();
        Pending entry = new Pending(code, now, now + current.codeTtlMillis(), current.maxAttempts());
        pending.put(player, entry);
        // Entries past the cooldown can no longer block anyone; dropping them keeps the map bounded.
        lastIssued.values().removeIf(issuedAt -> now - issuedAt >= current.perPlayerCooldownMillis());
        lastIssued.put(player, now);
        synchronized (recentIssues) {
            pruneRecentIssues(now);
            recentIssues.addLast(now);
        }
        return entry;
    }

    public Outcome verify(UUID player, String input) {
        Pending entry = pending.get(player);
        if (entry == null) {
            return Outcome.NO_PENDING;
        }
        if (clock.getAsLong() > entry.expiresAt()) {
            pending.remove(player);
            return Outcome.EXPIRED;
        }
        String candidate = input == null ? "" : input.trim().toUpperCase(java.util.Locale.ROOT);
        if (MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),
                entry.code().getBytes(StandardCharsets.UTF_8))) {
            pending.remove(player);
            // A verified player is not who the cooldown exists to slow down.
            lastIssued.remove(player);
            return Outcome.SUCCESS;
        }
        Pending spent = entry.withAttemptSpent();
        if (spent.attemptsLeft() <= 0) {
            pending.remove(player);
            return Outcome.OUT_OF_ATTEMPTS;
        }
        pending.put(player, spent);
        return Outcome.WRONG_CODE;
    }

    public Pending peek(UUID player) {
        return pending.get(player);
    }

    public int attemptsLeft(UUID player) {
        Pending entry = pending.get(player);
        return entry == null ? 0 : entry.attemptsLeft();
    }

    /**
     * Drops the player's pending code (quit, timeout, kick). The cooldown is deliberately kept:
     * clearing it here is what let one account reconnect in a loop and post a fresh code to
     * Discord every time.
     */
    public void cancel(UUID player) {
        pending.remove(player);
    }

    public void clearAll() {
        pending.clear();
        lastIssued.clear();
        synchronized (recentIssues) {
            recentIssues.clear();
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    private void pruneRecentIssues(long now) {
        while (!recentIssues.isEmpty() && now - recentIssues.peekFirst() >= 60_000L) {
            recentIssues.removeFirst();
        }
    }
}
