package top.imbring.bringteleport.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public final class ConfigManager {

    private ConfigManager() {
    }

    /**
     * Merges missing keys from the bundled default config.yml and locales.yml
     * into the user's existing files, so new features work without manual deletion.
     */
    public static void migrate(JavaPlugin plugin) {
        migrateFile(plugin, "config.yml");
        migrateFile(plugin, "locales.yml");
    }

    private static void migrateFile(JavaPlugin plugin, String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            // User doesn't have this file yet — let the standard saveResource handle it
            plugin.saveResource(fileName, false);
            return;
        }

        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);

        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) return;

            YamlConfiguration defaults = new YamlConfiguration();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                defaults.load(reader);
            }

            if (mergeMissing(current, defaults)) {
                current.save(file);
                plugin.getLogger().info("Migrated " + fileName + " — added missing keys");
            }
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to migrate " + fileName, e);
        }
    }

    /**
     * Deep-merge: copies keys from {@code defaults} into {@code target} if
     * they don't already exist. Returns true if any keys were added.
     */
    private static boolean mergeMissing(ConfigurationSection target, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (target.contains(key)) {
                if (defaults.isConfigurationSection(key) && target.isConfigurationSection(key)) {
                    changed |= mergeMissing(
                        target.getConfigurationSection(key),
                        defaults.getConfigurationSection(key));
                }
            } else {
                target.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }
}
