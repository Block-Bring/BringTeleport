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
     * After merging, files are reordered to match the default key order, so keys
     * added by updates stay in their natural position instead of piling up at the end.
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

            // 整理键值顺序，使与默认配置一致（用户自定义的额外键保留在所属层级末尾）
            YamlConfiguration reordered = reorderToDefault(current, defaults);
            if (!reordered.saveToString().equals(current.saveToString())) {
                reordered.save(file);
                plugin.getLogger().info("Reordered " + fileName + " — keys matched default order");
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

    /**
     * Rebuilds {@code current} with keys ordered like {@code defaults} (deep).
     * Keys that only exist in {@code current} are kept, appended to the end of
     * their parent section, preserving their relative order.
     */
    private static YamlConfiguration reorderToDefault(YamlConfiguration current, YamlConfiguration defaults) {
        YamlConfiguration result = new YamlConfiguration();
        copyInOrder(result, defaults, current);
        return result;
    }

    private static void copyInOrder(ConfigurationSection target, ConfigurationSection defaults, ConfigurationSection source) {
        for (String key : defaults.getKeys(false)) {
            copyKey(target, defaults, source, key);
        }
        // 默认配置中没有的键（用户自定义）追加到所属层级末尾，保持原有相对顺序
        for (String key : source.getKeys(false)) {
            if (!defaults.contains(key)) {
                copyKey(target, defaults, source, key);
            }
        }
    }

    private static void copyKey(ConfigurationSection target, ConfigurationSection defaults, ConfigurationSection source, String key) {
        if (defaults.isConfigurationSection(key) && source.isConfigurationSection(key)) {
            ConfigurationSection child = target.createSection(key);
            copyInOrder(child, defaults.getConfigurationSection(key), source.getConfigurationSection(key));
        } else if (source.contains(key)) {
            target.set(key, source.get(key));
        } else {
            target.set(key, defaults.get(key));
        }
    }
}
