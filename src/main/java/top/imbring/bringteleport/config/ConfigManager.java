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

        // v3 迁移：prefix-enabled 开关移除，前缀改为各消息内的 {prefix} 占位符
        if (fileName.equals("config.yml") && current.contains("prefix-enabled")) {
            current.set("prefix-enabled", null);
            changed = true;
        }

        // v5 迁移：update-checker.enabled 拆分为 check-on-startup 与 auto-check.enabled
        // （原开关值同步到两者，保留用户关闭/开启的意图）
        if (fileName.equals("config.yml") && current.contains("update-checker.enabled")) {
            boolean enabled = current.getBoolean("update-checker.enabled", true);
            if (!current.contains("update-checker.check-on-startup")) {
                current.set("update-checker.check-on-startup", enabled);
            }
            if (!current.contains("update-checker.auto-check.enabled")) {
                current.set("update-checker.auto-check.enabled", enabled);
            }
            current.set("update-checker.enabled", null);
            changed = true;
        }

        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) return;

            YamlConfiguration defaults = new YamlConfiguration();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                defaults.load(reader);
            }

            if (fileName.equals("locales.yml")) {
                // v3 迁移：旧消息无前缀，给默认模板含 {prefix} 的键补上前缀占位符（保留用户自定义内容）
                changed |= addPrefixPlaceholder(current, defaults);

                // v3 迁移：清理历史版本写入消息内容的渲染前缀（如 <aqua>[<green>BringTeleport<aqua>]），
                // 该键整体替换为默认模板（被污染的旧文案本已过时）
                String prefixDef = defaults.getString("bringteleport.prefix", "");
                changed |= cleanupRenderedPrefix(current, defaults, prefixDef);

                // v3 迁移：去除块标量开头的空行。SnakeYAML 保存以空行开头的多行字符串时会
                // 写成 |2 缩进指示符（合法但难看），新默认模板已无空首行，老文件在此对齐
                changed |= trimLeadingBlankLines(current, defaults);
            }

            // 迁移：移除 {stars_line} 特殊占位符机制，收藏行内联为公有模板的常规行。
            // 旧模板含 {stars_line}（或仍引用旧 stars 键）则整段替换为新默认公有模板，
            // 并清理已废弃的 warp.info.stars 键
            if (fileName.equals("locales.yml") && current.contains("warp.info.template")
                && defaults.contains("warp.info.template")) {
                String template = current.getString("warp.info.template");
                if (template != null && template.contains("{stars_line}")) {
                    current.set("warp.info.template", defaults.getString("warp.info.template"));
                    changed = true;
                }
            }
            if (fileName.equals("locales.yml") && current.contains("warp.info.stars")) {
                current.set("warp.info.stars", null);
                changed = true;
            }

            // v0.8.0 迁移：warp 传送成功 subtitle 新增坐标占位符（{world} {x} {y} {z}），
            // 旧模板缺失 {world} 则替换为默认模板（tpa 为新增段，默认即新模板）
            if (fileName.equals("locales.yml") && current.contains("warp.tp.success.subtitle")
                && defaults.contains("warp.tp.success.subtitle")) {
                String subtitle = current.getString("warp.tp.success.subtitle");
                if (subtitle != null && !subtitle.contains("{world}")) {
                    current.set("warp.tp.success.subtitle", defaults.getString("warp.tp.success.subtitle"));
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
     * Prepend {@code {prefix} } to user messages whose default template contains
     * {@code {prefix}} but the user's copy doesn't yet. Keeps user-customized
     * content intact. Returns true if any keys were modified.
     */
    private static boolean addPrefixPlaceholder(ConfigurationSection target, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (defaults.isConfigurationSection(key) && target.isConfigurationSection(key)) {
                changed |= addPrefixPlaceholder(
                    target.getConfigurationSection(key),
                    defaults.getConfigurationSection(key));
            } else if (defaults.isString(key) && target.isString(key)) {
                String def = defaults.getString(key);
                String cur = target.getString(key);
                if (def != null && def.contains("{prefix}") && cur != null && !cur.contains("{prefix}")) {
                    // 内容以换行开头（块标量空首行）时不加空格，避免行尾空格导致 SnakeYAML 改用双引号风格
                    target.set(key, cur.startsWith("\n") ? "{prefix}" + cur : "{prefix} " + cur);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Replaces user message values that contain the rendered prefix text
     * (historical versions wrote the rendered prefix into locale content)
     * with the default template. Returns true if any keys were modified.
     */
    private static boolean cleanupRenderedPrefix(ConfigurationSection target, ConfigurationSection defaults, String prefixDef) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (defaults.isConfigurationSection(key) && target.isConfigurationSection(key)) {
                changed |= cleanupRenderedPrefix(
                    target.getConfigurationSection(key),
                    defaults.getConfigurationSection(key),
                    prefixDef);
            } else if (defaults.isString(key) && target.isString(key) && !prefixDef.isEmpty()) {
                String def = defaults.getString(key);
                String cur = target.getString(key);
                if (def != null && cur != null && cur.contains(prefixDef)) {
                    target.set(key, def);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Removes leading blank lines from user message values whose default
     * template has none, so SnakeYAML won't write them back as {@code |2}
     * indentation indicators. Returns true if any keys were modified.
     */
    private static boolean trimLeadingBlankLines(ConfigurationSection target, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            if (defaults.isConfigurationSection(key) && target.isConfigurationSection(key)) {
                changed |= trimLeadingBlankLines(
                    target.getConfigurationSection(key),
                    defaults.getConfigurationSection(key));
            } else if (defaults.isString(key) && target.isString(key)) {
                String def = defaults.getString(key);
                String cur = target.getString(key);
                if (def != null && !def.startsWith("\n") && cur != null && cur.startsWith("\n")) {
                    target.set(key, cur.replaceFirst("^\n+", ""));
                    changed = true;
                }
            }
        }
        return changed;
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
