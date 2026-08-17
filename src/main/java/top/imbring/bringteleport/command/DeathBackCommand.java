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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.papermc.paper.command.brigadier.Commands.literal;

public final class DeathBackCommand {

    private static final String PERMISSION = "bringteleport.deathback";

    private static class ConfigCache {
        static boolean enabled;
        static boolean notifyOnRespawn;
        static boolean safePointEnabled;
        static int radiusH;
        static int radiusV;

        private ConfigCache() {}

        static void refresh(BringTeleportPlugin plugin) {
            var config = plugin.getConfig();
            enabled = config.getBoolean("death-back.enabled", true);
            notifyOnRespawn = config.getBoolean("death-back.notify-on-respawn", true);
            safePointEnabled = config.getBoolean("death-back.safe-point.enabled", true);
            radiusH = Math.max(0, config.getInt("death-back.safe-point.radius-horizontal", 32));
            radiusV = Math.max(0, config.getInt("death-back.safe-point.radius-vertical", 16));
        }
    }

    private DeathBackCommand() {
    }

    public static void register(Commands commands, BringTeleportPlugin plugin) {
        ConfigCache.refresh(plugin);

        // 死亡时记录位置；无权限的玩家不记录，省存储。
        // 安全点开关关闭时半径传 0，退化为只存死亡点本身
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerDeath(PlayerDeathEvent event) {
                Player player = event.getPlayer();
                if (!ConfigCache.enabled || !player.hasPermission(PERMISSION)) return;
                int radiusH = ConfigCache.safePointEnabled ? ConfigCache.radiusH : 0;
                int radiusV = ConfigCache.safePointEnabled ? ConfigCache.radiusV : 0;
                plugin.getDeathBackManager().saveDeathLocation(player, player.getLocation(), radiusH, radiusV);
            }

            // 复活后提示可 /back 返回（不清除记录，玩家可能稍后再用）
            @EventHandler
            public void onPlayerRespawn(PlayerRespawnEvent event) {
                Player player = event.getPlayer();
                if (!ConfigCache.enabled || !ConfigCache.notifyOnRespawn
                    || !player.hasPermission(PERMISSION)) return;
                if (plugin.getDeathBackManager().getDeathRecord(player.getUniqueId()).isEmpty()) return;
                player.sendMessage(plugin.getLocaleManager().getMessage("deathback.notify", null));
            }
        }, plugin);

        var backNode = literal("back")
            .executes(ctx -> executeBack(ctx, plugin))
            .build();
        commands.register(backNode, "Return to your last death location", List.of());
    }

    public static void refreshConfigCache(BringTeleportPlugin plugin) {
        ConfigCache.refresh(plugin);
    }

    private static int executeBack(CommandContext<CommandSourceStack> ctx, BringTeleportPlugin plugin) {
        try {
            CommandSourceStack source = ctx.getSource();
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage(getLocaleMessage(plugin, "deathback.error.player-only"));
                return 1;
            }

            if (!player.hasPermission(PERMISSION)) {
                player.sendMessage(getLocaleMessage(plugin, "deathback.error.no-permission"));
                return 0;
            }

            if (!ConfigCache.enabled) {
                player.sendMessage(getLocaleMessage(plugin, "deathback.error.disabled"));
                return 1;
            }

            UUID uuid = player.getUniqueId();
            DeathBackManager manager = plugin.getDeathBackManager();
            Optional<DeathBackManager.DeathRecord> opt = manager.getDeathRecord(uuid);
            if (opt.isEmpty()) {
                player.sendMessage(getLocaleMessage(plugin, "deathback.error.no-record"));
                return 1;
            }

            DeathBackManager.DeathRecord record = opt.get();
            World world = Bukkit.getWorld(record.world());
            if (world == null) {
                player.sendMessage(plugin.getLocaleManager().getMessage("deathback.error.world-not-loaded",
                    Map.of("world", plugin.getLocaleManager().getWorldName(record.world()))));
                return 1;
            }

            // 有安全点则传安全点（死亡点危险时），否则传原死亡点
            Location target;
            if (record.safeX() != null) {
                target = new Location(world, record.safeX(), record.safeY(), record.safeZ(), record.yaw(), record.pitch());
            } else {
                target = new Location(world, record.x(), record.y(), record.z(), record.yaw(), record.pitch());
            }

            // 记录当前位置以便 /warp tp back undo 撤销；不进历史链，避免污染 back
            plugin.getTeleportHistory().setLastBackSource(player, player.getLocation());
            manager.clear(uuid);

            Map<String, String> placeholders = Map.of(
                "world", plugin.getLocaleManager().getWorldName(world.getName()),
                "x", String.format("%.0f", target.getX()),
                "y", String.format("%.0f", target.getY()),
                "z", String.format("%.0f", target.getZ()));
            player.teleportAsync(target).thenAccept(success -> {
                if (success) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("deathback.success", placeholders));
                }
            });
            return 1;
        } catch (Exception e) {
            return handleError(plugin, ctx, "Failed to execute back command", e);
        }
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
