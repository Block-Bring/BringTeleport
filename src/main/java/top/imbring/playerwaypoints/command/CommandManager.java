package top.imbring.playerwaypoints.command;

import io.papermc.paper.command.brigadier.Commands;
import top.imbring.playerwaypoints.PlayerWaypointsPlugin;

public final class CommandManager {

    private CommandManager() {
    }

    public static void register(Commands commands, PlayerWaypointsPlugin plugin) {
        WaypointCommand.register(commands, plugin);
    }
}
