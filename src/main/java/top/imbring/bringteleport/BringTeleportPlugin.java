package top.imbring.bringteleport;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;
import top.imbring.bringteleport.command.CommandManager;
import top.imbring.bringteleport.command.DeathBackCommand;
import top.imbring.bringteleport.command.WarpCommand;
import top.imbring.bringteleport.config.ConfigManager;
import top.imbring.bringteleport.locale.LocaleManager;
import top.imbring.bringteleport.service.DeathBackManager;
import top.imbring.bringteleport.service.TeleportHistory;
import top.imbring.bringteleport.service.WarpManager;

public final class BringTeleportPlugin extends JavaPlugin {

    private LocaleManager localeManager;
    private WarpManager warpManager;
    private TeleportHistory teleportHistory;
    private DeathBackManager deathBackManager;

    @Override
    public void onEnable() {
        // Force sqlite-jdbc to use pure Java mode — prevents native DLL
        // extraction from the plugin JAR, which can cause Paper's classloader
        // to lose access to the JAR on Windows (zip file closed error).
        System.setProperty("sqlite.purejava", "true");

        saveDefaultConfig();
        saveResource("locales.yml", false);
        ConfigManager.migrate(this);
        this.localeManager = new LocaleManager(this);
        this.warpManager = new WarpManager(this);
        this.teleportHistory = new TeleportHistory();
        this.deathBackManager = new DeathBackManager(this);

        // Register commands via Paper lifecycle
        getLifecycleManager().registerEventHandler(
            LifecycleEvents.COMMANDS,
            event -> CommandManager.register(event.registrar(), this)
        );

        getLogger().info(getPluginMeta().getVersion() + " has been enabled!");
    }

    @Override
    public void onDisable() {
        WarpCommand.cancelAllCountdowns();
        if (this.deathBackManager != null) {
            this.deathBackManager.shutdown();
        }
        if (this.warpManager != null) {
            this.warpManager.shutdown();
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

    public void reload() {
        ConfigManager.migrate(this);
        reloadConfig();
        WarpCommand.refreshConfigCache(this);
        DeathBackCommand.refreshConfigCache(this);
        this.localeManager.reload();
    }
}
