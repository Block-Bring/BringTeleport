package top.imbring.bringteleport.command;

import io.papermc.paper.command.brigadier.Commands;
import top.imbring.bringteleport.BringTeleportPlugin;

public final class CommandManager {

    private CommandManager() {
    }

    public static void register(Commands commands, BringTeleportPlugin plugin) {
        WaypointCommand.register(commands, plugin);
    }
}
