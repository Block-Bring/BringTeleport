package top.imbring.playerwaypoints.locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

public class LocaleManager {

    private final JavaPlugin plugin;
    private YamlConfiguration locale;
    private final MiniMessage miniMessage;

    public LocaleManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "locales.yml");
        plugin.saveResource("locales.yml", true);
        this.locale = YamlConfiguration.loadConfiguration(file);
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

        return this.miniMessage.deserialize(message);
    }

    public String getRaw(String path) {
        return this.locale.getString(path, "Missing locale: " + path);
    }
}
