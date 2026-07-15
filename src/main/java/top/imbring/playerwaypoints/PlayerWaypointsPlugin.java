package top.imbring.playerwaypoints;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;
import top.imbring.playerwaypoints.command.CommandManager;
import top.imbring.playerwaypoints.locale.LocaleManager;
import top.imbring.playerwaypoints.service.TeleportHistory;
import top.imbring.playerwaypoints.service.WaypointManager;

public final class PlayerWaypointsPlugin extends JavaPlugin {

    private LocaleManager localeManager;
    private WaypointManager waypointManager;
    private TeleportHistory teleportHistory;

    @Override
    public void onEnable() {
        // Force sqlite-jdbc to use pure Java mode — prevents native DLL
        // extraction from the plugin JAR, which can cause Paper's classloader
        // to lose access to the JAR on Windows (zip file closed error).
        System.setProperty("sqlite.purejava", "true");

        saveDefaultConfig();
        this.localeManager = new LocaleManager(this);
        this.waypointManager = new WaypointManager(this);
        this.teleportHistory = new TeleportHistory();

        // Register commands via Paper lifecycle
        getLifecycleManager().registerEventHandler(
            LifecycleEvents.COMMANDS,
            event -> CommandManager.register(event.registrar(), this)
        );

        getLogger().info(getPluginMeta().getVersion() + " has been enabled!");
    }

    @Override
    public void onDisable() {
        if (this.waypointManager != null) {
            this.waypointManager.shutdown();
        }
        getLogger().info("PlayerWaypoints has been disabled!");
    }

    public LocaleManager getLocaleManager() {
        return this.localeManager;
    }

    public WaypointManager getWaypointManager() {
        return this.waypointManager;
    }

    public TeleportHistory getTeleportHistory() {
        return this.teleportHistory;
    }

    public void reload() {
        reloadConfig();
        this.localeManager.reload();
    }
}
