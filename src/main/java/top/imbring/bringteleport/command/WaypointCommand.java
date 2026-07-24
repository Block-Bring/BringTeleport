package top.imbring.bringteleport.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import top.imbring.bringteleport.BringTeleportPlugin;
import top.imbring.bringteleport.model.Waypoint;
import top.imbring.bringteleport.model.Waypoint.WaypointType;
import top.imbring.bringteleport.service.TeleportHistory;
import top.imbring.bringteleport.service.WaypointManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

public final class WaypointCommand {

    private static final String TYPE_PUBLIC = "public";
    private static final String TYPE_PRIVATE = "private";

    private WaypointCommand() {
    }

    public static void register(Commands commands, BringTeleportPlugin plugin) {
        var string = com.mojang.brigadier.arguments.StringArgumentType.greedyString();

        var waypointNode = literal("waypoint")
            .executes(ctx -> executeHelp(ctx, plugin))
            .then(literal("reload")
                .executes(ctx -> executeReload(ctx, plugin)))
            .then(literal("help")
                .executes(ctx -> executeHelp(ctx, plugin)))
            .then(literal("create")
                .then(literal(TYPE_PUBLIC)
                    .then(argument("name", string)
                        .executes(ctx -> executeCreate(ctx, plugin, WaypointType.PUBLIC))))
                .then(literal(TYPE_PRIVATE)
                    .then(argument("name", string)
                        .executes(ctx -> executeCreate(ctx, plugin, WaypointType.PRIVATE)))))
            .then(literal("delete")
                .then(literal(TYPE_PUBLIC)
                    .then(argument("name", string)
                        .suggests((ctx, builder) -> {
                            WaypointManager mgr = plugin.getWaypointManager();
                            if (mgr != null) {
                                var stream = mgr.getPublicWaypoints().stream();
                                // Regular players without del.other can only tab-complete
                                // their own public waypoints
                                CommandSourceStack source = ctx.getSource();
                                if (source.getSender() instanceof Player player
                                    && !player.hasPermission("bringteleport.del.other")) {
                                    stream = stream.filter(
                                        wp -> player.getUniqueId().equals(wp.getOwnerUuid()));
                                }
                                stream.map(Waypoint::getName)
                                    .filter(name -> name.startsWith(builder.getRemaining()))
                                    .forEach(builder::suggest);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeDel(ctx, plugin, WaypointType.PUBLIC))))
                .then(literal(TYPE_PRIVATE)
                    .then(argument("name", string)
                        .suggests((ctx, builder) -> {
                            CommandSourceStack source = ctx.getSource();
                            if (source.getSender() instanceof Player player) {
                                WaypointManager mgr = plugin.getWaypointManager();
                                if (mgr != null) {
                                    mgr.getPrivateWaypoints(player.getUniqueId()).stream()
                                        .map(Waypoint::getName)
                                        .filter(name -> name.startsWith(builder.getRemaining()))
                                        .forEach(builder::suggest);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeDel(ctx, plugin, WaypointType.PRIVATE)))))
            .then(literal("info")
                .then(literal(TYPE_PUBLIC)
                    .then(argument("name", string)
                        .suggests((ctx, builder) -> {
                            WaypointManager mgr = plugin.getWaypointManager();
                            if (mgr != null) {
                                mgr.getPublicWaypoints().stream()
                                    .map(Waypoint::getName)
                                    .filter(name -> name.startsWith(builder.getRemaining()))
                                    .forEach(builder::suggest);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeInfo(ctx, plugin, WaypointType.PUBLIC))))
                .then(literal(TYPE_PRIVATE)
                    .then(argument("name", string)
                        .suggests((ctx, builder) -> {
                            CommandSourceStack source = ctx.getSource();
                            if (source.getSender() instanceof Player player) {
                                WaypointManager mgr = plugin.getWaypointManager();
                                if (mgr != null) {
                                    mgr.getPrivateWaypoints(player.getUniqueId()).stream()
                                        .map(Waypoint::getName)
                                        .filter(name -> name.startsWith(builder.getRemaining()))
                                        .forEach(builder::suggest);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeInfo(ctx, plugin, WaypointType.PRIVATE)))))
            .then(literal("tp")
                .then(literal(TYPE_PUBLIC)
                    .then(argument("name", string)
                        .suggests((ctx, builder) -> {
                            WaypointManager mgr = plugin.getWaypointManager();
                            if (mgr != null) {
                                mgr.getPublicWaypoints().stream()
                                    .map(Waypoint::getName)
                                    .filter(name -> name.startsWith(builder.getRemaining()))
                                    .forEach(builder::suggest);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeTp(ctx, plugin, WaypointType.PUBLIC))))
                .then(literal(TYPE_PRIVATE)
                    .then(argument("name", string)
                        .suggests((ctx, builder) -> {
                            CommandSourceStack source = ctx.getSource();
                            if (source.getSender() instanceof Player player) {
                                WaypointManager mgr = plugin.getWaypointManager();
                                if (mgr != null) {
                                    mgr.getPrivateWaypoints(player.getUniqueId()).stream()
                                        .map(Waypoint::getName)
                                        .filter(name -> name.startsWith(builder.getRemaining()))
                                        .forEach(builder::suggest);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> executeTp(ctx, plugin, WaypointType.PRIVATE))))
                .then(literal("back")
                    .executes(ctx -> executeTpBack(ctx, plugin, 1))
                    .then(literal("undo")
                        .executes(ctx -> executeTpBackUndo(ctx, plugin)))
                    .then(argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> executeTpBack(ctx, plugin, IntegerArgumentType.getInteger(ctx, "index"))))))
            .build();

        commands.register(waypointNode, "Manage waypoints", List.of("wp"));
    }

    private static int executeReload(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!source.getSender().hasPermission("bringteleport.reload")) {
                source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }
            plugin.reload();
            source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.reload.success"));
            return 1;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to reload plugin", e);
            ctx.getSource().getSender().sendMessage(Component.text("An internal error occurred. Please try again."));
            return 0;
        }
    }

    private static int executeHelp(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            CommandSourceStack source = ctx.getSource();
            Component message = plugin.getLocaleManager().getMessage("waypoint.help", null);
            source.getSender().sendMessage(message);
            return 1;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to execute waypoint help command", e);
            ctx.getSource().getSender().sendMessage(Component.text("An internal error occurred. Please try again."));
            return 0;
        }
    }

    private static int executeCreate(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, WaypointType type) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.player-only"));
                return 1;
            }

            if (!player.hasPermission("bringteleport.create")) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            String name;
            try {
                name = ctx.getArgument("name", String.class).trim();
            } catch (IllegalArgumentException e) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.name-required"));
                return 1;
            }

            if (name.isEmpty()) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.name-required"));
                return 1;
            }

            if (name.length() > 32) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.name-too-long"));
                return 1;
            }

            WaypointManager manager = plugin.getWaypointManager();
            UUID ownerUuid = player.getUniqueId();

            // Check for duplicates manually for better error messages
            if (type == WaypointType.PUBLIC) {
                Optional<Waypoint> existing = manager.getWaypoint(name, WaypointType.PUBLIC, null);
                if (existing.isPresent()) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.create.duplicate-public",
                        Map.of("name", name)));
                    return 1;
                }
            } else {
                Optional<Waypoint> existing = manager.getWaypoint(name, WaypointType.PRIVATE, player.getUniqueId());
                if (existing.isPresent()) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.create.duplicate-private",
                        Map.of("name", name)));
                    return 1;
                }
            }

            Location location = player.getLocation();
            Waypoint waypoint = Waypoint.fromLocation(name, location, type, ownerUuid);

            if (manager.addWaypoint(waypoint)) {
                String typeLabel = type == WaypointType.PUBLIC ? "public" : "private";
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.create.success",
                    Map.of("name", name, "type", typeLabel)));
            } else {
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.create.fail",
                    Map.of("name", name)));
            }

            return 1;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to execute waypoint add command", e);
            ctx.getSource().getSender().sendMessage(Component.text("An internal error occurred. Please try again."));
            return 0;
        }
    }

    private static int executeDel(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, WaypointType type) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.player-only"));
                return 1;
            }

            if (!player.hasPermission("bringteleport.del")) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            String name;
            try {
                name = ctx.getArgument("name", String.class).trim();
            } catch (IllegalArgumentException e) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.name-required"));
                return 1;
            }

            if (name.isEmpty()) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.name-required"));
                return 1;
            }

            WaypointManager manager = plugin.getWaypointManager();
            UUID ownerUuid = (type == WaypointType.PRIVATE) ? player.getUniqueId() : null;

            // For public waypoints, check ownership
            if (type == WaypointType.PUBLIC) {
                Optional<Waypoint> existing = manager.getWaypoint(name, WaypointType.PUBLIC, null);
                if (existing.isEmpty()) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.not-found",
                        Map.of("name", name, "type", getTypeLabel(plugin, WaypointType.PUBLIC))));
                    return 1;
                }

                boolean isOwner = existing.get().getOwnerUuid() != null
                    && existing.get().getOwnerUuid().equals(player.getUniqueId());

                if (!isOwner && !player.hasPermission("bringteleport.del.other")) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.not-owner", null));
                    return 0;
                }
            }

            if (!manager.deleteWaypoint(name, type, ownerUuid)) {
                String typeLabel = type == WaypointType.PUBLIC ? "public" : "private";
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.not-found",
                    Map.of("name", name, "type", typeLabel)));
                return 1;
            }

            String typeLabel = type == WaypointType.PUBLIC ? "public" : "private";
            player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.success",
                Map.of("name", name, "type", typeLabel)));
            return 1;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to execute waypoint del command", e);
            ctx.getSource().getSender().sendMessage(Component.text("An internal error occurred. Please try again."));
            return 0;
        }
    }

    private static int executeInfo(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, WaypointType type) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.player-only"));
                return 1;
            }

            if (!player.hasPermission("bringteleport.info")) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            String name;
            try {
                name = ctx.getArgument("name", String.class).trim();
            } catch (IllegalArgumentException e) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.name-required"));
                return 1;
            }

            if (name.isEmpty()) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.name-required"));
                return 1;
            }

            WaypointManager manager = plugin.getWaypointManager();
            UUID ownerUuid = (type == WaypointType.PRIVATE) ? player.getUniqueId() : null;

            Optional<Waypoint> opt = manager.getWaypoint(name, type, ownerUuid);
            if (opt.isEmpty()) {
                var typeLabel = getTypeLabel(plugin, type);
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.info.not-found",
                    Map.of("name", name, "type", typeLabel)));
                return 1;
            }

            Waypoint waypoint = opt.get();

            // Resolve creator name
            String creatorName = "???";
            UUID creatorUuid = waypoint.getOwnerUuid();
            if (creatorUuid != null) {
                var offlinePlayer = Bukkit.getOfflinePlayer(creatorUuid);
                if (offlinePlayer.getName() != null) {
                    creatorName = offlinePlayer.getName();
                } else {
                    creatorName = creatorUuid.toString().substring(0, 8);
                }
            } else {
                creatorName = "---";
            }

            // Format creation time
            String formattedDate = formatDateTime(waypoint.getCreatedAt());

            // Build info message
            var typeLabel = getTypeLabel(plugin, type);
            String info = plugin.getLocaleManager().getRaw("waypoint.info.template")
                .replace("{name}", waypoint.getName())
                .replace("{type}", typeLabel)
                .replace("{creator}", creatorName)
                .replace("{world}", waypoint.getWorld())
                .replace("{x}", String.format("%.0f", waypoint.getX()))
                .replace("{y}", String.format("%.0f", waypoint.getY()))
                .replace("{z}", String.format("%.0f", waypoint.getZ()))
                .replace("{date}", formattedDate);

            player.sendMessage(MiniMessage.miniMessage().deserialize(info));
            return 1;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to execute waypoint info command", e);
            ctx.getSource().getSender().sendMessage(Component.text("An internal error occurred. Please try again."));
            return 0;
        }
    }

    private static String formatDateTime(String dbTimestamp) {
        if (dbTimestamp == null || dbTimestamp.isEmpty()) return "---";
        try {
            LocalDateTime dt = LocalDateTime.parse(dbTimestamp,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return dt.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"));
        } catch (Exception e) {
            return dbTimestamp;
        }
    }

    private static int executeTp(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, WaypointType type) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.player-only"));
                return 1;
            }

            if (!player.hasPermission("bringteleport.tp")) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            String name;
            try {
                name = ctx.getArgument("name", String.class).trim();
            } catch (IllegalArgumentException e) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.name-required"));
                return 1;
            }

            if (name.isEmpty()) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.name-required"));
                return 1;
            }

            WaypointManager manager = plugin.getWaypointManager();
            UUID ownerUuid = (type == WaypointType.PRIVATE) ? player.getUniqueId() : null;

            Optional<Waypoint> opt = manager.getWaypoint(name, type, ownerUuid);
            if (opt.isEmpty()) {
                String typeLabel = type == WaypointType.PUBLIC ? "public" : "private";
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.tp.not-found",
                    Map.of("name", name, "type", typeLabel)));
                return 1;
            }

            Waypoint waypoint = opt.get();
            Location location = manager.toLocation(waypoint);
            if (location == null) {
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.tp.world-not-loaded",
                    Map.of("world", waypoint.getWorld())));
                return 1;
            }

            plugin.getTeleportHistory().record(player, player.getLocation());
            player.teleportAsync(location);
            player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.tp.success",
                Map.of("name", name)));

            return 1;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to execute waypoint tp command", e);
            ctx.getSource().getSender().sendMessage(Component.text("An internal error occurred. Please try again."));
            return 0;
        }
    }

    private static int executeTpBack(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, int steps) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.player-only"));
                return 1;
            }

            if (!player.hasPermission("bringteleport.tp")) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            TeleportHistory history = plugin.getTeleportHistory();
            int available = history.getHistorySize(player);
            if (available == 0) {
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.tp.back.no-history", null));
                return 1;
            }
            if (steps > available) {
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.tp.back.steps-exceed",
                    Map.of("steps", String.valueOf(steps), "available", String.valueOf(available))));
                return 1;
            }
            Location target = history.getBackLocation(player, steps);

            // Save current position for undo (don't record this teleport in history)
            history.setLastBackSource(player, player.getLocation());

            player.teleportAsync(target);
            player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.tp.back.success",
                Map.of("steps", String.valueOf(steps))));
            return 1;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to execute waypoint tp back command", e);
            ctx.getSource().getSender().sendMessage(Component.text("An internal error occurred. Please try again."));
            return 0;
        }
    }

    private static int executeTpBackUndo(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.player-only"));
                return 1;
            }

            if (!player.hasPermission("bringteleport.tp")) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            TeleportHistory history = plugin.getTeleportHistory();
            Location target = history.getAndClearLastBackSource(player);
            if (target == null) {
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.tp.back.undo-none", null));
                return 1;
            }

            player.teleportAsync(target);
            player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.tp.back.undo-success", null));
            return 1;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to execute waypoint tp back undo command", e);
            ctx.getSource().getSender().sendMessage(Component.text("An internal error occurred. Please try again."));
            return 0;
        }
    }

    private static Component getLocaleMessage(BringTeleportPlugin plugin, String path) {
        return plugin.getLocaleManager().getMessage(path, null);
    }

    private static String getTypeLabel(BringTeleportPlugin plugin, WaypointType type) {
        String key = type == WaypointType.PUBLIC ? "waypoint.type.public" : "waypoint.type.private";
        return plugin.getLocaleManager().getRaw(key);
    }
}
