package top.imbring.bringteleport.service;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import top.imbring.bringteleport.BringTeleportPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 GitHub Releases 检查插件更新。所有检查都在异步线程进行，
 * 结果缓存 6 小时（GitHub API 未认证限流 60 次/小时），缓存用于玩家加入时的提示复用。
 */
public final class UpdateChecker {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final long CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L;
    private static final Pattern TAG_NAME = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");

    public record CheckResult(String latestVersion, boolean hasUpdate, boolean failed) {
        static CheckResult failure() {
            return new CheckResult(null, false, true);
        }
    }

    private final BringTeleportPlugin plugin;
    private final HttpClient httpClient;

    private volatile String latestVersion;
    private volatile long lastCheckAt;
    private BukkitTask periodicTask;

    public UpdateChecker(BringTeleportPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * 启动时检查一次（check-on-startup），并注册自动检查任务
     * （auto-check.enabled，间隔 interval-hours 小时）。
     */
    public void start() {
        if (checkOnStartup()) {
            checkAsync(this::announce);
        }
        if (autoCheckEnabled()) {
            long intervalTicks = Math.max(1, intervalHours()) * 3_600_000L / 50;
            this.periodicTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, () -> checkAsync(this::announce), intervalTicks, intervalTicks);
        }
    }

    public void shutdown() {
        if (this.periodicTask != null) {
            this.periodicTask.cancel();
        }
    }

    /** 手动检查（命令用）：结果通过本地化消息回报给执行者。 */
    public void checkFor(CommandSender sender) {
        checkAsync(result -> {
            if (result.failed()) {
                sender.sendMessage(plugin.getLocaleManager().getMessage("update.check-failed", null));
            } else if (result.hasUpdate()) {
                sender.sendMessage(plugin.getLocaleManager().getMessage("update.found", Map.of(
                    "current", currentVersion(),
                    "latest", result.latestVersion())));
            } else {
                sender.sendMessage(plugin.getLocaleManager().getMessage("update.up-to-date", Map.of(
                    "current", currentVersion())));
            }
        });
    }

    /** 缓存新鲜且存在新版本时返回 true（玩家加入时提示用）。 */
    public boolean hasCachedUpdate() {
        String latest = this.latestVersion;
        return latest != null
            && System.currentTimeMillis() - this.lastCheckAt < CACHE_TTL_MILLIS
            && compareVersions(latest, currentVersion()) > 0;
    }

    public void notifyPlayer(Player player) {
        String latest = this.latestVersion;
        if (latest == null) {
            return;
        }
        player.sendMessage(plugin.getLocaleManager().getMessage("update.found", Map.of(
            "current", currentVersion(),
            "latest", latest)));
    }

    /**
     * 异步检查最新版本；缓存新鲜时直接复用。回调在主线程执行，
     * 任何情况下都会调用（失败时 failed=true）。
     */
    private void checkAsync(Consumer<CheckResult> callback) {
        String cached = this.latestVersion;
        if (cached != null && System.currentTimeMillis() - this.lastCheckAt < CACHE_TTL_MILLIS) {
            notify(callback, of(cached));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            CheckResult result = fetch();
            if (!result.failed()) {
                this.latestVersion = result.latestVersion();
                this.lastCheckAt = System.currentTimeMillis();
            }
            notify(callback, result);
        });
    }

    private void notify(Consumer<CheckResult> callback, CheckResult result) {
        if (callback == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
    }

    private CheckResult fetch() {
        String url = plugin.getConfig().getString(
            "update-checker.url",
            "https://api.github.com/repos/Block-Bring/BringTeleport/releases/latest");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "BringTeleport/" + currentVersion())
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                plugin.getLogger().log(Level.WARNING,
                    "Update check failed: HTTP " + response.statusCode());
                return CheckResult.failure();
            }
            String tag = parseTagName(response.body());
            if (tag == null) {
                plugin.getLogger().log(Level.WARNING, "Update check failed: no tag_name in response");
                return CheckResult.failure();
            }
            return of(tag);
        } catch (IOException | InterruptedException e) {
            plugin.getLogger().log(Level.WARNING, "Update check failed: " + e.getMessage());
            return CheckResult.failure();
        }
    }

    private CheckResult of(String latest) {
        return new CheckResult(latest, compareVersions(latest, currentVersion()) > 0, false);
    }

    /** 启动/周期检查的默认处理：有新版本时警告日志并通知在线有权限玩家。 */
    private void announce(CheckResult result) {
        if (result.failed()) {
            return;
        }
        if (result.hasUpdate()) {
            plugin.getLogger().warning("A new version is available: " + result.latestVersion()
                + " (current: " + currentVersion() + ")");
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("bringteleport.update")) {
                    notifyPlayer(player);
                }
            }
        }
    }

    private boolean checkOnStartup() {
        return plugin.getConfig().getBoolean("update-checker.check-on-startup", true);
    }

    private boolean autoCheckEnabled() {
        return plugin.getConfig().getBoolean("update-checker.auto-check.enabled", true);
    }

    private long intervalHours() {
        return plugin.getConfig().getLong("update-checker.auto-check.interval-hours", 6);
    }

    private String currentVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    private static String parseTagName(String body) {
        Matcher matcher = TAG_NAME.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 比较两个版本号（如 "0.8.0"、"v1.2.3-beta"）。去掉 v 前缀后按 . 分段，
     * 每段取前导数字比较（beta/alpha 等后缀忽略），a &gt; b 返回正数。
     */
    static int compareVersions(String a, String b) {
        String[] partsA = normalize(a).split("\\.");
        String[] partsB = normalize(b).split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            long x = i < partsA.length ? leadingNumber(partsA[i]) : 0;
            long y = i < partsB.length ? leadingNumber(partsB[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    private static String normalize(String version) {
        return (version.startsWith("v") || version.startsWith("V")) ? version.substring(1) : version;
    }

    private static long leadingNumber(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Long.parseLong(part.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
