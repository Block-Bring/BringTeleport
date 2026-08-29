package top.imbring.bringteleport.command;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import top.imbring.bringteleport.BringTeleportPlugin;
import top.imbring.bringteleport.service.DeathBackManager;
import top.imbring.bringteleport.service.SafePointFinder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.papermc.paper.command.brigadier.Commands.literal;

public final class DeathBackCommand {

    private static final String PERMISSION = "bringteleport.deathback";

    private static class ConfigCache {
        static boolean enabled;
        static boolean notifyOnRespawn;
        static boolean safePointEnabled;
        static int radiusH;
        static int radiusV;
        static int effort;

        private ConfigCache() {}

        static void refresh(BringTeleportPlugin plugin) {
            var config = plugin.getConfig();
            enabled = config.getBoolean("death-back.enabled", true);
            notifyOnRespawn = config.getBoolean("death-back.notify-on-respawn", true);
            safePointEnabled = config.getBoolean("death-back.safe-point.enabled", true);
            radiusH = Math.max(0, config.getInt("death-back.safe-point.radius-horizontal", 32));
            radiusV = Math.max(0, config.getInt("death-back.safe-point.radius-vertical", 16));
            effort = Math.max(1, Math.min(3, config.getInt("death-back.safe-point.effort", 2)));
        }
    }

    private DeathBackCommand() {
    }

