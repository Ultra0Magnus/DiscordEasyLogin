package io.github.ultra0magnus.easyguard.test;

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
 * EasyGuard cancels an unverified player's chat at LOWEST priority, but cancelling does not stop
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
}
