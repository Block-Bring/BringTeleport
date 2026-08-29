package top.imbring.bringteleport.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import top.imbring.bringteleport.BringTeleportPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

public final class TpaCommand {

    private static final String PERM_REQUEST = "bringteleport.tpa.request";
    private static final String PERM_ACCEPT = "bringteleport.tpa.accept";
    private static final String PERM_BACK = "bringteleport.tpa.back";

    // 被请求者 UUID -> 收到的请求（按时间先后，最早的在前）
    private static final Map<UUID, List<PendingRequest>> REQUESTS = new HashMap<>();
    // 玩家 UUID -> 最近一次 TPA 传送前的位置（/tpaback 返回用）
    private static final Map<UUID, Location> BACK_LOCATIONS = new HashMap<>();
    // 倒计时中的玩家 UUID -> 倒计时上下文（移动取消用）
    private static final Map<UUID, CountdownContext> ACTIVE_COUNTDOWNS = new HashMap<>();

    // here=false：请求者想传送到被请求者身边；here=true：请求被请求者传送过来
    private record PendingRequest(UUID requesterUuid, String requesterName, boolean here, long timestamp) {}
    private record CountdownContext(BukkitTask task, int startBlockX, int startBlockY, int startBlockZ) {}

    private static class ConfigCache {
        static long timeoutMs;

        // 传送倒计时（与 warp 同款：移动取消、音效、title/chat 显示）
        static boolean countdownEnabled;
        static double countdownDelay;
        static double countdownInterval;
        static String countdownDisplayMode;

        static boolean countdownSoundEnabled;
        static String countdownSoundName;
        static int countdownSoundInterval;
        static float countdownSoundVolume;
        static float countdownSoundPitch;

        static boolean cancelOnMoveEnabled;
        static String cancelDisplayMode;
        static String cancelSoundName;
        static float cancelSoundVolume;
        static float cancelSoundPitch;

        // 传送成功显示
        static String successDisplayMode;
        static boolean successSoundEnabled;
        static String successSoundName;
        static float successSoundVolume;
        static float successSoundPitch;

        private ConfigCache() {}

        static void refresh(BringTeleportPlugin plugin) {
            var config = plugin.getConfig();
            timeoutMs = Math.max(1, (long) (config.getDouble("tpa.timeout", 60.0) * 1000));

            countdownEnabled = config.getBoolean("tpa.teleport.countdown.enabled", true);
            countdownDelay = config.getDouble("tpa.teleport.countdown.delay", 3.0);
            countdownInterval = config.getDouble("tpa.teleport.countdown.interval", 1.0);
            countdownDisplayMode = config.getString("tpa.teleport.countdown.display", "both");

            countdownSoundEnabled = config.getBoolean("tpa.teleport.countdown.sound.enabled", true);
            countdownSoundName = config.getString("tpa.teleport.countdown.sound.name", "entity.experience_orb.pickup");
            countdownSoundInterval = config.getInt("tpa.teleport.countdown.sound.interval", 1);
            countdownSoundVolume = (float) config.getDouble("tpa.teleport.countdown.sound.volume", 1.0);
            countdownSoundPitch = (float) config.getDouble("tpa.teleport.countdown.sound.pitch", 1.0);

            cancelOnMoveEnabled = config.getBoolean("tpa.teleport.countdown.cancel-on-move.enabled", true);
            cancelDisplayMode = config.getString("tpa.teleport.countdown.cancel-on-move.display", "chat");
            cancelSoundName = config.getString("tpa.teleport.countdown.cancel-on-move.sound.name", "block.anvil.place");
            cancelSoundVolume = (float) config.getDouble("tpa.teleport.countdown.cancel-on-move.sound.volume", 1.0);
            cancelSoundPitch = (float) config.getDouble("tpa.teleport.countdown.cancel-on-move.sound.pitch", 1.0);

            successDisplayMode = config.getString("tpa.teleport.success.display", "title");
            successSoundEnabled = config.getBoolean("tpa.teleport.success.sound.enabled", true);
            successSoundName = config.getString("tpa.teleport.success.sound.name", "entity.player.levelup");
            successSoundVolume = (float) config.getDouble("tpa.teleport.success.sound.volume", 1.0);
            successSoundPitch = (float) config.getDouble("tpa.teleport.success.sound.pitch", 1.0);
        }
    }

    private TpaCommand() {
    }

