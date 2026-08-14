package top.imbring.bringteleport.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

public final class WaypointCommand {

    private static final String TYPE_PUBLIC = "public";
    private static final String TYPE_PRIVATE = "private";

    private static final Map<UUID, PendingDeletion> PENDING_DELETIONS = new HashMap<>();
    private static final Map<UUID, CountdownContext> ACTIVE_COUNTDOWNS = new HashMap<>();
    // 聊天监听器在异步线程读取，需线程安全
    private static final Map<UUID, PendingRename> PENDING_RENAMES = new ConcurrentHashMap<>();
    private static final long RENAME_TIMEOUT_MS = 30_000;

    private record PendingDeletion(String name, WaypointType type, long timestamp) {}
    private record CountdownContext(BukkitTask task, int startBlockX, int startBlockY, int startBlockZ) {}
    // ownerName 为 null 表示操作者自己的私有路径点（仅玩家场景）
    private record PrivateTarget(String ownerName, String name) {}
    private record PendingRename(String name, WaypointType type, long timestamp) {}

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

        // Display timezone (database stores UTC)
        static ZoneId displayZone;

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
            countdownDisplayMode = config.getString("waypoint.teleport.countdown.display", "both");

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

            String tz = config.getString("timezone", "+8");
            if (tz == null || tz.isBlank() || tz.equalsIgnoreCase("system")) {
                displayZone = ZoneId.systemDefault();
            } else {
                displayZone = parseDisplayZone(tz, plugin);
            }
        }

        // 支持 IANA 时区名（Asia/Shanghai）或与 UTC 的偏移（+8、-5、+08:30）
        private static ZoneId parseDisplayZone(String tz, BringTeleportPlugin plugin) {
            try {
                return ZoneId.of(tz);
            } catch (Exception ignored) {
                try {
                    return parseOffset(tz);
                } catch (Exception e) {
                    plugin.getLogger().warning("无效的 timezone 配置: " + tz + "，已使用服务器系统时区");
                    return ZoneId.systemDefault();
                }
            }
        }

        private static ZoneId parseOffset(String tz) {
            String s = tz.trim();
            if (!s.matches("[+-]?\\d{1,2}(:\\d{2})?")) {
                throw new IllegalArgumentException("invalid offset: " + tz);
            }
            int sign = s.startsWith("-") ? -1 : 1;
            String rest = s.startsWith("+") || s.startsWith("-") ? s.substring(1) : s;
            String[] parts = rest.split(":");
            int hours = Integer.parseInt(parts[0]);
            int minutes = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes);
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
            return builder.buildFuture();
        }

        // 控制台：第一个参数建议玩家名，之后建议该玩家的私有路径点
        String typed = builder.getRemaining();
        if (!typed.contains(" ")) {
            Set<String> names = new HashSet<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            for (UUID uuid : mgr.getPrivateWaypointOwners()) {
                String name = Bukkit.getOfflinePlayer(uuid).getName();
                if (name != null) names.add(name);
            }
            return suggestTokens(builder, List.copyOf(names));
        }
        String ownerName = typed.trim().split("\\s+")[0];
        return suggestTokens(builder,
            mgr.getPrivateWaypoints(resolvePlayerByName(ownerName).getUniqueId()).stream()
                .map(Waypoint::getName)
                .toList());
    }

    // 建议候选列表，替换范围仅覆盖当前正在输入的最后一个 token，避免覆盖已输入部分
    private static CompletableFuture<Suggestions> suggestTokens(SuggestionsBuilder builder, List<String> candidates) {
        String typed = builder.getRemaining();
        String prefix = typed.substring(typed.lastIndexOf(' ') + 1);
        StringRange range = StringRange.between(builder.getStart() + typed.lastIndexOf(' ') + 1, builder.getInput().length());
        List<Suggestion> suggestions = candidates.stream()
            .filter(name -> name.startsWith(prefix))
            .map(name -> new Suggestion(range, name))
            .toList();
        if (suggestions.isEmpty()) {
            return Suggestions.empty();
        }
        return CompletableFuture.completedFuture(new Suggestions(range, suggestions));
    }

    // rename 公有路径点的补全：玩家仅建议自己创建的（或持 del.other 权限则全部），控制台建议全部公有路径点
    private static CompletableFuture<Suggestions> suggestRenamePublic(CommandSourceStack source, SuggestionsBuilder builder, WaypointManager mgr) {
        if (source.getSender() instanceof Player player) {
            return suggestPublicWaypoints(source, builder, mgr,
                !player.hasPermission("bringteleport.waypoint.del.other"));
        }
        return suggestTokens(builder,
            mgr.getPublicWaypoints().stream().map(Waypoint::getName).toList());
    }

    // tpwarp 补全：全部公有路径点 + 玩家自己的私有路径点（去重）
    private static CompletableFuture<Suggestions> suggestTpWarp(CommandSourceStack source, SuggestionsBuilder builder, WaypointManager mgr) {
        if (!(source.getSender() instanceof Player)) {
            return builder.buildFuture();
        }
        Set<String> names = new LinkedHashSet<>();
        mgr.getPublicWaypoints().stream().map(Waypoint::getName).forEach(names::add);
        mgr.getPrivateWaypoints(((Player) source.getSender()).getUniqueId()).stream()
            .map(Waypoint::getName).forEach(names::add);
        return suggestTokens(builder, List.copyOf(names));
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

    // 解析支持引号包裹的参数："a b" c → ["a b", "c"]
    private static List<String> parseQuotedTokens(String raw) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    // 私有路径点参数：玩家输入整个字符串即路径点名称；控制台需以 <玩家名> 开头指定所有者
    private static PrivateTarget resolvePrivateTarget(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        CommandSourceStack source = ctx.getSource();
        String raw = ctx.getArgument("name", String.class).trim();

        if (source.getSender() instanceof Player) {
            if (raw.isEmpty()) {
                source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.name-required"));
                return null;
            }
            return new PrivateTarget(null, raw);
        }

        List<String> tokens = parseQuotedTokens(raw);
        if (tokens.size() < 2) {
            source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.player-required"));
            return null;
        }
        String ownerName = tokens.get(0);
        String name = String.join(" ", tokens.subList(1, tokens.size()));
        return new PrivateTarget(ownerName, name);
    }

    // 解析玩家名：优先在线玩家（忽略大小写），其次离线缓存
    private static OfflinePlayer resolvePlayerByName(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) return online;
        }
        return Bukkit.getOfflinePlayer(name);
    }

    // 私有路径点所有者：指定了玩家名则解析为离线玩家 UUID，否则为操作者本人
    private static UUID resolveOwnerUuid(CommandSourceStack source, String ownerName, BringTeleportPlugin plugin) {
        if (ownerName != null) {
            OfflinePlayer owner = resolvePlayerByName(ownerName);
            if (!owner.isOnline() && owner.getName() == null && !owner.hasPlayedBefore()) {
                source.getSender().sendMessage(plugin.getLocaleManager().getMessage("waypoint.error.player-not-found",
                    Map.of("player", escape(ownerName))));
                return null;
            }
            return owner.getUniqueId();
        }
        if (source.getSender() instanceof Player player) {
            return player.getUniqueId();
        }
        source.getSender().sendMessage(getLocaleMessage(plugin, "waypoint.error.player-required"));
        return null;
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
            .then(literal("rename")
                .then(literal(TYPE_PUBLIC)
                    .then(argument("name", string)
                        .suggests((ctx, builder) ->
                            suggestRenamePublic(ctx.getSource(), builder, plugin.getWaypointManager()))
                        .executes(ctx -> executeRename(ctx, plugin, WaypointType.PUBLIC))))
                .then(literal(TYPE_PRIVATE)
                    .then(argument("name", string)
                        .suggests((ctx, builder) ->
                            suggestPrivateWaypoints(ctx.getSource(), builder, plugin.getWaypointManager()))
                        .executes(ctx -> executeRename(ctx, plugin, WaypointType.PRIVATE)))))
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

        // 重命名输入监听器：捕获待重命名玩家的聊天消息作为新名字
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onChat(AsyncChatEvent event) {
                Player player = event.getPlayer();
                PendingRename pending = PENDING_RENAMES.get(player.getUniqueId());
                if (pending == null) return;

                event.setCancelled(true);
                String newName = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
                Bukkit.getScheduler().runTask(plugin, () -> processRename(player, pending, newName, plugin));
            }
        }, plugin);

        commands.register(waypointNode, "Manage waypoints", List.of("wp"));

        // /tpwarp：直接传送到路径点，Tab 补全列出全部可传送路径点（公有 + 自己的私有）
        var tpWarpNode = literal("tpwarp")
            .then(argument("name", string)
                .suggests((ctx, builder) ->
                    suggestTpWarp(ctx.getSource(), builder, plugin.getWaypointManager()))
                .executes(ctx -> executeTpWarp(ctx, plugin)))
            .build();
        commands.register(tpWarpNode, "Teleport to a waypoint", List.of());
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
                        Map.of("name", escape(name))));
                    return 1;
                }
            } else {
                Optional<Waypoint> existing = manager.getWaypoint(name, WaypointType.PRIVATE, player.getUniqueId());
                if (existing.isPresent()) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.create.duplicate-private",
                        Map.of("name", escape(name))));
                    return 1;
                }
            }

            Location location = player.getLocation();
            Waypoint waypoint = Waypoint.fromLocation(name, location, type, ownerUuid);

            if (manager.addWaypoint(waypoint)) {
                String typeLabel = type == WaypointType.PUBLIC ? "public" : "private";
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.create.success",
                    Map.of("name", escape(name), "type", typeLabel)));

                // 创建公有路径点后向所有在线玩家广播分享消息（创建者已有成功提示，不再重复）
                if (type == WaypointType.PUBLIC) {
                    Map<String, String> sharePlaceholders = new HashMap<>(locationPlaceholders(waypoint, plugin));
                    sharePlaceholders.put("player", escape(player.getName()));
                    sharePlaceholders.put("name", escape(name));
                    Component shareMsg = plugin.getLocaleManager().getMessage("waypoint.create.shared", sharePlaceholders);
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (!online.getUniqueId().equals(player.getUniqueId())) {
                            online.sendMessage(shareMsg);
                        }
                    }
                }
            } else {
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.create.fail",
                    Map.of("name", escape(name))));
            }

            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute waypoint add command", e);
        }
    }

    private static int executeDel(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, WaypointType type) {
        try {
            CommandSourceStack source = ctx.getSource();
            CommandSender sender = source.getSender();
            if (!sender.hasPermission("bringteleport.waypoint.del")) {
                sender.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            String name;
            UUID ownerUuid = null;
            if (type == WaypointType.PRIVATE) {
                PrivateTarget target = resolvePrivateTarget(ctx, plugin);
                if (target == null) return 1;
                name = target.name();
                ownerUuid = resolveOwnerUuid(source, target.ownerName(), plugin);
                if (ownerUuid == null) return 1;
            } else {
                name = resolveName(ctx, plugin);
                if (name == null) return 1;
            }

            WaypointManager manager = plugin.getWaypointManager();

            // 公有路径点的所有者检查仅对玩家生效，控制台视为管理员
            if (type == WaypointType.PUBLIC && sender instanceof Player player) {
                Optional<Waypoint> existing = manager.getWaypoint(name, WaypointType.PUBLIC, null);
                if (existing.isEmpty()) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.not-found",
                        Map.of("name", escape(name), "type", getTypeLabel(plugin, WaypointType.PUBLIC))));
                    return 1;
                }

                boolean isOwner = existing.get().getOwnerUuid() != null
                    && existing.get().getOwnerUuid().equals(player.getUniqueId());

                if (!isOwner && !player.hasPermission("bringteleport.waypoint.del.other")) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.not-owner", null));
                    return 0;
                }
            }

            // 删除确认仅对玩家生效，控制台直接删除
            if (ConfigCache.deleteConfirmationEnabled && sender instanceof Player player) {
                double timeoutSec = ConfigCache.deleteConfirmationTimeout;
                // C1: Clean up expired pending deletions for this player
                PENDING_DELETIONS.entrySet().removeIf(entry ->
                    entry.getKey().equals(player.getUniqueId())
                        && System.currentTimeMillis() - entry.getValue().timestamp() > (long) (timeoutSec * 1000));
                PENDING_DELETIONS.put(player.getUniqueId(), new PendingDeletion(name, type, System.currentTimeMillis()));
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.confirm-required",
                    Map.of("name", escape(name), "timeout", String.valueOf(timeoutSec))));
                return 1;
            }

            if (!manager.deleteWaypoint(name, type, ownerUuid)) {
                String typeLabel = type == WaypointType.PUBLIC ? "public" : "private";
                sender.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.not-found",
                    Map.of("name", escape(name), "type", typeLabel)));
                return 1;
            }

            String typeLabel = type == WaypointType.PUBLIC ? "public" : "private";
            sender.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.success",
                Map.of("name", escape(name), "type", typeLabel)));
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
                    Map.of("name", escape(pending.name), "type", typeLabel)));
                return 1;
            }

            player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.delete.confirm-success",
                Map.of("name", escape(pending.name))));
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute waypoint confirm command", e);
        }
    }

    private static int executeRename(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, WaypointType type) {
        try {
            CommandSourceStack source = ctx.getSource();
            CommandSender sender = source.getSender();
            if (!sender.hasPermission("bringteleport.waypoint.rename")) {
                sender.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            if (sender instanceof Player player) {
                // 玩家：先指定旧路径点，随后在聊天框输入新名字
                String name = resolveName(ctx, plugin);
                if (name == null) return 1;

                WaypointManager manager = plugin.getWaypointManager();
                if (type == WaypointType.PUBLIC) {
                    Optional<Waypoint> existing = manager.getWaypoint(name, WaypointType.PUBLIC, null);
                    if (existing.isEmpty()) {
                        player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.rename.not-found",
                            Map.of("name", escape(name), "type", getTypeLabel(plugin, WaypointType.PUBLIC))));
                        return 1;
                    }

                    boolean isOwner = existing.get().getOwnerUuid() != null
                        && existing.get().getOwnerUuid().equals(player.getUniqueId());

                    if (!isOwner && !player.hasPermission("bringteleport.waypoint.del.other")) {
                        player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.rename.not-owner", null));
                        return 0;
                    }
                }

                // 清理该玩家过期的待重命名请求
                PENDING_RENAMES.entrySet().removeIf(entry ->
                    entry.getKey().equals(player.getUniqueId())
                        && System.currentTimeMillis() - entry.getValue().timestamp() > RENAME_TIMEOUT_MS);

                PENDING_RENAMES.put(player.getUniqueId(), new PendingRename(name, type, System.currentTimeMillis()));
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.rename.prompt",
                    Map.of("name", escape(name), "timeout", String.valueOf(RENAME_TIMEOUT_MS / 1000))));
                return 1;
            }

            // 控制台：参数直接包含旧名与新名（支持引号包裹带空格的路径点名）
            String raw = ctx.getArgument("name", String.class).trim();
            List<String> tokens = parseQuotedTokens(raw);
            String name;
            String newName;
            UUID ownerUuid = null;
            if (type == WaypointType.PUBLIC) {
                if (tokens.size() < 2) {
                    sender.sendMessage(getLocaleMessage(plugin, "waypoint.rename.usage"));
                    return 1;
                }
                name = tokens.get(0);
                newName = String.join(" ", tokens.subList(1, tokens.size()));
            } else {
                if (tokens.size() < 3) {
                    sender.sendMessage(getLocaleMessage(plugin, "waypoint.rename.usage"));
                    return 1;
                }
                String ownerName = tokens.get(0);
                name = tokens.get(1);
                newName = String.join(" ", tokens.subList(2, tokens.size()));
                ownerUuid = resolveOwnerUuid(source, ownerName, plugin);
                if (ownerUuid == null) return 1;
            }

            return performRename(sender, plugin, type, name, newName, ownerUuid);
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute waypoint rename command", e);
        }
    }

    // 聊天框输入新名字后（主线程）执行重命名
    private static void processRename(Player player, PendingRename pending, String newName, BringTeleportPlugin plugin) {
        PENDING_RENAMES.remove(player.getUniqueId());

        if (System.currentTimeMillis() - pending.timestamp() > RENAME_TIMEOUT_MS) {
            player.sendMessage(getLocaleMessage(plugin, "waypoint.rename.expired"));
            return;
        }

        UUID ownerUuid = (pending.type() == WaypointType.PRIVATE) ? player.getUniqueId() : null;
        performRename(player, plugin, pending.type(), pending.name(), newName, ownerUuid);
    }

    private static int performRename(CommandSender sender, BringTeleportPlugin plugin, WaypointType type, String name, String newName, UUID ownerUuid) {
        if (newName.isEmpty()) {
            sender.sendMessage(getLocaleMessage(plugin, "waypoint.error.name-required"));
            return 1;
        }
        if (newName.length() > 32) {
            sender.sendMessage(getLocaleMessage(plugin, "waypoint.error.name-too-long"));
            return 1;
        }

        WaypointManager manager = plugin.getWaypointManager();
        if (manager.getWaypoint(newName, type, ownerUuid).isPresent()) {
            sender.sendMessage(plugin.getLocaleManager().getMessage("waypoint.rename.duplicate",
                Map.of("newName", escape(newName))));
            return 1;
        }

        if (!manager.renameWaypoint(name, type, ownerUuid, newName)) {
            sender.sendMessage(plugin.getLocaleManager().getMessage("waypoint.rename.not-found",
                Map.of("name", escape(name), "type", getTypeLabel(plugin, type))));
            return 1;
        }

        sender.sendMessage(plugin.getLocaleManager().getMessage("waypoint.rename.success",
            Map.of("name", escape(name), "newName", escape(newName))));
        return 1;
    }

    private static int executeInfo(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, WaypointType type) {
        try {
            CommandSourceStack source = ctx.getSource();
            CommandSender sender = source.getSender();
            if (!sender.hasPermission("bringteleport.waypoint.info")) {
                sender.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            String name;
            UUID ownerUuid = null;
            if (type == WaypointType.PRIVATE) {
                PrivateTarget target = resolvePrivateTarget(ctx, plugin);
                if (target == null) return 1;
                name = target.name();
                ownerUuid = resolveOwnerUuid(source, target.ownerName(), plugin);
                if (ownerUuid == null) return 1;
            } else {
                name = resolveName(ctx, plugin);
                if (name == null) return 1;
            }

            WaypointManager manager = plugin.getWaypointManager();

            Optional<Waypoint> opt = manager.getWaypoint(name, type, ownerUuid);
            if (opt.isEmpty()) {
                var typeLabel = getTypeLabel(plugin, type);
                sender.sendMessage(plugin.getLocaleManager().getMessage("waypoint.info.not-found",
                    Map.of("name", escape(name), "type", typeLabel)));
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
            Map<String, String> placeholders = new HashMap<>(locationPlaceholders(waypoint, plugin));
            placeholders.put("name", escape(waypoint.getName()));
            placeholders.put("type", typeLabel);
            placeholders.put("creator", escape(creatorName));
            placeholders.put("date", formattedDate);

            String info = plugin.getLocaleManager().getRaw("waypoint.info.template");
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                info = info.replace("{" + entry.getKey() + "}", entry.getValue());
            }

            sender.sendMessage(MiniMessage.miniMessage().deserialize(info));
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute waypoint info command", e);
        }
    }

    // 世界名与坐标占位符（info 模板与共享通知共用同一套格式化）
    private static Map<String, String> locationPlaceholders(Waypoint waypoint, BringTeleportPlugin plugin) {
        return Map.of(
            "world", plugin.getLocaleManager().getWorldName(waypoint.getWorld()),
            "x", String.format("%.0f", waypoint.getX()),
            "y", String.format("%.0f", waypoint.getY()),
            "z", String.format("%.0f", waypoint.getZ()));
    }

    // 转义玩家输入中的 MiniMessage 特殊字符，防止路径点名注入标签或破坏 click 参数引号
    // 顺序固定：先反斜杠，再尖括号，最后单引号
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("<", "\\<").replace("'", "\\'");
    }

    private static String formatDateTime(String dbTimestamp) {
        if (dbTimestamp == null || dbTimestamp.isEmpty()) return "---";
        try {
            LocalDateTime dt = LocalDateTime.parse(dbTimestamp,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            // SQLite CURRENT_TIMESTAMP 存的是 UTC，转换到配置的显示时区
            return dt.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(ConfigCache.displayZone)
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"));
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
                    Map.of("name", escape(name), "type", typeLabel)));
                return 1;
            }

            Waypoint waypoint = opt.get();
            teleportTo(player, plugin, waypoint);
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute waypoint tp command", e);
        }
    }

    // 传送玩家到路径点（含倒计时、back 记录、成功提示）；世界未加载时返回 false
    private static boolean teleportTo(Player player, BringTeleportPlugin plugin, Waypoint waypoint) {
        Location location = plugin.getWaypointManager().toLocation(waypoint);
        if (location == null) {
            player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.tp.world-not-loaded",
                Map.of("world", plugin.getLocaleManager().getWorldName(waypoint.getWorld()))));
            return false;
        }

        boolean countdownEnabled = ConfigCache.countdownEnabled;
        double delaySec = ConfigCache.countdownDelay;
        if (countdownEnabled && delaySec > 0) {
            double intervalSec = ConfigCache.countdownInterval;
            final double tickInterval = intervalSec > 0 ? intervalSec : 1.0;
            String displayMode = ConfigCache.countdownDisplayMode;
            Location playerLoc = player.getLocation();
            Location targetLoc = location;
            String wpName = waypoint.getName();

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

            return true;
        }

        plugin.getTeleportHistory().record(player, player.getLocation());
        player.teleportAsync(location).thenAccept(success ->
            sendTeleportSuccess(player, plugin, waypoint.getName()));

        return true;
    }

    private static int executeTpWarp(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            Player player = resolvePlayer(ctx, plugin);
            if (player == null) return 1;

            if (!player.hasPermission("bringteleport.waypoint.tp")) {
                player.sendMessage(getLocaleMessage(plugin, "waypoint.error.no-permission"));
                return 0;
            }

            String name = resolveName(ctx, plugin);
            if (name == null) return 1;

            // 同名时私有路径点优先（自己的位置比共享的更符合直觉）
            WaypointManager manager = plugin.getWaypointManager();
            Optional<Waypoint> opt = manager.getWaypoint(name, WaypointType.PRIVATE, player.getUniqueId());
            if (opt.isEmpty()) {
                opt = manager.getWaypoint(name, WaypointType.PUBLIC, null);
            }
            if (opt.isEmpty()) {
                player.sendMessage(plugin.getLocaleManager().getMessage("waypoint.tp.not-found",
                    Map.of("name", escape(name), "type", getTypeLabel(plugin, WaypointType.PUBLIC))));
                return 1;
            }

            teleportTo(player, plugin, opt.get());
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute tpwarp command", e);
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
            plugin.getLocaleManager().getRaw("waypoint.tp.success.title").replace("{name}", escape(waypointName)));
        Component subtitleComp = MiniMessage.miniMessage().deserialize(
            plugin.getLocaleManager().getRaw("waypoint.tp.success.subtitle").replace("{name}", escape(waypointName)));
        Component chatComp = plugin.getLocaleManager().getMessage("waypoint.tp.success.chat",
            Map.of("name", escape(waypointName)));
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
