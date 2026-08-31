package top.imbring.bringteleport.command;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import top.imbring.bringteleport.BringTeleportPlugin;

import static io.papermc.paper.command.brigadier.Commands.literal;

public final class BringTeleportCommand {

    private BringTeleportCommand() {
    }

    public static void register(Commands commands, BringTeleportPlugin plugin) {
        var node = literal("bringteleport")
            .executes(ctx -> executeHelp(ctx, plugin))
            .then(literal("reload")
                .executes(ctx -> executeReload(ctx, plugin)))
            .then(literal("help")
                .executes(ctx -> executeHelp(ctx, plugin)))
            .then(literal("update")
                .executes(ctx -> executeUpdate(ctx, plugin)))
            .build();

        commands.register(node, "BringTeleport plugin management commands");
    }

    private static int executeReload(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!source.getSender().hasPermission("bringteleport.reload")) {
                source.getSender().sendMessage(
                    plugin.getLocaleManager().getMessage("warp.error.no-permission", null));
                return 0;
            }
            plugin.reload();
            source.getSender().sendMessage(
                plugin.getLocaleManager().getMessage("bringteleport.reload.success", null));
            return 1;
        } catch (Exception e) {
            return WarpCommand.handleError(plugin, ctx, "Failed to reload plugin", e);
        }
    }

    private static int executeUpdate(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!source.getSender().hasPermission("bringteleport.update")) {
                source.getSender().sendMessage(
                    plugin.getLocaleManager().getMessage("warp.error.no-permission", null));
                return 0;
            }
            plugin.getUpdateChecker().checkFor(source.getSender());
            return 1;
        } catch (Exception e) {
            return WarpCommand.handleError(plugin, ctx, "Failed to check for updates", e);
        }
    }

    private static int executeHelp(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            CommandSourceStack source = ctx.getSource();
            Component message = plugin.getLocaleManager().getMessage("bringteleport.help", null);
            source.getSender().sendMessage(message);
            return 1;
        } catch (Exception e) {
            return WarpCommand.handleError(plugin, ctx, "Failed to execute bringteleport help command", e);
        }
    }
}
