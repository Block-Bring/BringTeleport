package top.imbring.bringteleport.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import top.imbring.bringteleport.BringTeleportPlugin;
import top.imbring.bringteleport.model.Waypoint;
import top.imbring.bringteleport.model.Waypoint.WaypointType;
import top.imbring.bringteleport.service.TeleportHistory;
import top.imbring.bringteleport.service.WaypointManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

public final class WaypointCommand {

    private static final String TYPE_PUBLIC = "public";
    private static final String TYPE_PRIVATE = "private";

    private static final Map<UUID, PendingDeletion> PENDING_DELETIONS = new HashMap<>();
    private static final Map<UUID, CountdownContext> ACTIVE_COUNTDOWNS = new HashMap<>();

    private record PendingDeletion(String name, WaypointType type, long timestamp) {}
    private record CountdownContext(BukkitTask task, int startBlockX, int startBlockY, int startBlockZ) {}

    // ===== B2: Configuration cache =====
    private static class ConfigCache {
        // Cancel-on-move
        static boolean cancelOnMoveEnabled;
        static String cancelSoundName;
        static float cancelSoundVolume;
        static float cancelSoundPitch;
        static String cancelDisplayMode;

        // Countdown
        static boolean countdownEnabled;
        static double countdownDelay;
        static double countdownInterval;
        static String countdownDisplayMode;

        // Countdown sound
        static boolean countdownSoundEnabled;
        static String countdownSoundName;
        static int countdownSoundInterval;
        static float countdownSoundVolume;
        static float countdownSoundPitch;

        // Success display
        static String successDisplayMode;
        static boolean successSoundEnabled;
        static String successSoundName;
        static float successSoundVolume;
        static float successSoundPitch;

        // Delete confirmation
        static boolean deleteConfirmationEnabled;
        static double deleteConfirmationTimeout;

        private ConfigCache() {}

        static void refresh(BringTeleportPlugin plugin) {
            var config = plugin.getConfig();
            cancelOnMoveEnabled = config.getBoolean("waypoint.teleport.countdown.cancel-on-move.enabled", true);
            cancelSoundName = config.getString("waypoint.teleport.countdown.cancel-on-move.sound.name", "block.anvil.place");
            cancelSoundVolume = (float) config.getDouble("waypoint.teleport.countdown.cancel-on-move.sound.volume", 1.0);
            cancelSoundPitch = (float) config.getDouble("waypoint.teleport.countdown.cancel-on-move.sound.pitch", 1.0);
            cancelDisplayMode = config.getString("waypoint.teleport.countdown.cancel-on-move.display", "chat");

            countdownEnabled = config.getBoolean("waypoint.teleport.countdown.enabled", true);
            countdownDelay = config.getDouble("waypoint.teleport.countdown.delay", 3.0);
            countdownInterval = config.getDouble("waypoint.teleport.countdown.interval", 1.0);
            countdownDisplayMode = config.getString("waypoint.teleport.countdown.display", "subtitle");

            countdownSoundEnabled = config.getBoolean("waypoint.teleport.countdown.sound.enabled", true);
            countdownSoundName = config.getString("waypoint.teleport.countdown.sound.name", "block.note_block.pling");
            countdownSoundInterval = config.getInt("waypoint.teleport.countdown.sound.interval", 1);
            countdownSoundVolume = (float) config.getDouble("waypoint.teleport.countdown.sound.volume", 1.0);
            countdownSoundPitch = (float) config.getDouble("waypoint.teleport.countdown.sound.pitch", 1.0);

            successDisplayMode = config.getString("waypoint.teleport.success.display", "title");
            successSoundEnabled = config.getBoolean("waypoint.teleport.success.sound.enabled", true);
            successSoundName = config.getString("waypoint.teleport.success.sound.name", "entity.player.levelup");
            successSoundVolume = (float) config.getDouble("waypoint.teleport.success.sound.volume", 1.0);
            successSoundPitch = (float) config.getDouble("waypoint.teleport.success.sound.pitch", 1.0);

            deleteConfirmationEnabled = config.getBoolean("waypoint.delete-confirmation.enabled", true);
            deleteConfirmationTimeout = config.getDouble("waypoint.delete-confirmation.timeout", 10.0);
        }
    }

