package top.imbring.bringteleport;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import top.imbring.bringteleport.command.CommandManager;
import top.imbring.bringteleport.command.DeathBackCommand;
import top.imbring.bringteleport.command.TpaCommand;
import top.imbring.bringteleport.command.WarpCommand;
import top.imbring.bringteleport.config.ConfigManager;
import top.imbring.bringteleport.locale.LocaleManager;
import top.imbring.bringteleport.service.DeathBackManager;
import top.imbring.bringteleport.service.TeleportHistory;
import top.imbring.bringteleport.service.UpdateChecker;
import top.imbring.bringteleport.service.WarpManager;

public final class BringTeleportPlugin extends JavaPlugin implements Listener {

    private LocaleManager localeManager;
    private WarpManager warpManager;
    private TeleportHistory teleportHistory;
    private DeathBackManager deathBackManager;
    private UpdateChecker updateChecker;

    @Override
    public void onEnable() {
        // 强制 sqlite-jdbc 使用纯 Java 模式，避免从插件 JAR 中解压原生 DLL：
        // 在 Windows 上解压会导致 Paper 类加载器失去对 JAR 的访问，
        // 报出 zip file closed 错误
        System.setProperty("sqlite.purejava", "true");

        saveDefaultConfig();
        saveResource("locales.yml", false);
        ConfigManager.migrate(this);
        this.localeManager = new LocaleManager(this);
        this.warpManager = new WarpManager(this);
        this.teleportHistory = new TeleportHistory();
        this.deathBackManager = new DeathBackManager(this);
        this.updateChecker = new UpdateChecker(this);

        // 缓存有新版时，加入的玩家（有权限）收到更新提示
        getServer().getPluginManager().registerEvents(this, this);
        this.updateChecker.start();

        // 通过 Paper 生命周期事件注册命令
        getLifecycleManager().registerEventHandler(
            LifecycleEvents.COMMANDS,
            event -> CommandManager.register(event.registrar(), this)
        );

        getLogger().info(getPluginMeta().getVersion() + " has been enabled!");
    }

    @Override
    public void onDisable() {
        WarpCommand.cancelAllCountdowns();
        TpaCommand.cancelAllCountdowns();
        if (this.deathBackManager != null) {
            this.deathBackManager.shutdown();
        }
        if (this.warpManager != null) {
            this.warpManager.shutdown();
        }
        if (this.updateChecker != null) {
            this.updateChecker.shutdown();
        }
        getLogger().info("BringTeleport has been disabled!");
    }

    public LocaleManager getLocaleManager() {
        return this.localeManager;
    }

    public WarpManager getWarpManager() {
        return this.warpManager;
    }

    public TeleportHistory getTeleportHistory() {
        return this.teleportHistory;
    }

    public DeathBackManager getDeathBackManager() {
        return this.deathBackManager;
    }

    public UpdateChecker getUpdateChecker() {
        return this.updateChecker;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (this.updateChecker != null && this.updateChecker.hasCachedUpdate()
            && event.getPlayer().hasPermission("bringteleport.update")) {
            this.updateChecker.notifyPlayer(event.getPlayer());
        }
    }

    public void reload() {
        ConfigManager.migrate(this);
        reloadConfig();
        WarpCommand.refreshConfigCache(this);
        DeathBackCommand.refreshConfigCache(this);
        TpaCommand.refreshConfigCache(this);
        this.localeManager.reload();
    }
}
