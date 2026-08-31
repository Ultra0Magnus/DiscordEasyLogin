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

    public void hold(Player player, Duration timeout, Consumer<Player> onTimeout) {
        EasyGuardConfig cfg = config.get();
        Location anchor = player.getLocation().clone();

        if (cfg.applyBlindness()) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
        if (cfg.hideFromOthers()) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.getUniqueId().equals(player.getUniqueId())) {
                    other.hidePlayer(plugin, player);
                }
            }
        }

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (held.containsKey(player.getUniqueId())) {
                onTimeout.accept(player);
            }
        }, Math.max(1L, timeout.toSeconds() * 20L));

        held.put(player.getUniqueId(), new Held(anchor, task));
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