    // ===== A1: Tab completion helpers =====
    private static CompletableFuture<Suggestions> suggestPublicWaypoints(CommandSourceStack source, SuggestionsBuilder builder, WaypointManager mgr, boolean filterOwner) {
        var stream = mgr.getPublicWaypoints().stream();
        if (filterOwner && source.getSender() instanceof Player player) {
            stream = stream.filter(wp -> player.getUniqueId().equals(wp.getOwnerUuid()));
        }
        stream.map(Waypoint::getName)
            .filter(name -> name.startsWith(builder.getRemaining()))
            .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestPrivateWaypoints(CommandSourceStack source, SuggestionsBuilder builder, WaypointManager mgr) {
        if (source.getSender() instanceof Player player) {
            mgr.getPrivateWaypoints(player.getUniqueId()).stream()
                .map(Waypoint::getName)
                .filter(name -> name.startsWith(builder.getRemaining()))
                .forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    // ===== A2: Player & name resolution helpers =====
    private static Player resolvePlayer(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        CommandSourceStack source = ctx.getSource();
        if (source.getSender() instanceof Player player) {
            return player;
        }
        source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.player-only"));
        return null;
    }

    private static String resolveName(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            String name = ctx.getArgument("name", String.class).trim();
            if (name.isEmpty()) {
                ctx.getSource().getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.name-required"));
                return null;
            }
            return name;
        } catch (IllegalArgumentException e) {
            ctx.getSource().getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.name-required"));
            return null;
        }
    }

    // ===== C2: Cancel all active countdowns =====
    public static void cancelAllCountdowns() {
        ACTIVE_COUNTDOWNS.values().forEach(ctx -> ctx.task().cancel());
        ACTIVE_COUNTDOWNS.clear();
    }

    // ===== B2: Public cache refresh entry =====
    public static void refreshConfigCache(BringTeleportPlugin plugin) {
        ConfigCache.refresh(plugin);
    }

    private WaypointCommand() {
    }

    public static void register(Commands commands, BringTeleportPlugin plugin) {
        ConfigCache.refresh(plugin);
        var string = com.mojang.brigadier.arguments.StringArgumentType.greedyString();

        var waypointNode = literal("waypoint")
            .executes(ctx -> executeHelp(ctx, plugin))
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
                            CommandSourceStack source = ctx.getSource();
                            return suggestPublicWaypoints(source, builder, plugin.getWaypointManager(),
                                source.getSender() instanceof Player player && !player.hasPermission("bringteleport.waypoint.del.other"));
                        })
                        .executes(ctx -> executeDel(ctx, plugin, WaypointType.PUBLIC))))
                .then(literal(TYPE_PRIVATE)
                    .then(argument("name", string)
                        .suggests((ctx, builder) ->
                            suggestPrivateWaypoints(ctx.getSource(), builder, plugin.getWaypointManager()))
                        .executes(ctx -> executeDel(ctx, plugin, WaypointType.PRIVATE)))))
            .then(literal("confirm")
                .executes(ctx -> executeConfirm(ctx, plugin)))
            .then(literal("info")
                .then(literal(TYPE_PUBLIC)
                    .then(argument("name", string)
                        .suggests((ctx, builder) ->
                            suggestPublicWaypoints(ctx.getSource(), builder, plugin.getWaypointManager(), false))
                        .executes(ctx -> executeInfo(ctx, plugin, WaypointType.PUBLIC))))
                .then(literal(TYPE_PRIVATE)
                    .then(argument("name", string)
                        .suggests((ctx, builder) ->
                            suggestPrivateWaypoints(ctx.getSource(), builder, plugin.getWaypointManager()))
                        .executes(ctx -> executeInfo(ctx, plugin, WaypointType.PRIVATE)))))
            .then(literal("tp")
                .then(literal(TYPE_PUBLIC)
                    .then(argument("name", string)
                        .suggests((ctx, builder) ->
                            suggestPublicWaypoints(ctx.getSource(), builder, plugin.getWaypointManager(), false))
                        .executes(ctx -> executeTp(ctx, plugin, WaypointType.PUBLIC))))
                .then(literal(TYPE_PRIVATE)
                    .then(argument("name", string)
                        .suggests((ctx, builder) ->
                            suggestPrivateWaypoints(ctx.getSource(), builder, plugin.getWaypointManager()))
                        .executes(ctx -> executeTp(ctx, plugin, WaypointType.PRIVATE))))
                .then(literal("back")
                    .executes(ctx -> executeTpBack(ctx, plugin, 1))
                    .then(literal("undo")
                        .executes(ctx -> executeTpBackUndo(ctx, plugin)))
                    .then(argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> executeTpBack(ctx, plugin, IntegerArgumentType.getInteger(ctx, "index"))))))
            .build();

        // 玩家移动监听器：传送倒计时期间移动则取消
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerMove(PlayerMoveEvent event) {
                UUID uuid = event.getPlayer().getUniqueId();
                CountdownContext ctx = ACTIVE_COUNTDOWNS.get(uuid);
                if (ctx == null) return;

                var from = event.getFrom();
                var to = event.getTo();
                if (to == null) return;

                // 忽略纯视角转动（方块坐标未变）
                if (from.getBlockX() == to.getBlockX()
                    && from.getBlockY() == to.getBlockY()
                    && from.getBlockZ() == to.getBlockZ()) return;

                // 未真正离开起始方块则忽略
                if (to.getBlockX() == ctx.startBlockX
                    && to.getBlockY() == ctx.startBlockY
                    && to.getBlockZ() == ctx.startBlockZ) return;

                boolean cancelOnMove = ConfigCache.cancelOnMoveEnabled;
                if (!cancelOnMove) return;

                ACTIVE_COUNTDOWNS.remove(uuid);
                ctx.task.cancel();

                Player player = event.getPlayer();
                String cancelSoundName = ConfigCache.cancelSoundName;
                float cancelSoundVolume = ConfigCache.cancelSoundVolume;
                float cancelSoundPitch = ConfigCache.cancelSoundPitch;
                player.playSound(player.getLocation(), cancelSoundName, SoundCategory.MASTER, cancelSoundVolume, cancelSoundPitch);

                String displayMode = ConfigCache.cancelDisplayMode;
                displayCancelMessage(player, plugin, displayMode);
            }
        }, plugin);

        commands.register(waypointNode, "Manage waypoints", List.of("wp"));
    }

    private static int executeHelp(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            CommandSourceStack source = ctx.getSource();
            Component message = plugin.getLocaleManager().getMessage("waypoint.help", null);
            source.getSender().sendMessage(message);
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute waypoint help command", e);
        }
    }

    private static int executeCreate(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, WaypointType type) {
        try {
            Player player = resolvePlayer(ctx, plugin);
            if (player == null) return 1;

            if (!player.hasPermission("bringteleport.waypoint.create")) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            String name = resolveName(ctx, plugin);
            if (name == null) return 1;

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
            return handleError(plugin, ctx, "Failed to execute waypoint add command", e);
        }
    }

    private static int executeDel(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, WaypointType type) {
        try {
            Player player = resolvePlayer(ctx, plugin);
            if (player == null) return 1;

            if (!player.hasPermission("bringteleport.waypoint.del")) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            String name = resolveName(ctx, plugin);
            if (name == null) return 1;

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

                if (!isOwner && !player.hasPermission("bringteleport.waypoint.del.other")) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.not-owner", null));
                    return 0;
                }
            }

            // If delete-confirmation is enabled, set pending instead of deleting
            if (ConfigCache.deleteConfirmationEnabled) {
                double timeoutSec = ConfigCache.deleteConfirmationTimeout;
                // C1: Clean up expired pending deletions for this player
                PENDING_DELETIONS.entrySet().removeIf(entry ->
                    entry.getKey().equals(player.getUniqueId())
                        && System.currentTimeMillis() - entry.getValue().timestamp() > (long) (timeoutSec * 1000));
                PENDING_DELETIONS.put(player.getUniqueId(), new PendingDeletion(name, type, System.currentTimeMillis()));
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.confirm-required",
                    Map.of("name", name, "timeout", String.valueOf(timeoutSec))));
                return 1;
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
            return handleError(plugin, ctx, "Failed to execute waypoint del command", e);
        }
    }

    private static int executeConfirm(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.player-only"));
                return 1;
            }

            if (!player.hasPermission("bringteleport.waypoint.del")) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            PendingDeletion pending = PENDING_DELETIONS.remove(player.getUniqueId());
            if (pending == null) {
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.confirm-none", null));
                return 1;
            }

            double timeoutSec = ConfigCache.deleteConfirmationTimeout;
            long timeoutMs = (long) (timeoutSec * 1000);

            if (System.currentTimeMillis() - pending.timestamp > timeoutMs) {
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.confirm-expired",
                    Map.of("timeout", String.valueOf(timeoutSec))));
                return 1;
            }

            WaypointManager manager = plugin.getWaypointManager();
            UUID ownerUuid = (pending.type == WaypointType.PRIVATE) ? player.getUniqueId() : null;

            if (!manager.deleteWaypoint(pending.name, pending.type, ownerUuid)) {
                String typeLabel = pending.type == WaypointType.PUBLIC ? "public" : "private";
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.not-found",
                    Map.of("name", pending.name, "type", typeLabel)));
                return 1;
            }

            player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.confirm-success",
                Map.of("name", pending.name)));
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute waypoint confirm command", e);
        }
    }

    private static int executeInfo(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, WaypointType type) {
        try {
            Player player = resolvePlayer(ctx, plugin);
            if (player == null) return 1;

            if (!player.hasPermission("bringteleport.waypoint.info")) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            String name = resolveName(ctx, plugin);
            if (name == null) return 1;

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
            return handleError(plugin, ctx, "Failed to execute waypoint info command", e);
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
            Player player = resolvePlayer(ctx, plugin);
            if (player == null) return 1;

            if (!player.hasPermission("bringteleport.waypoint.tp")) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            String name = resolveName(ctx, plugin);
            if (name == null) return 1;

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

            boolean countdownEnabled = ConfigCache.countdownEnabled;
            double delaySec = ConfigCache.countdownDelay;
            if (countdownEnabled && delaySec > 0) {
                double intervalSec = ConfigCache.countdownInterval;
                final double tickInterval = intervalSec > 0 ? intervalSec : 1.0;
                String displayMode = ConfigCache.countdownDisplayMode;
                Location playerLoc = player.getLocation();
                Location targetLoc = location;
                String wpName = name;

                CountdownContext existing = ACTIVE_COUNTDOWNS.remove(player.getUniqueId());
                if (existing != null) existing.task().cancel();

                int totalSteps = (int) Math.ceil(delaySec / tickInterval);
                long intervalTicks = Math.max(1, (long) (tickInterval * 20));

                boolean soundEnabled = ConfigCache.countdownSoundEnabled;
                String soundName = ConfigCache.countdownSoundName;
                int soundInterval = ConfigCache.countdownSoundInterval;
                float soundVolume = ConfigCache.countdownSoundVolume;
                float soundPitch = ConfigCache.countdownSoundPitch;

                int startBlockX = playerLoc.getBlockX();
                int startBlockY = playerLoc.getBlockY();
                int startBlockZ = playerLoc.getBlockZ();

                BukkitTask task = new BukkitRunnable() {
                    int step = 0;

                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            ACTIVE_COUNTDOWNS.remove(player.getUniqueId());
                            cancel();
                            return;
                        }

                        if (step >= totalSteps) {
                            ACTIVE_COUNTDOWNS.remove(player.getUniqueId());
                            plugin.getTeleportHistory().record(player, playerLoc);
                            player.teleportAsync(targetLoc).thenAccept(success ->
                                sendTeleportSuccess(player, plugin, wpName));
                            cancel();
                            return;
                        }

                        double remain = delaySec - (step * tickInterval);
                        if (remain < 0) remain = 0;
                        String secStr;
                        if (tickInterval >= 1.0 || Math.abs(remain - Math.round(remain)) < 0.01) {
                            secStr = String.valueOf((int) Math.round(remain));
                        } else {
                            int decimals = tickInterval < 0.1 ? 2 : 1;
                            secStr = String.format("%." + decimals + "f", remain);
                        }

                        // 播放倒计时提示音
                        if (soundEnabled && soundInterval > 0 && step % soundInterval == 0) {
                            player.playSound(player.getLocation(), soundName, SoundCategory.MASTER, soundVolume, soundPitch);
                        }

                        String titleRaw = plugin.getLocaleManager().getRaw("waypoint.tp.countdown.title")
                            .replace("{seconds}", secStr);
                        String subtitleRaw = plugin.getLocaleManager().getRaw("waypoint.tp.countdown.subtitle")
                            .replace("{seconds}", secStr);
                        Component cTitle = MiniMessage.miniMessage().deserialize(titleRaw);
                        Component cSubtitle = MiniMessage.miniMessage().deserialize(subtitleRaw);

                        String chatRaw = plugin.getLocaleManager().getRaw("waypoint.tp.countdown.chat")
                            .replace("{seconds}", secStr);

                        sendDisplayMessage(player, cTitle, cSubtitle, MiniMessage.miniMessage().deserialize(chatRaw), displayMode);

                        step++;
                    }
                }.runTaskTimer(plugin, 0L, intervalTicks);
                ACTIVE_COUNTDOWNS.put(player.getUniqueId(), new CountdownContext(task, startBlockX, startBlockY, startBlockZ));

                return 1;
            }

            plugin.getTeleportHistory().record(player, player.getLocation());
            player.teleportAsync(location).thenAccept(success ->
                sendTeleportSuccess(player, plugin, name));

            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute waypoint tp command", e);
        }
    }

    private static int executeTpBack(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, int steps) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.player-only"));
                return 1;
            }

            if (!player.hasPermission("bringteleport.waypoint.tp")) {
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
            return handleError(plugin, ctx, "Failed to execute waypoint tp back command", e);
        }
    }

    private static int executeTpBackUndo(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.player-only"));
                return 1;
            }

            if (!player.hasPermission("bringteleport.waypoint.tp")) {
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
            return handleError(plugin, ctx, "Failed to execute waypoint tp back undo command", e);
        }
    }

    private static void sendTeleportSuccess(Player player, BringTeleportPlugin plugin, String waypointName) {
        String displayMode = ConfigCache.successDisplayMode;

        boolean soundEnabled = ConfigCache.successSoundEnabled;
        if (soundEnabled) {
            String soundName = ConfigCache.successSoundName;
            float volume = ConfigCache.successSoundVolume;
            float pitch = ConfigCache.successSoundPitch;
            player.playSound(player.getLocation(), soundName, SoundCategory.MASTER, volume, pitch);
        }

        Component titleComp = MiniMessage.miniMessage().deserialize(
            plugin.getLocaleManager().getRaw("waypoint.tp.success.title").replace("{name}", waypointName));
        Component subtitleComp = MiniMessage.miniMessage().deserialize(
            plugin.getLocaleManager().getRaw("waypoint.tp.success.subtitle").replace("{name}", waypointName));
        Component chatComp = plugin.getLocaleManager().getMessage("waypoint.tp.success.chat",
            Map.of("name", waypointName));
        sendDisplayMessage(player, titleComp, subtitleComp, chatComp, displayMode);
    }

    private static void displayCancelMessage(Player player, BringTeleportPlugin plugin, String displayMode) {
        String text = plugin.getLocaleManager().getRaw("waypoint.tp.countdown.cancelled");
        Component comp = MiniMessage.miniMessage().deserialize(text);
        Component chatComp = plugin.getLocaleManager().getMessage("waypoint.tp.countdown.cancelled", null);
        sendDisplayMessage(player, comp, comp, chatComp, displayMode);
    }

    private static Component getLocaleMessage(BringTeleportPlugin plugin, String path) {
        return plugin.getLocaleManager().getMessage(path, null);
    }

    private static String getTypeLabel(BringTeleportPlugin plugin, WaypointType type) {
        String key = type == WaypointType.PUBLIC ? "waypoint.info.type.public" : "waypoint.info.type.private";
        return plugin.getLocaleManager().getRaw(key);
    }

    // ===== A3: Unified display message =====
    private static void sendDisplayMessage(Player player, Component titleComponent, Component subtitleComponent, Component chatComponent, String displayMode) {
        var times = Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(250));
        switch (displayMode) {
            case "title" -> player.showTitle(Title.title(titleComponent, Component.empty(), times));
            case "subtitle" -> player.showTitle(Title.title(Component.empty(), subtitleComponent, times));
            case "both" -> player.showTitle(Title.title(titleComponent, subtitleComponent, times));
            default -> player.sendMessage(chatComponent);
        }
    }

    // ===== A4: Unified error handler =====
    static int handleError(BringTeleportPlugin plugin, CommandContext<CommandSourceStack> ctx, String errorMsg, Exception e) {
        plugin.getLogger().log(Level.SEVERE, errorMsg, e);
        ctx.getSource().getSender().sendMessage(Component.text("An internal error occurred. Please try again."));
        return 0;
    }
}
