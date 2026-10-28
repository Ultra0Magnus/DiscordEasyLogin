package io.github.ultra0magnus.easyguard;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

/**
 * Keeps unverified players from doing anything except answering with their code.
 *
 * <p>Two Bukkit details shape this class. Subclass events often have their own handler list
 * (PlayerTeleportEvent, PlayerArmorStandManipulateEvent), so a handler for the parent never sees
 * them. And an air click's PlayerInteractEvent arrives already cancelled, so a handler with
 * {@code ignoreCancelled = true} never sees it either - which is how bows, potions, ender pearls
 * and milk once worked for an unverified player.
 */
public final class GateListener implements Listener {

    private static final long NOTICE_COOLDOWN_MILLIS = 2000L;

    private static final Component UNAVAILABLE =
            Component.text("Verification is unavailable right now.", NamedTextColor.RED);

    private final EasyGuardPlugin plugin;
    private final GateService gate;
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    /** Text captured from the legacy chat event, which Paper fires before the modern one. */
    private final Map<UUID, String> legacyText = new ConcurrentHashMap<>();
    private volatile boolean legacyGuardRegistered;

    public GateListener(EasyGuardPlugin plugin, GateService gate) {
        this.plugin = plugin;
        this.gate = gate;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean holding;
        try {
            // Whoever joins must not see anyone still waiting to verify; hold() only hides a held
            // player from the people online at that moment.
            plugin.limbo().hideHeldFrom(player);
            holding = gate.onJoin(player);
        } catch (RuntimeException e) {
            // An exception escaping a join handler does not stop the join, so without this an
            // unexpected bug would let the player in with no code. Fail closed instead.
            plugin.getLogger().log(Level.SEVERE, "EasyGuard could not check " + player.getName()
                    + " - refusing the login rather than letting them in unverified", e);
            event.joinMessage(null);
            EasyGuardConfig cfg = plugin.configOrNull();
            player.kick(cfg == null ? UNAVAILABLE : Text.render(cfg.messages().kickUnavailable()),
                    PlayerKickEvent.Cause.PLUGIN);
            return;
        }
        if (holding && plugin.config().suppressJoinMessage()) {
            event.joinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        lastNotice.remove(player.getUniqueId());
        legacyText.remove(player.getUniqueId());
        gate.forgetRecentCode(player.getUniqueId());
        if (isHeld(player)) {
            if (plugin.config().suppressJoinMessage()) {
                event.quitMessage(null);
            }
            gate.onQuit(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String captured = legacyText.remove(player.getUniqueId());
        if (!isHeld(player)) {
            if (gate.isEchoOfUsedCode(player, plainText(event.message()))) {
                silence(event);
            }
            return;
        }
        // Never let an unverified player's text reach public chat - a fumbled paste would leak the code.
        String text = captured != null ? captured : plainText(event.message()).trim();
        silence(event);
        if (text.isEmpty()) {
            return; // nothing to check, and an empty message must not burn an attempt
        }
        Bukkit.getScheduler().runTask(plugin, () -> gate.submit(player, text));
    }

    /** Unverified players do not get to read the server's chat while they wait. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChatDelivery(AsyncChatEvent event) {
        event.viewers().removeIf(viewer -> viewer instanceof Player player && isHeld(player));
    }

    /**
     * Cancelling alone is not enough: listeners at later priorities (chat bridges, loggers) still
     * receive the event, and some ignore the cancelled flag. Leave them nothing to read.
     */
    private static void silence(AsyncChatEvent event) {
        event.setCancelled(true);
        event.message(Component.empty());
        event.viewers().clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        ensureLegacyChatGuard();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        ensureLegacyChatGuard();
    }

    /**
     * When any plugin listens to the deprecated chat event, Paper fires it before the modern one,
     * so those listeners would see a pasted code before {@link #onChat} could hide it. Guard that
     * event too - but only once someone listens to it, because registering a legacy listener of our
     * own would switch the whole server onto Paper's legacy chat path for nothing.
     */
    @SuppressWarnings("deprecation")
    private void ensureLegacyChatGuard() {
        if (legacyGuardRegistered || AsyncPlayerChatEvent.getHandlerList().getRegisteredListeners().length == 0) {
            return;
        }
        legacyGuardRegistered = true;
        plugin.getServer().getPluginManager().registerEvents(new LegacyChatGuard(this), plugin);
    }

    @SuppressWarnings("deprecation")
    void interceptLegacyChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (isHeld(player)) {
            // Kept for onChat, which submits it: one submission per message, whichever event Paper fires.
            legacyText.put(player.getUniqueId(), event.getMessage().trim());
        } else if (!gate.isEchoOfUsedCode(player, event.getMessage())) {
            return;
        }
        event.setCancelled(true);
        event.getRecipients().clear();
        event.setMessage("");
    }

    /**
     * Bukkit delivers teleports to PlayerTeleportEvent handlers only, never to the PlayerMoveEvent
     * handler below, so without this any plugin or /tp could relocate an unverified player.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!isHeld(player) || !plugin.config().freezeMovement()) {
            return;
        }
        // The freeze itself works by sending the player back to their anchor, and Paper carries
        // out a changed PlayerMoveEvent destination as a teleport. Those must go through.
        Location anchor = plugin.limbo().anchorOf(player.getUniqueId());
        if (anchor != null && sameBlock(anchor, event.getTo())) {
            return;
        }
        event.setCancelled(true);
    }

    private static boolean sameBlock(Location a, Location b) {
        return b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isHeld(player)) {
            return;
        }
        String message = event.getMessage().trim();
        String[] parts = message.split("\\s+");
        String label = parts[0].toLowerCase(Locale.ROOT);
        event.setCancelled(true);
        if (label.equals("/verify") || label.equals("/easyguard:verify")) {
            // Handled here rather than passed on, so the code never reaches the command log, other
            // plugins' command listeners, or another plugin that also registered "/verify".
            event.setMessage(label);
            if (parts.length != 2) {
                player.sendMessage(Text.render(plugin.config().messages().usage()));
                return;
            }
            gate.submit(player, parts[1]);
            return;
        }
        notice(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTabComplete(AsyncTabCompleteEvent event) {
        if (event.getSender() instanceof Player player && isHeld(player)) {
            // Tab completion lists online players and installed plugins; nothing to learn while held.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.config().freezeMovement() || !isHeld(event.getPlayer())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return; // looking around is fine
        }
        Location anchor = plugin.limbo().anchorOf(event.getPlayer().getUniqueId());
        Location target = (anchor == null ? from : anchor).clone();
        target.setYaw(to.getYaw());
        target.setPitch(to.getPitch());
        event.setTo(target);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        cancelIfHeld(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        cancelIfHeld(event.getPlayer(), event);
    }

    /** Deliberately not {@code ignoreCancelled}: Bukkit creates every air click already cancelled. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isHeld(player)) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (event.getAction() != Action.PHYSICAL) {
            notice(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        cancelIfHeld(event.getPlayer(), event);
    }

    /** Has its own handler list, so onInteractEntity never sees it. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        cancelIfHeld(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        cancelIfHeld(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && isHeld(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player && isHeld(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        cancelIfHeld(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && isHeld(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player && isHeld(player)) {
            event.setCancelled(true);
        }
    }

    /** Respawning clears every potion effect, blindness included. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (isHeld(player)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isConnected() && isHeld(player)) {
                    plugin.limbo().applyBlindness(player);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            cancelIfHeld(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            cancelIfHeld(player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        cancelIfHeld(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isHeld(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isHeld(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = attackerOf(event.getDamager());
        if (attacker != null && isHeld(attacker)) {
            event.setCancelled(true);
        }
    }

    /** The player really behind a hit: arrows, potions, primed TNT and pets all count. */
    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) {
            return player;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return player;
        }
        if (damager instanceof Tameable pet && pet.getOwner() instanceof Player player) {
            return player;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && isHeld(player)) {
            event.setCancelled(true);
        }
    }

    private boolean isHeld(Player player) {
        return plugin.limbo().isHeld(player.getUniqueId());
    }

    private static String plainText(Component message) {
        return PlainTextComponentSerializer.plainText().serialize(message);
    }

    private void cancelIfHeld(Player player, org.bukkit.event.Cancellable event) {
        if (isHeld(player)) {
            event.setCancelled(true);
            notice(player);
        }
    }

    private void notice(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < NOTICE_COOLDOWN_MILLIS) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        player.sendMessage(Text.render(plugin.config().messages().blockedAction()));
    }

    /** Registered on demand by {@link #ensureLegacyChatGuard}; see there for why. */
    private static final class LegacyChatGuard implements Listener {

        private final GateListener gate;

        LegacyChatGuard(GateListener gate) {
            this.gate = gate;
        }

        @SuppressWarnings("deprecation")
        @EventHandler(priority = EventPriority.LOWEST)
        public void onLegacyChat(AsyncPlayerChatEvent event) {
            gate.interceptLegacyChat(event);
        }
    }
}
