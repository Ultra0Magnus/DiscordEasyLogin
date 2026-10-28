package io.github.ultra0magnus.discordeasylogin.test;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only plugin. Stands in for a chat-bridge plugin (DiscordSRV and friends): it listens at
 * MONITOR priority and does NOT ignore cancelled events, which is the worst realistic case.
 *
 * DiscordEasyLogin cancels an unverified player's chat at LOWEST priority, but cancelling does not stop
 * later listeners from seeing the event. If this probe can read the message, a real bridge could
 * forward a verification code somewhere it does not belong.
 *
 * Never install this on a real server: it prints chat messages, including codes, to the console.
 */
public final class ChatProbe extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("LeakProbe watching chat at MONITOR priority.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        getLogger().info("LEAKED[cancelled=" + event.isCancelled() + "] "
                + event.getPlayer().getName() + ": " + text);
    }

    /**
     * Many chat bridges and formatters still use the deprecated chat event. When any plugin
     * listens to it, Paper fires it before the modern one, so this is where an older bridge sees
     * the message.
     *
     * NORMAL, not LOWEST: DiscordEasyLogin's guard for this event sits at LOWEST, and a listener at that
     * same priority registered before it would run first. That case is documented as a limit; this
     * probe checks every priority a real bridge uses.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onLegacyChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        getLogger().info("LEGACY[cancelled=" + event.isCancelled() + "] "
                + event.getPlayer().getName() + ": " + event.getMessage());
    }
}
