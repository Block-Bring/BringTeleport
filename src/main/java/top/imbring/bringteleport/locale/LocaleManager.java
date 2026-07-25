package top.imbring.bringteleport.locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

public class LocaleManager {

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage;
    private YamlConfiguration locale;
    private boolean prefixEnabled;
    private Component prefix;

    public LocaleManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "locales.yml");
        this.locale = YamlConfiguration.loadConfiguration(file);

        this.prefixEnabled = plugin.getConfig().getBoolean("prefix-enabled", true);
        String prefixStr = this.locale.getString("bringteleport.prefix", "");
        this.prefix = prefixStr.isEmpty() ? null : this.miniMessage.deserialize(prefixStr);
    }

    public Component getMessage(String path, Map<String, String> placeholders) {
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

        if (prefixEnabled && prefix != null) {
            component = prefix.append(Component.space()).append(component);
        }

        return component;
    }

    public String getRaw(String path) {
        return this.locale.getString(path, "Missing locale: " + path);
    }
}
