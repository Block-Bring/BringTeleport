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
    private String prefixStr;

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

        this.prefixStr = this.locale.getString("bringteleport.prefix", "");

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
        String message = this.locale.getString(path);
        if (message == null) {
            return Component.text("Missing locale: " + path);
        }

        boolean hasPlaceholders = placeholders != null && !placeholders.isEmpty();
        // 含 {prefix} 的消息依赖前缀定义，前缀变化后随 reload 重建，不缓存
        boolean hasPrefixPlaceholder = message.contains("{prefix}");

        // Return cached component for messages without placeholders
        if (!hasPlaceholders && !hasPrefixPlaceholder) {
            Component cached = messageCache.get(path);
            if (cached != null) {
                return cached;
            }
        }

        if (hasPrefixPlaceholder) {
            message = message.replace("{prefix}", prefixStr);
        }

        if (hasPlaceholders) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                message = message.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }

        Component component = this.miniMessage.deserialize(message);

        // Cache only for messages without placeholders
        if (!hasPlaceholders && !hasPrefixPlaceholder) {
            messageCache.put(path, component);
        }

        return component;
    }

    public String getRaw(String path) {
        String message = this.locale.getString(path, "Missing locale: " + path);
        if (message.contains("{prefix}")) {
            message = message.replace("{prefix}", prefixStr);
        }
        return message;
    }
}
