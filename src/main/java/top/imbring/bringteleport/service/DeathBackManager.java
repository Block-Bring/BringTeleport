package top.imbring.bringteleport.service;

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
     * 记录死亡位置；死亡点危险（无法安全站立）时同步寻找最近安全点一并存储，
     * 找不到安全点则 safe 字段为 null（/back 时回退传死亡点）。
     */
    public void saveDeathLocation(Player player, Location location, int radiusH, int radiusV) {
        Double safeX = null, safeY = null, safeZ = null;
        Location safe = SafePointFinder.findSafePoint(location, radiusH, radiusV);
        if (safe != null && !sameSpot(safe, location)) {
            safeX = safe.getX();
            safeY = safe.getY();
            safeZ = safe.getZ();
        }

        try (PreparedStatement pstmt = getConnection().prepareStatement(UPSERT_SQL)) {
            pstmt.setString(1, player.getUniqueId().toString());
            pstmt.setString(2, location.getWorld() != null ? location.getWorld().getName() : "");
            pstmt.setDouble(3, location.getX());
            pstmt.setDouble(4, location.getY());
            pstmt.setDouble(5, location.getZ());
            pstmt.setFloat(6, location.getYaw());
            pstmt.setFloat(7, location.getPitch());
            if (safeX != null) {
                pstmt.setDouble(8, safeX);
                pstmt.setDouble(9, safeY);
                pstmt.setDouble(10, safeZ);
            } else {
                pstmt.setNull(8, java.sql.Types.REAL);
                pstmt.setNull(9, java.sql.Types.REAL);
                pstmt.setNull(10, java.sql.Types.REAL);
            }
            pstmt.executeUpdate();
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to save death location for " + player.getName(), e);
        }
    }

    // 安全点与死亡点同格时不视为"安全点"（死亡点本身安全，无需另存）
    private static boolean sameSpot(Location a, Location b) {
        return a.getBlockX() == b.getBlockX()
            && a.getBlockY() == b.getBlockY()
            && a.getBlockZ() == b.getBlockZ();
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
