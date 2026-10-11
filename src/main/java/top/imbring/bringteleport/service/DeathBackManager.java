package top.imbring.bringteleport.service;

import top.imbring.bringteleport.BringTeleportPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 死亡点存储：每个玩家仅保留最近一次死亡位置，使用一次即清除。
 */
public class DeathBackManager {

    // 不用 UPSERT（ON CONFLICT ... DO UPDATE）：sqlite.purejava 模式下
    // 内置的 NestedVM SQLite 版本过旧（3.8.x），INSERT OR REPLACE 全版本可用
    private static final String UPSERT_SQL = """
        INSERT OR REPLACE INTO death_backs (player_uuid, world, x, y, z, yaw, pitch, safe_x, safe_y, safe_z, dangerous)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    // 安全点可空：死亡点危险时找到的最近安全落脚点，找不到则为 null（回退传死亡点）。
    // dangerous=true 表示死亡点本身危险（如岩浆/虚空）且未找到安全点，/back 需 confirm 确认
    public record DeathRecord(String world, double x, double y, double z, float yaw, float pitch,
                              Double safeX, Double safeY, Double safeZ, boolean dangerous) {}

    private final BringTeleportPlugin plugin;
    private final String dbUrl;
    private Connection connection;

    // 进行中的异步安全点搜索：/back 需等待其完成再读取记录，避免落回危险死亡点
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> pendingSearches = new ConcurrentHashMap<>();

    public DeathBackManager(BringTeleportPlugin plugin) {
        this.plugin = plugin;
        File dbFile = new File(plugin.getDataFolder(), "data.db");
        this.dbUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        ensureSchema();
    }

    private synchronized Connection getConnection() throws SQLException {
        if (this.connection == null || this.connection.isClosed()) {
            this.connection = DriverManager.getConnection(this.dbUrl);
        }
        return this.connection;
    }

    private void ensureSchema() {
        try {
            Connection conn = getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS death_backs (
                        player_uuid TEXT PRIMARY KEY,
                        world TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        yaw REAL DEFAULT 0,
                        pitch REAL DEFAULT 0,
                        safe_x REAL,
                        safe_y REAL,
                        safe_z REAL,
                        dangerous INTEGER DEFAULT 0
                    )
                """);
                // 旧表（无安全点/危险标志列）迁移：SQLite 不支持 ADD COLUMN IF NOT EXISTS，需查列
                try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(death_backs)")) {
                    boolean hasSafeX = false;
                    boolean hasDangerous = false;
                    while (rs.next()) {
                        String name = rs.getString("name");
                        if ("safe_x".equals(name)) {
                            hasSafeX = true;
                        } else if ("dangerous".equals(name)) {
                            hasDangerous = true;
                        }
                    }
                    if (!hasSafeX) {
                        stmt.execute("ALTER TABLE death_backs ADD COLUMN safe_x REAL");
                        stmt.execute("ALTER TABLE death_backs ADD COLUMN safe_y REAL");
                        stmt.execute("ALTER TABLE death_backs ADD COLUMN safe_z REAL");
                        this.plugin.getLogger().info("Migrated death_backs table — added safe point columns");
                    }
                    if (!hasDangerous) {
                        stmt.execute("ALTER TABLE death_backs ADD COLUMN dangerous INTEGER DEFAULT 0");
                        this.plugin.getLogger().info("Migrated death_backs table — added dangerous column");
                    }
                }
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to initialize death back database", e);
        }
    }

    /**
     * 记录死亡位置（不搜索安全点）。
     * 安全点搜索关闭、或死亡点本身安全时使用；safe 列保持 NULL，/back 回退传死亡点。
     *
     * @param dangerous 死亡点本身危险（如岩浆/虚空）且不会去搜索安全点，/back 需 confirm 确认
     */
    public void saveDeathLocation(Player player, Location location, boolean dangerous) {
        try (PreparedStatement pstmt = getConnection().prepareStatement(UPSERT_SQL)) {
            pstmt.setString(1, player.getUniqueId().toString());
            pstmt.setString(2, location.getWorld() != null ? location.getWorld().getName() : "");
            pstmt.setDouble(3, location.getX());
            pstmt.setDouble(4, location.getY());
            pstmt.setDouble(5, location.getZ());
            pstmt.setFloat(6, location.getYaw());
            pstmt.setFloat(7, location.getPitch());
            pstmt.setNull(8, java.sql.Types.REAL);
            pstmt.setNull(9, java.sql.Types.REAL);
            pstmt.setNull(10, java.sql.Types.REAL);
            pstmt.setInt(11, dangerous ? 1 : 0);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to save death location for " + player.getName(), e);
        }
    }

    /**
     * 记录死亡位置并异步搜索安全点：先立刻落库（safe 列空，/back 立即可用），
     * 搜索在主线程外完成，找到安全点后回到主线程回填，
     * 避免把大范围方块搜索放在死亡事件的服务器主线程上。
     * 搜索失败（找不到安全点）时保持 dangerous 标志并提示玩家返回需确认。
     */
    public void saveDeathLocationWithSafePointAsync(Player player, Location location, int radiusH, int radiusV, int effort) {
        saveDeathLocation(player, location, true);
        UUID uuid = player.getUniqueId();
        if (location.getWorld() == null) return;

        CompletableFuture<Void> done = new CompletableFuture<>();
        pendingSearches.put(uuid, done);

        SafePointFinder.findSafePointAsync(location, radiusH, radiusV, effort).whenComplete((safe, error) -> {
            if (!this.plugin.isEnabled()) {
                // 插件已停用：无法再调度主线程任务，直接收尾
                pendingSearches.remove(uuid, done);
                done.complete(null);
                return;
            }
            // 回填放回主线程，与主线程上的读/清记录串行化，避免 SQLite 连接并发访问
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                try {
                    // 搜索期间玩家可能再次死亡并覆盖记录：本次搜索的结果与提示都不该再生效
                    if (!isCurrentRecord(uuid, location)) return;
                    if (safe != null) {
                        updateSafePoint(uuid, safe);
                    } else {
                        // 通用搜索失败：末地掉入虚空时走专属兜底（垂直找 Y 0~128，找不到生成平台）
                        World world = location.getWorld();
                        Location fallback = null;
                        if (world != null && world.getEnvironment() == World.Environment.THE_END) {
                            fallback = SafePointFinder.handleEndVoid(
                                world, location.getBlockX(), location.getBlockZ(), location);
                            if (fallback != null) {
                                updateSafePoint(uuid, fallback);
                            }
                        }
                        if (fallback == null) {
                            Player p = Bukkit.getPlayer(uuid);
                            if (p != null && p.isOnline()) {
                                p.sendMessage(this.plugin.getLocaleManager()
                                    .getMessage("deathback.safe-point-not-found", null));
                            }
                        }
                    }
                } finally {
                    pendingSearches.remove(uuid, done);
                    done.complete(null);
                }
            });
        });
    }

    // 记录是否仍指向本次死亡点：搜索期间玩家可能再次死亡并覆盖记录。
    // 判定与回填都在主线程，读到的记录不会被并发修改
    private boolean isCurrentRecord(UUID uuid, Location origin) {
        Optional<DeathRecord> record = getDeathRecord(uuid);
        if (record.isEmpty()) return false;
        DeathRecord current = record.get();
        String world = origin.getWorld() != null ? origin.getWorld().getName() : "";
        return current.world().equals(world)
            && Double.compare(current.x(), origin.getX()) == 0
            && Double.compare(current.y(), origin.getY()) == 0
            && Double.compare(current.z(), origin.getZ()) == 0;
    }

    // 安全点回填；调用前需已确认记录仍属本次死亡，找到安全点后危险标志清除，/back 无需再确认。
    private void updateSafePoint(UUID uuid, Location safe) {
        String sql = """
            UPDATE death_backs SET safe_x = ?, safe_y = ?, safe_z = ?, dangerous = 0
            WHERE player_uuid = ?
            """;
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setDouble(1, safe.getX());
            pstmt.setDouble(2, safe.getY());
            pstmt.setDouble(3, safe.getZ());
            pstmt.setString(4, uuid.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to update safe point for " + uuid, e);
        }
    }

    /**
     * 返回该玩家进行中的安全点搜索（若有）；/back 需等待其完成再读取记录传送。
     */
    public CompletableFuture<Void> getPendingSearch(UUID playerUuid) {
        return pendingSearches.get(playerUuid);
    }

    public Optional<DeathRecord> getDeathRecord(UUID playerUuid) {
        String sql = "SELECT world, x, y, z, yaw, pitch, safe_x, safe_y, safe_z, dangerous FROM death_backs WHERE player_uuid = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Double safeX = rs.getObject("safe_x") != null ? rs.getDouble("safe_x") : null;
                    Double safeY = rs.getObject("safe_y") != null ? rs.getDouble("safe_y") : null;
                    Double safeZ = rs.getObject("safe_z") != null ? rs.getDouble("safe_z") : null;
                    return Optional.of(new DeathRecord(
                        rs.getString("world"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getFloat("pitch"),
                        safeX, safeY, safeZ,
                        rs.getInt("dangerous") != 0));
                }
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to get death location for " + playerUuid, e);
        }
        return Optional.empty();
    }

    /**
     * 清除死亡记录；仅当记录仍指向 {@code expected} 那次死亡时生效。
     * 传送是异步的，期间玩家可能再次死亡并写入新记录，此时不能把新记录一并删掉。
     */
    public void clearIfCurrent(UUID playerUuid, DeathRecord expected) {
        String sql = """
            DELETE FROM death_backs
            WHERE player_uuid = ? AND world = ? AND x = ? AND y = ? AND z = ?
            """;
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
            pstmt.setString(2, expected.world());
            pstmt.setDouble(3, expected.x());
            pstmt.setDouble(4, expected.y());
            pstmt.setDouble(5, expected.z());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to clear death location for " + playerUuid, e);
        }
    }

    public synchronized void shutdown() {
        // 收尾挂起的搜索，避免 /back 的等待回调在插件停用后无处落地
        pendingSearches.values().forEach(future -> future.complete(null));
        pendingSearches.clear();
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (SQLException e) {
                this.plugin.getLogger().log(Level.SEVERE, "Failed to close death back database connection", e);
            }
            this.connection = null;
        }
    }
}
