package io.github.ultra0magnus.easyguard;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Holds unverified players in place: frozen, blinded, invisible to everyone else, and on a
 * countdown. All methods here touch Bukkit state and must run on the main thread.
 */
public final class LimboManager {

    private record Held(Location anchor, BukkitTask timeoutTask) {
    }

    private final Plugin plugin;
    private final Supplier<EasyGuardConfig> config;
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();

    public LimboManager(Plugin plugin, Supplier<EasyGuardConfig> config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Holds the player and returns a token for this particular session. Deferred work (a Discord
     * failure arriving seconds later) must check {@link #isCurrentSession} with it: by then the
     * same account may have quit and rejoined, and acting on the new session would free it.
     */
    public Object hold(Player player, Duration timeout, Consumer<Player> onTimeout) {
        EasyGuardConfig cfg = config.get();
        // Vanilla remounts a player who logged out riding something, and riding is not walking:
        // PlayerMoveEvent never fires for it. Put them on their feet first.
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        Location anchor = player.getLocation().clone();

        applyBlindness(player);
        if (cfg.hideFromOthers()) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.getUniqueId().equals(player.getUniqueId())) {
                    other.hidePlayer(plugin, player);
                }
            }
        }

        Held[] session = new Held[1];
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (held.get(player.getUniqueId()) == session[0]) {
                onTimeout.accept(player);
            }
        }, Math.max(1L, timeout.toSeconds() * 20L));

        session[0] = new Held(anchor, task);
        held.put(player.getUniqueId(), session[0]);
        return session[0];
    }

    /** @return true only if the player is held and it is still the session {@code token} came from */
    public boolean isCurrentSession(UUID uuid, Object token) {
        return token != null && held.get(uuid) == token;
    }

    /** Re-blinds a held player; respawning clears every potion effect. */
    public void applyBlindness(Player player) {
        if (config.get().applyBlindness()) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
    }

    /** Hides every currently held player from someone who has just joined. */
    public void hideHeldFrom(Player viewer) {
        if (held.isEmpty() || !config.get().hideFromOthers()) {
            return;
        }
        for (UUID uuid : held.keySet()) {
            Player heldPlayer = Bukkit.getPlayer(uuid);
            if (heldPlayer != null && !heldPlayer.getUniqueId().equals(viewer.getUniqueId())) {
                viewer.hidePlayer(plugin, heldPlayer);
            }
        }
    }

    /** Restores a player who verified successfully. */
    public void release(Player player) {
        Held state = held.remove(player.getUniqueId());
        if (state != null) {
            state.timeoutTask().cancel();
        }
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        for (Player other : Bukkit.getOnlinePlayers()) {
            other.showPlayer(plugin, player);
        }
    }

    /** Drops tracking for a player who is already gone (quit or kicked). */
    public void discard(UUID uuid) {
        Held state = held.remove(uuid);
        if (state != null) {
            state.timeoutTask().cancel();
        }
    }

    /**
     * Called as a held player quits. Besides dropping the hold, undo the hiding: Paper remembers
     * hidden players by UUID for as long as the viewer stays online, so without this the same
     * account would stay invisible to them on a later join that is never held (bypass, gate off).
     */
    public void forget(Player player) {
        discard(player.getUniqueId());
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.getUniqueId().equals(player.getUniqueId())) {
                other.showPlayer(plugin, player);
            }
        }
    }

    public void releaseAll() {
        for (UUID uuid : Set.copyOf(held.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                release(player);
            } else {
                discard(uuid);
            }
        }
    }

    public boolean isHeld(UUID uuid) {
        return held.containsKey(uuid);
    }

    public Location anchorOf(UUID uuid) {
        Held state = held.get(uuid);
        return state == null ? null : state.anchor();
    }

    public int heldCount() {
        return held.size();
    }
}
