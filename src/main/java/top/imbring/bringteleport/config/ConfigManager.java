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
        boolean changed = false;

        // v2 迁移：waypoint 模块更名为 warp，旧键路径重命名（保留用户自定义值）
        if (current.contains("waypoint") && !current.contains("warp")) {
            current.set("warp", current.get("waypoint"));
            current.set("waypoint", null);
            changed = true;
        }

        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) return;

            YamlConfiguration defaults = new YamlConfiguration();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                defaults.load(reader);
            }

            // v0.7.1 迁移：info 模板新增 {stars_line} 占位符行（收藏数移入框内），
            // 旧模板缺失该占位符则整段替换为默认模板
            if (fileName.equals("locales.yml") && current.contains("warp.info.template")
                && defaults.contains("warp.info.template")) {
                String template = current.getString("warp.info.template");
                if (template != null && !template.contains("{stars_line}")) {
                    current.set("warp.info.template", defaults.getString("warp.info.template"));
                    changed = true;
                }
            }

            if (mergeMissing(current, defaults) || changed) {
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
