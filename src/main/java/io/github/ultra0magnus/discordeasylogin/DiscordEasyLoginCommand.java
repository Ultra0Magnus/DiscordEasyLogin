package io.github.ultra0magnus.discordeasylogin;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@code /discordeasylogin status|reload|code|trust|lockdown} - administration. */
public final class DiscordEasyLoginCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS = List.of("status", "reload", "code", "trust", "lockdown");

    private final DiscordEasyLoginPlugin plugin;

    public DiscordEasyLoginCommand(DiscordEasyLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String permission() {
        return "discordeasylogin.admin";
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            sender.sendMessage(Component.text("/discordeasylogin <status|reload|code|trust|lockdown>", NamedTextColor.GRAY));
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender);
            case "reload" -> reload(sender);
            case "code" -> showCode(sender, args);
            case "trust" -> trust(sender, args);
            case "lockdown" -> lockdown(sender, args);
            default -> sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
    }

    private void status(CommandSender sender) {
        DiscordEasyLoginConfig cfg = plugin.configOrNull();
        sender.sendMessage(Component.text("DiscordEasyLogin status", NamedTextColor.GOLD));
        String lockdown = plugin.isConfigFailed() ? "yes - config failed to load"
                : plugin.isManualLockdown() ? "yes - turned on by an administrator" : "no";
        sender.sendMessage(line("locked down", lockdown));
        if (cfg == null) {
            sender.sendMessage(Component.text("config failed to load - see the startup log", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(line("gate enabled", Boolean.toString(cfg.enabled())));
        sender.sendMessage(line("webhook host", String.valueOf(cfg.webhookEndpoint().getHost())));
        sender.sendMessage(line("awaiting verification", Integer.toString(plugin.limbo().heldCount())));
        sender.sendMessage(line("pending codes", Integer.toString(plugin.verification().pendingCount())));
        sender.sendMessage(line("trusted sessions", Integer.toString(plugin.trustedSessions().size())));
        sender.sendMessage(line("trust window", cfg.trustDuration().toHours() + "h"));
    }

    private void reload(CommandSender sender) {
        String error = plugin.reloadDiscordEasyLogin();
        if (error == null) {
            sender.sendMessage(Component.text("DiscordEasyLogin reloaded.", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Reload failed, server is now locked down: " + error, NamedTextColor.RED));
        }
    }

    private void showCode(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            // Printing a live code in chat would defeat the gate; the console is the escape hatch.
            // Checked as "is a console" rather than "is not a player": a command block is neither,
            // and with commandBlockOutput on it would broadcast the code to every op.
            sender.sendMessage(Component.text("This command can only be run from the server console.", NamedTextColor.RED));
            return;
        }
        if (args.length != 2) {
            sender.sendMessage(Component.text("Usage: /discordeasylogin code <player>", NamedTextColor.GRAY));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("No such player online.", NamedTextColor.RED));
            return;
        }
        VerificationManager.Pending pending = plugin.verification().peek(target.getUniqueId());
        if (pending == null) {
            sender.sendMessage(Component.text("No pending code for " + target.getName() + ".", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("Code for " + target.getName() + ": " + pending.code(), NamedTextColor.YELLOW));
    }

    private void trust(CommandSender sender, String[] args) {
        if (args.length != 3 || !args[1].equalsIgnoreCase("clear")) {
            sender.sendMessage(Component.text("Usage: /discordeasylogin trust clear <player|all>", NamedTextColor.GRAY));
            return;
        }
        if (args[2].equalsIgnoreCase("all")) {
            plugin.trustedSessions().revokeAll();
            persist(sender);
            sender.sendMessage(Component.text("Cleared all trusted sessions.", NamedTextColor.GREEN));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[2]);
        UUID uuid = target == null ? null : target.getUniqueId();
        if (uuid == null) {
            sender.sendMessage(Component.text("Unknown player: " + args[2], NamedTextColor.RED));
            return;
        }
        plugin.trustedSessions().revoke(uuid);
        persist(sender);
        sender.sendMessage(Component.text("Cleared trusted session for " + args[2] + ".", NamedTextColor.GREEN));
    }

    private void lockdown(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(Component.text("Usage: /discordeasylogin lockdown <on|off>", NamedTextColor.GRAY));
            return;
        }
        String state = args[1].toLowerCase(Locale.ROOT);
        if (!state.equals("on") && !state.equals("off")) {
            // Anything but an exact "off" used to switch the lockdown off; a typo must not open the server.
            sender.sendMessage(Component.text("Usage: /discordeasylogin lockdown <on|off>", NamedTextColor.GRAY));
            return;
        }
        boolean on = state.equals("on");
        plugin.setManualLockdown(on);
        if (on) {
            sender.sendMessage(Component.text("Lockdown enabled - all logins are denied.", NamedTextColor.RED));
        } else if (plugin.isConfigFailed()) {
            sender.sendMessage(Component.text("Manual lockdown cleared, but logins stay blocked: the config "
                    + "failed to load. Fix config.yml and run /discordeasylogin reload.", NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text("Lockdown disabled.", NamedTextColor.GREEN));
        }
    }

    private void persist(CommandSender sender) {
        try {
            plugin.trustedSessions().save();
        } catch (IOException e) {
            sender.sendMessage(Component.text("Warning: could not write sessions file: " + e.getMessage(),
                    NamedTextColor.RED));
        }
    }

    private Component line(String key, String value) {
        return Component.text("  " + key + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            return out;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "lockdown" -> List.of("on", "off");
            case "trust" -> args.length == 2 ? List.of("clear") : List.of("all");
            case "code" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
            default -> List.of();
        };
    }
}
