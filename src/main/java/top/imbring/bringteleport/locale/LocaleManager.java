package top.imbring.bringteleport.locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class LocaleManager {

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage;
    private final Map<String, Component> messageCache;
    private final Map<String, String> worldNames = new HashMap<>();
    private YamlConfiguration locale;
    private boolean prefixEnabled;
    private Component prefix;

    public LocaleManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.messageCache = new HashMap<>();
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "locales.yml");
        this.locale = YamlConfiguration.loadConfiguration(file);
        this.messageCache.clear();

        this.prefixEnabled = plugin.getConfig().getBoolean("prefix-enabled", true);
        String prefixStr = this.locale.getString("bringteleport.prefix", "");
        this.prefix = prefixStr.isEmpty() ? null : this.miniMessage.deserialize(prefixStr);

        this.worldNames.clear();
        ConfigurationSection section = this.locale.getConfigurationSection("world-names");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String display = section.getString(key);
                if (display != null && !display.isBlank()) {
                    this.worldNames.put(key, display);
                }
            }
        }
    }

    // 世界显示名：有映射返回映射名，否则返回世界原名
    public String getWorldName(String world) {
        return worldNames.getOrDefault(world, world);
    }

    public Component getMessage(String path, Map<String, String> placeholders) {
        // Return cached component for messages without placeholders
        if (placeholders == null || placeholders.isEmpty()) {
            Component cached = messageCache.get(path);
            if (cached != null) {
                return prefixEnabled && prefix != null
                    ? prefix.append(Component.space()).append(cached)
                    : cached;
            }
        }

        String message = this.locale.getString(path);
        if (message == null) {
            return Component.text("Missing locale: " + path);
        }

        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                message = message.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }

        Component component = this.miniMessage.deserialize(message);

        // Cache only for messages without placeholders
        if (placeholders == null || placeholders.isEmpty()) {
            messageCache.put(path, component);
        }

        if (prefixEnabled && prefix != null) {
            component = prefix.append(Component.space()).append(component);
        }

        return component;
    }

    public String getRaw(String path) {
        return this.locale.getString(path, "Missing locale: " + path);
    }
}
