package top.imbring.bringteleport.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

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
        INSERT OR REPLACE INTO death_backs (player_uuid, world, x, y, z, yaw, pitch, safe_x, safe_y, safe_z)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    // 安全点可空：死亡点危险时找到的最近安全落脚点，找不到则为 null（回退传死亡点）
    public record DeathRecord(String world, double x, double y, double z, float yaw, float pitch,
                              Double safeX, Double safeY, Double safeZ) {}

    private final JavaPlugin plugin;
    private final String dbUrl;
    private Connection connection;

    // 进行中的异步安全点搜索：/back 需等待其完成再读取记录，避免落回危险死亡点
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> pendingSearches = new ConcurrentHashMap<>();

    public DeathBackManager(JavaPlugin plugin) {
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
                        died_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);
                // 旧表（无安全点列）迁移：SQLite 不支持 ADD COLUMN IF NOT EXISTS，需查列
                try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(death_backs)")) {
                    boolean hasSafeX = false;
                    while (rs.next()) {
                        if ("safe_x".equals(rs.getString("name"))) {
                            hasSafeX = true;
                            break;
                        }
                    }
                    if (!hasSafeX) {
                        stmt.execute("ALTER TABLE death_backs ADD COLUMN safe_x REAL");
                        stmt.execute("ALTER TABLE death_backs ADD COLUMN safe_y REAL");
                        stmt.execute("ALTER TABLE death_backs ADD COLUMN safe_z REAL");
                        this.plugin.getLogger().info("Migrated death_backs table — added safe point columns");
                    }
                }
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to initialize death back database", e);
        }
    }

    /**
     * 记录死亡位置（不搜索安全点）。
     * 安全点开关关闭、或死亡点本身安全时使用；safe 列保持 NULL，/back 回退传死亡点。
     */
    public void saveDeathLocation(Player player, Location location) {
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
            pstmt.executeUpdate();
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to save death location for " + player.getName(), e);
        }
    }

    /**
     * 记录死亡位置并异步搜索安全点：先立刻落库（safe 列空，/back 立即可用），
     * 搜索在主线程外完成，找到安全点后回到主线程回填，
     * 避免把大范围方块搜索放在死亡事件的服务器主线程上。
     */
    public void saveDeathLocationWithSafePointAsync(Player player, Location location, int radiusH, int radiusV) {
        saveDeathLocation(player, location);
        UUID uuid = player.getUniqueId();
        if (location.getWorld() == null) return;

        CompletableFuture<Void> done = new CompletableFuture<>();
        pendingSearches.put(uuid, done);

        SafePointFinder.findSafePointAsync(location, radiusH, radiusV).whenComplete((safe, error) -> {
            if (!this.plugin.isEnabled()) {
                // 插件已停用：无法再调度主线程任务，直接收尾
                pendingSearches.remove(uuid, done);
                done.complete(null);
                return;
            }
            // 回填放回主线程，与主线程上的读/清记录串行化，避免 SQLite 连接并发访问
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                try {
                    if (safe != null) {
                        updateSafePoint(uuid, safe, location);
                    }
                } finally {
                    pendingSearches.remove(uuid, done);
                    done.complete(null);
                }
            });
        });
    }

    // 安全点回填；期间玩家可能再次死亡覆盖记录，仅当记录仍指向本次死亡点时生效
    private void updateSafePoint(UUID uuid, Location safe, Location origin) {
        String sql = """
            UPDATE death_backs SET safe_x = ?, safe_y = ?, safe_z = ?
            WHERE player_uuid = ? AND world = ? AND x = ? AND y = ? AND z = ?
            """;
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setDouble(1, safe.getX());
            pstmt.setDouble(2, safe.getY());
            pstmt.setDouble(3, safe.getZ());
            pstmt.setString(4, uuid.toString());
            pstmt.setString(5, origin.getWorld() != null ? origin.getWorld().getName() : "");
            pstmt.setDouble(6, origin.getX());
            pstmt.setDouble(7, origin.getY());
            pstmt.setDouble(8, origin.getZ());
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
        String sql = "SELECT world, x, y, z, yaw, pitch, safe_x, safe_y, safe_z FROM death_backs WHERE player_uuid = ?";
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
                        safeX, safeY, safeZ));
                }
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to get death location for " + playerUuid, e);
        }
        return Optional.empty();
    }

    public void clear(UUID playerUuid) {
        String sql = "DELETE FROM death_backs WHERE player_uuid = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, playerUuid.toString());
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