    public static void register(Commands commands, BringTeleportPlugin plugin) {
        ConfigCache.refresh(plugin);

        // 死亡时记录位置；无权限的玩家不记录，省存储。
        // 死亡点本身安全时直接落库（绝大多数情况）；不安全时异步搜索安全点，
        // 大范围方块搜索放到主线程之外，避免死亡瞬间卡服
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerDeath(PlayerDeathEvent event) {
                Player player = event.getPlayer();
                if (!ConfigCache.enabled || !player.hasPermission(PERMISSION)) return;
                Location location = player.getLocation();
                if (!ConfigCache.safePointEnabled || SafePointFinder.isSpotSafe(location)) {
                    plugin.getDeathBackManager().saveDeathLocation(player, location);
                } else {
                    plugin.getDeathBackManager().saveDeathLocationWithSafePointAsync(
                        player, location, ConfigCache.radiusH, ConfigCache.radiusV, ConfigCache.effort);
                }
            }

            // 复活后提示可 /back 返回（不清除记录，玩家可能稍后再用），并显示实际死亡坐标
            @EventHandler
            public void onPlayerRespawn(PlayerRespawnEvent event) {
                Player player = event.getPlayer();
                if (!ConfigCache.enabled || !ConfigCache.notifyOnRespawn
                    || !player.hasPermission(PERMISSION)) return;
                Optional<DeathBackManager.DeathRecord> opt =
                    plugin.getDeathBackManager().getDeathRecord(player.getUniqueId());
                if (opt.isEmpty()) return;
                DeathBackManager.DeathRecord record = opt.get();
                Map<String, String> placeholders = Map.of(
                    "world", plugin.getLocaleManager().getWorldName(record.world()),
                    "x", String.format("%.0f", record.x()),
                    "y", String.format("%.0f", record.y()),
                    "z", String.format("%.0f", record.z()));
                player.sendMessage(plugin.getLocaleManager().getMessage("deathback.notify", placeholders));
            }
        }, plugin);

        var backNode = literal("back")
            .then(literal("confirm").executes(ctx -> executeBackConfirm(ctx, plugin)))
            .executes(ctx -> executeBack(ctx, plugin))
            .build();
        commands.register(backNode, "Return to your last death location", List.of());
    }

    public static void refreshConfigCache(BringTeleportPlugin plugin) {
        ConfigCache.refresh(plugin);
    }

    private static int executeBack(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            Player player = requirePlayer(plugin, ctx);
            if (player == null) return 1;
            if (!player.hasPermission(PERMISSION)) {
                player.sendMessage(getLocaleMessage(plugin, "deathback.error.no-permission"));
                return 0;
            }
            if (!ConfigCache.enabled) {
                player.sendMessage(getLocaleMessage(plugin, "deathback.error.disabled"));
                return 1;
            }
            UUID uuid = player.getUniqueId();
            // 死亡点不安全时，安全点可能还在异步搜索中：等它完成再读取记录传送，
            // 否则会先落回死亡点（如岩浆/海里）再死一次
            runAfterSearch(plugin, player, uuid, () -> {
                DeathBackManager.DeathRecord record = readRecordOrWarn(plugin, player);
                if (record == null) return;
                if (record.safeX() != null || !record.dangerous()) {
                    teleportBack(plugin, player, uuid, record, false);
                } else {
                    // 未找到安全点且死亡点危险：要求 /back confirm 确认
                    player.sendMessage(getLocaleMessage(plugin, "deathback.confirm-required"));
                }
            });
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute back command", e);
        }
    }

    // /back confirm：确认返回危险死亡点（未找到安全点时强制传死亡点）
    private static int executeBackConfirm(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            Player player = requirePlayer(plugin, ctx);
            if (player == null) return 1;
            if (!player.hasPermission(PERMISSION)) {
                player.sendMessage(getLocaleMessage(plugin, "deathback.error.no-permission"));
                return 0;
            }
            if (!ConfigCache.enabled) {
                player.sendMessage(getLocaleMessage(plugin, "deathback.error.disabled"));
                return 1;
            }
            UUID uuid = player.getUniqueId();
            runAfterSearch(plugin, player, uuid, () -> {
                DeathBackManager.DeathRecord record = readRecordOrWarn(plugin, player);
                if (record == null) return;
                // 有安全点则传安全点；无安全点（危险或安全死亡点）都传死亡点
                teleportBack(plugin, player, uuid, record, record.safeX() == null);
            });
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute back confirm command", e);
        }
    }

    private static Player requirePlayer(BringTeleportPlugin plugin, CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(getLocaleMessage(plugin, "deathback.error.player-only"));
            return null;
        }
        return player;
    }

    // 等待进行中的安全点搜索完成后在主线程执行 action
    private static void runAfterSearch(BringTeleportPlugin plugin, Player player, UUID uuid, Runnable action) {
        CompletableFuture<Void> pending = plugin.getDeathBackManager().getPendingSearch(uuid);
        if (pending != null) {
            pending.whenComplete((v, ex) -> {
                if (plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(plugin, action);
                }
            });
        } else {
            action.run();
        }
    }

    private static DeathBackManager.DeathRecord readRecordOrWarn(BringTeleportPlugin plugin, Player player) {
        Optional<DeathBackManager.DeathRecord> opt =
            plugin.getDeathBackManager().getDeathRecord(player.getUniqueId());
        if (opt.isEmpty()) {
            player.sendMessage(getLocaleMessage(plugin, "deathback.error.no-record"));
            return null;
        }
        return opt.get();
    }

    // 读取死亡记录并传送（主线程执行）；forceDeathPoint=true 时忽略安全点直接传死亡点
    private static void teleportBack(BringTeleportPlugin plugin, Player player, UUID uuid,
                                     DeathBackManager.DeathRecord record, boolean forceDeathPoint) {
        World world = Bukkit.getWorld(record.world());
        if (world == null) {
            player.sendMessage(plugin.getLocaleManager().getMessage("deathback.error.world-not-loaded",
                Map.of("world", plugin.getLocaleManager().getWorldName(record.world()))));
            return;
        }

        Location target;
        if (!forceDeathPoint && record.safeX() != null) {
            target = new Location(world, record.safeX(), record.safeY(), record.safeZ(), record.yaw(), record.pitch());
        } else {
            target = new Location(world, record.x(), record.y(), record.z(), record.yaw(), record.pitch());
        }

        // 记录当前位置以便 /warp tp back undo 撤销；不进历史链，避免污染 back
        plugin.getTeleportHistory().setLastBackSource(player, player.getLocation());

        // 传送成功后才清除记录：失败时保留以便玩家重试
        player.teleportAsync(target).thenAccept(success -> {
            if (success) {
                plugin.getDeathBackManager().clear(uuid);
                player.sendMessage(getLocaleMessage(plugin, "deathback.success"));
            } else {
                player.sendMessage(getLocaleMessage(plugin, "deathback.error.teleport-failed"));
            }
        });
    }

    private static int handleError(BringTeleportPlugin plugin, CommandContext<CommandSourceStack> ctx, String errorMsg, Exception e) {
        plugin.getLogger().log(java.util.logging.Level.SEVERE, errorMsg, e);
        ctx.getSource().getSender().sendMessage(Component.text("An internal error occurred. Please try again."));
        return 0;
    }

    private static Component getLocaleMessage(BringTeleportPlugin plugin, String path) {
        return plugin.getLocaleManager().getMessage(path, null);
    }
}
