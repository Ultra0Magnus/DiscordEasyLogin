package io.github.ultra0magnus.discordeasylogin;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@code /verify <code>} - the only command an unverified player may run. */
public final class VerifyCommand implements BasicCommand {

    private final DiscordEasyLoginPlugin plugin;
    private final GateService gate;

    public VerifyCommand(DiscordEasyLoginPlugin plugin, GateService gate) {
        this.plugin = plugin;
        this.gate = gate;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(Component.text("Only players can verify.", NamedTextColor.RED));
            return;
        }
        if (args.length != 1) {
            player.sendMessage(Text.render(plugin.config().messages().usage()));
            return;
        }
        gate.submit(player, args[0]);
    }

    @Override
    public @NotNull java.util.Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        return List.of();
    }
}