    public static void register(Commands commands, BringTeleportPlugin plugin) {
        ConfigCache.refresh(plugin);
        var string = com.mojang.brigadier.arguments.StringArgumentType.word();

        var tpaNode = literal("tpa")
            .executes(ctx -> executeHelp(ctx, plugin))
            .then(argument("player", string)
                .suggests((ctx, builder) -> suggestOnlinePlayers(ctx.getSource(), builder))
                .executes(ctx -> executeTpa(ctx, plugin, false)))
            .build();
        commands.register(tpaNode, "Request to teleport to another player", List.of());

        var tpaHereNode = literal("tpahere")
            .executes(ctx -> executeHelp(ctx, plugin))
            .then(argument("player", string)
                .suggests((ctx, builder) -> suggestOnlinePlayers(ctx.getSource(), builder))
                .executes(ctx -> executeTpa(ctx, plugin, true)))
            .build();
        commands.register(tpaHereNode, "Request another player to teleport to you", List.of());

        var tpAcceptNode = literal("tpaccept")
            .then(argument("player", string)
                .suggests((ctx, builder) -> suggestRequesters(ctx, builder))
                .executes(ctx -> executeAccept(ctx, plugin, true)))
            .executes(ctx -> executeAccept(ctx, plugin, false))
            .build();
        commands.register(tpAcceptNode, "Accept a teleport request", List.of());

        var tpDenyNode = literal("tpadeny")
            .then(argument("player", string)
                .suggests((ctx, builder) -> suggestRequesters(ctx, builder))
                .executes(ctx -> executeDeny(ctx, plugin, true)))
            .executes(ctx -> executeDeny(ctx, plugin, false))
            .build();
        commands.register(tpDenyNode, "Deny a teleport request", List.of());

        var tpaBackNode = literal("tpaback")
            .executes(ctx -> executeBack(ctx, plugin))
            .build();
        commands.register(tpaBackNode, "Return to your location before the last TPA", List.of());

        // 玩家下线时清空其收到的请求，避免残留过期请求
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerQuit(PlayerQuitEvent event) {
                REQUESTS.remove(event.getPlayer().getUniqueId());
            }
        }, plugin);

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

                if (!ConfigCache.cancelOnMoveEnabled) return;

                ACTIVE_COUNTDOWNS.remove(uuid);
                ctx.task.cancel();
                // 未传送，清除 back 记录
                BACK_LOCATIONS.remove(uuid);

                Player player = event.getPlayer();
                player.playSound(player.getLocation(), ConfigCache.cancelSoundName, SoundCategory.MASTER,
                    ConfigCache.cancelSoundVolume, ConfigCache.cancelSoundPitch);

                String displayMode = ConfigCache.cancelDisplayMode;
                String text = plugin.getLocaleManager().getRaw("tpa.teleport.countdown.cancelled");
                Component comp = MiniMessage.miniMessage().deserialize(text);
                Component chatComp = plugin.getLocaleManager().getMessage("tpa.teleport.countdown.cancelled", null);
                sendDisplayMessage(player, comp, comp, chatComp, displayMode);
            }
        }, plugin);
    }

    public static void cancelAllCountdowns() {
        ACTIVE_COUNTDOWNS.values().forEach(ctx -> ctx.task().cancel());
        ACTIVE_COUNTDOWNS.clear();
    }

    public static void refreshConfigCache(BringTeleportPlugin plugin) {
        ConfigCache.refresh(plugin);
    }

    private static int executeTpa(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, boolean here) {
        try {
            Player requester = requirePlayer(plugin, ctx);
            if (requester == null) return 1;
            if (!requester.hasPermission(PERM_REQUEST)) {
                requester.sendMessage(getLocaleMessage(plugin, "tpa.error.no-permission"));
                return 0;
            }

            String targetName = ctx.getArgument("player", String.class).trim();
            Player target = resolveOnlinePlayer(targetName);
            if (target == null) {
                requester.sendMessage(plugin.getLocaleManager().getMessage("tpa.error.player-not-found",
                    Map.of("player", escape(targetName))));
                return 1;
            }
            if (target.getUniqueId().equals(requester.getUniqueId())) {
                requester.sendMessage(getLocaleMessage(plugin, "tpa.error.self"));
                return 1;
            }

            sendRequest(requester, target, here, plugin);
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute tpa command", e);
        }
    }

    private static void sendRequest(Player requester, Player target, boolean here, BringTeleportPlugin plugin) {
        long now = System.currentTimeMillis();
        List<PendingRequest> list = REQUESTS.computeIfAbsent(target.getUniqueId(), k -> new ArrayList<>());
        list.removeIf(r -> now - r.timestamp() > ConfigCache.timeoutMs);
        // 同一请求者对同一目标的重复请求：覆盖旧请求
        list.removeIf(r -> r.requesterUuid().equals(requester.getUniqueId()));
        list.add(new PendingRequest(requester.getUniqueId(), requester.getName(), here, now));

        requester.sendMessage(plugin.getLocaleManager().getMessage(
            here ? "tpa.request.here-sent" : "tpa.request.sent",
            Map.of("player", escape(target.getName()))));
        target.sendMessage(plugin.getLocaleManager().getMessage(
            here ? "tpa.request.here-received" : "tpa.request.received",
            Map.of("player", escape(requester.getName()))));
    }

    private static int executeAccept(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, boolean hasArg) {
        try {
            Player acceptor = requirePlayer(plugin, ctx);
            if (acceptor == null) return 1;
            if (!acceptor.hasPermission(PERM_ACCEPT)) {
                acceptor.sendMessage(getLocaleMessage(plugin, "tpa.error.no-permission"));
                return 0;
            }

            PendingRequest request = pickRequest(ctx, plugin, acceptor, hasArg, true);
            if (request == null) return 1;

            removeRequest(acceptor.getUniqueId(), request);
            fulfillRequest(acceptor, request, plugin, true);
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute tpaccept command", e);
        }
    }

    private static int executeDeny(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin, boolean hasArg) {
        try {
            Player acceptor = requirePlayer(plugin, ctx);
            if (acceptor == null) return 1;
            if (!acceptor.hasPermission(PERM_ACCEPT)) {
                acceptor.sendMessage(getLocaleMessage(plugin, "tpa.error.no-permission"));
                return 0;
            }

            PendingRequest request = pickRequest(ctx, plugin, acceptor, hasArg, false);
            if (request == null) return 1;

            removeRequest(acceptor.getUniqueId(), request);
            fulfillRequest(acceptor, request, plugin, false);
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute tpadeny command", e);
        }
    }

    // 从被请求者的请求列表中选择要处理的请求；无参数且仅有一个请求时直接取它
    private static PendingRequest pickRequest(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin,
                                              Player acceptor, boolean hasArg, boolean accept) {
        List<PendingRequest> valid = validRequests(acceptor.getUniqueId());
        if (valid.isEmpty()) {
            acceptor.sendMessage(getLocaleMessage(plugin, "tpa.error.no-request"));
            return null;
        }

        if (!hasArg && valid.size() > 1) {
            acceptor.sendMessage(getLocaleMessage(plugin, accept ? "tpa.accept.multiple" : "tpa.deny.multiple"));
            return null;
        }

        PendingRequest request;
        if (hasArg) {
            String name = ctx.getArgument("player", String.class).trim();
            request = valid.stream()
                .filter(r -> r.requesterName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
            if (request == null) {
                acceptor.sendMessage(plugin.getLocaleManager().getMessage("tpa.error.no-request-from",
                    Map.of("player", escape(name))));
                return null;
            }
        } else {
            request = valid.get(0);
        }
        return request;
    }

    // 接受/拒绝后通知双方；接受时执行传送（被请求者传送为 tpahere，请求者传送为 tpa）
    private static void fulfillRequest(Player acceptor, PendingRequest request, BringTeleportPlugin plugin, boolean accepted) {
        Player requester = Bukkit.getPlayer(request.requesterUuid());
        if (requester == null || !requester.isOnline()) {
            acceptor.sendMessage(getLocaleMessage(plugin, "tpa.error.requester-offline"));
            return;
        }

        Map<String, String> acceptorPlaceholders = Map.of("player", escape(requester.getName()));
        Map<String, String> requesterPlaceholders = Map.of("player", escape(acceptor.getName()));
        acceptor.sendMessage(plugin.getLocaleManager().getMessage(
            accepted ? "tpa.accept.success" : "tpa.deny.success", acceptorPlaceholders));
        requester.sendMessage(plugin.getLocaleManager().getMessage(
            accepted ? "tpa.accept.notify" : "tpa.deny.notify", requesterPlaceholders));

        if (accepted) {
            if (request.here()) {
                // tpahere：被请求者传送到请求者身边
                teleportPlayer(acceptor, requester.getLocation(), requester.getName(), plugin);
            } else {
                // tpa：请求者传送到被请求者身边
                teleportPlayer(requester, acceptor.getLocation(), acceptor.getName(), plugin);
            }
        }
    }

    // 传送前记录当前位置供 /tpaback 返回；倒计时启用时先倒计时再传送
    private static void teleportPlayer(Player player, Location target, String targetName, BringTeleportPlugin plugin) {
        BACK_LOCATIONS.put(player.getUniqueId(), player.getLocation());
        if (ConfigCache.countdownEnabled && ConfigCache.countdownDelay > 0) {
            startCountdown(player, target, targetName, plugin);
        } else {
            doTeleport(player, target, targetName, plugin);
        }
    }

    // 直接传送；传送成功显示提示，失败时移除 back 记录（玩家仍在原地）
    private static void doTeleport(Player player, Location target, String targetName, BringTeleportPlugin plugin) {
        player.teleportAsync(target).thenAccept(success -> {
            if (success) {
                sendTeleportSuccess(player, target, targetName, plugin);
            } else {
                BACK_LOCATIONS.remove(player.getUniqueId());
                if (player.isOnline()) {
                    player.sendMessage(getLocaleMessage(plugin, "tpa.error.teleport-failed"));
                }
            }
        });
    }

    // 传送成功提示（与 warp 传送成功同款：title/chat 显示 + 音效）；subtitle 默认显示目标坐标
    private static void sendTeleportSuccess(Player player, Location target, String targetName, BringTeleportPlugin plugin) {
        String displayMode = ConfigCache.successDisplayMode;

        if (ConfigCache.successSoundEnabled) {
            player.playSound(player.getLocation(), ConfigCache.successSoundName, SoundCategory.MASTER,
                ConfigCache.successSoundVolume, ConfigCache.successSoundPitch);
        }

        Component titleComp = MiniMessage.miniMessage().deserialize(
            plugin.getLocaleManager().getRaw("tpa.teleport.success.title").replace("{player}", escape(targetName)));
        String subtitleRaw = plugin.getLocaleManager().getRaw("tpa.teleport.success.subtitle")
            .replace("{player}", escape(targetName))
            .replace("{world}", plugin.getLocaleManager().getWorldName(target.getWorld().getName()))
            .replace("{x}", String.format("%.0f", target.getX()))
            .replace("{y}", String.format("%.0f", target.getY()))
            .replace("{z}", String.format("%.0f", target.getZ()));
        Component subtitleComp = MiniMessage.miniMessage().deserialize(subtitleRaw);
        Component chatComp = plugin.getLocaleManager().getMessage("tpa.teleport.success.chat",
            Map.of("player", escape(targetName)));
        sendDisplayMessage(player, titleComp, subtitleComp, chatComp, displayMode);
    }

    // 传送倒计时（与 warp 传送同款）：期间移动取消，倒计时结束执行传送
    private static void startCountdown(Player player, Location target, String targetName, BringTeleportPlugin plugin) {
        double delaySec = ConfigCache.countdownDelay;
        double intervalSec = ConfigCache.countdownInterval;
        final double tickInterval = intervalSec > 0 ? intervalSec : 1.0;
        String displayMode = ConfigCache.countdownDisplayMode;

        CountdownContext existing = ACTIVE_COUNTDOWNS.remove(player.getUniqueId());
        if (existing != null) existing.task().cancel();

        int totalSteps = (int) Math.ceil(delaySec / tickInterval);
        long intervalTicks = Math.max(1, (long) (tickInterval * 20));

        Location playerLoc = player.getLocation();
        int startBlockX = playerLoc.getBlockX();
        int startBlockY = playerLoc.getBlockY();
        int startBlockZ = playerLoc.getBlockZ();

        BukkitTask task = new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    ACTIVE_COUNTDOWNS.remove(player.getUniqueId());
                    BACK_LOCATIONS.remove(player.getUniqueId());
                    cancel();
                    return;
                }

                if (step >= totalSteps) {
                    ACTIVE_COUNTDOWNS.remove(player.getUniqueId());
                    doTeleport(player, target, targetName, plugin);
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
                if (ConfigCache.countdownSoundEnabled && ConfigCache.countdownSoundInterval > 0
                    && step % ConfigCache.countdownSoundInterval == 0) {
                    player.playSound(player.getLocation(), ConfigCache.countdownSoundName, SoundCategory.MASTER,
                        ConfigCache.countdownSoundVolume, ConfigCache.countdownSoundPitch);
                }

                String titleRaw = plugin.getLocaleManager().getRaw("tpa.teleport.countdown.title")
                    .replace("{seconds}", secStr);
                String subtitleRaw = plugin.getLocaleManager().getRaw("tpa.teleport.countdown.subtitle")
                    .replace("{seconds}", secStr);
                Component cTitle = MiniMessage.miniMessage().deserialize(titleRaw);
                Component cSubtitle = MiniMessage.miniMessage().deserialize(subtitleRaw);

                String chatRaw = plugin.getLocaleManager().getRaw("tpa.teleport.countdown.chat")
                    .replace("{seconds}", secStr)
                    .replace("{player}", escape(targetName));

                sendDisplayMessage(player, cTitle, cSubtitle,
                    MiniMessage.miniMessage().deserialize(chatRaw), displayMode);

                step++;
            }
        }.runTaskTimer(plugin, 0L, intervalTicks);
        ACTIVE_COUNTDOWNS.put(player.getUniqueId(), new CountdownContext(task, startBlockX, startBlockY, startBlockZ));
    }

    // 统一显示消息（title / subtitle / both / chat）
    private static void sendDisplayMessage(Player player, Component titleComponent, Component subtitleComponent,
                                           Component chatComponent, String displayMode) {
        var times = Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(250));
        switch (displayMode) {
            case "title" -> player.showTitle(Title.title(titleComponent, Component.empty(), times));
            case "subtitle" -> player.showTitle(Title.title(Component.empty(), subtitleComponent, times));
            case "both" -> player.showTitle(Title.title(titleComponent, subtitleComponent, times));
            default -> player.sendMessage(chatComponent);
        }
    }

    private static int executeBack(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            Player player = requirePlayer(plugin, ctx);
            if (player == null) return 1;
            if (!player.hasPermission(PERM_BACK)) {
                player.sendMessage(getLocaleMessage(plugin, "tpa.error.no-permission"));
                return 0;
            }

            Location back = BACK_LOCATIONS.remove(player.getUniqueId());
            if (back == null) {
                player.sendMessage(getLocaleMessage(plugin, "tpa.back.none"));
                return 1;
            }

            // 传送失败则放回记录，便于重试
            player.teleportAsync(back).thenAccept(success -> {
                if (success) {
                    player.sendMessage(getLocaleMessage(plugin, "tpa.back.success"));
                } else {
                    BACK_LOCATIONS.put(player.getUniqueId(), back);
                    player.sendMessage(getLocaleMessage(plugin, "tpa.error.teleport-failed"));
                }
            });
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute tpaback command", e);
        }
    }

    private static int executeHelp(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            ctx.getSource().getSender().sendMessage(plugin.getLocaleManager().getMessage("tpa.help", null));
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute tpa help command", e);
        }
    }

    // 清理目标玩家已过期的请求；全部过期则移除整个列表
    private static List<PendingRequest> validRequests(UUID targetUuid) {
        List<PendingRequest> list = REQUESTS.get(targetUuid);
        if (list == null) return List.of();
        long now = System.currentTimeMillis();
        list.removeIf(r -> now - r.timestamp() > ConfigCache.timeoutMs);
        if (list.isEmpty()) {
            REQUESTS.remove(targetUuid);
            return List.of();
        }
        return list;
    }

    private static void removeRequest(UUID targetUuid, PendingRequest request) {
        List<PendingRequest> list = REQUESTS.get(targetUuid);
        if (list != null) {
            list.remove(request);
            if (list.isEmpty()) {
                REQUESTS.remove(targetUuid);
            }
        }
    }

    private static Player requirePlayer(BringTeleportPlugin plugin, CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(getLocaleMessage(plugin, "tpa.error.player-only"));
            return null;
        }
        return player;
    }

    // 优先精确匹配，其次忽略大小写匹配在线玩家
    private static Player resolveOnlinePlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) return online;
        }
        return null;
    }

    // 建议在线玩家名（排除自己）
    private static CompletableFuture<Suggestions> suggestOnlinePlayers(CommandSourceStack source, SuggestionsBuilder builder) {
        Player self = source.getSender() instanceof Player player ? player : null;
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (self != null && online.getUniqueId().equals(self.getUniqueId())) continue;
            names.add(online.getName());
        }
        return suggestTokens(builder, names);
    }

    // 建议当前玩家收到的未过期请求的发送者名
    private static CompletableFuture<Suggestions> suggestRequesters(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return builder.buildFuture();
        }
        List<String> names = validRequests(player.getUniqueId()).stream()
            .map(PendingRequest::requesterName)
            .distinct()
            .toList();
        return suggestTokens(builder, names);
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

    // 转义玩家输入中的 MiniMessage 特殊字符，防止注入标签或破坏 click 参数引号
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("<", "\\<").replace("'", "\\'");
    }

    private static Component getLocaleMessage(BringTeleportPlugin plugin, String path) {
        return plugin.getLocaleManager().getMessage(path, null);
    }

    private static int handleError(BringTeleportPlugin plugin, CommandContext<CommandSourceStack> ctx, String errorMsg, Exception e) {
        plugin.getLogger().log(Level.SEVERE, errorMsg, e);
        ctx.getSource().getSender().sendMessage(Component.text("An internal error occurred. Please try again."));
        return 0;
    }
}
