package io.github.ultra0magnus.easyguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Denies every login while EasyGuard is locked down.
 *
 * <p>This exists so that a misconfigured or broken EasyGuard blocks the door instead of
 * disappearing: a plugin that fails to start would otherwise leave the server wide open.
 */
public final class LockdownListener implements Listener {

    private static final Component FALLBACK_REASON = Component
            .text("This server is locked down: EasyGuard could not start.", NamedTextColor.RED);

    private final EasyGuardPlugin plugin;

    public LockdownListener(EasyGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        deny(event);
    }

    /** Again at HIGHEST, so another plugin calling {@code allow()} in between cannot reopen the door. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLoginFinal(AsyncPlayerPreLoginEvent event) {
        deny(event);
    }

    private void deny(AsyncPlayerPreLoginEvent event) {
        if (!plugin.isLockedDown()) {
            return;
        }
        EasyGuardConfig cfg = plugin.configOrNull();
        Component reason;
        if (cfg == null) {
            reason = FALLBACK_REASON;
        } else if (plugin.isConfigFailed()) {
            reason = Text.render(cfg.messages().kickLockdown());
        } else {
            reason = Text.render(cfg.messages().kickManualLockdown());
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, reason);
    }
}
