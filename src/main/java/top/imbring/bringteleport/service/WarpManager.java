package top.imbring.bringteleport.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import top.imbring.bringteleport.model.Warp;
import top.imbring.bringteleport.model.Warp.WarpType;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class WarpManager {

    private final JavaPlugin plugin;
    private final String dbUrl;
    private Connection connection;
    private boolean schemaReady;

    public WarpManager(JavaPlugin plugin) {
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
                // v2 迁移：waypoint 模块更名为 warp，旧表 waypoints 重命名为 warps
                try (ResultSet rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='waypoints'")) {
                    if (rs.next()) {
                        stmt.execute("DROP INDEX IF EXISTS idx_waypoint_public_name");
                        stmt.execute("DROP INDEX IF EXISTS idx_waypoint_private_name");
                        stmt.execute("ALTER TABLE waypoints RENAME TO warps");
                        this.plugin.getLogger().info("Migrated database table waypoints -> warps");
                    }
                }

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS warps (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        yaw REAL DEFAULT 0,
                        pitch REAL DEFAULT 0,
                        type TEXT NOT NULL CHECK(type IN ('PUBLIC','PRIVATE')),
                        owner_uuid TEXT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                """);

                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_warp_public_name " +
                    "ON warps(name) WHERE type = 'PUBLIC'");

                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_warp_private_name " +
                    "ON warps(name, owner_uuid) WHERE type = 'PRIVATE'");
            }
            this.schemaReady = true;
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to initialize database", e);
        }
    }

    /**
     * Add a new warp.
     * @return true if successful, false if name already exists
     */
    public boolean addWarp(Warp warp) {
        String sql = "INSERT INTO warps (name, world, x, y, z, yaw, pitch, type, owner_uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, warp.getName());
            pstmt.setString(2, warp.getWorld());
            pstmt.setDouble(3, warp.getX());
            pstmt.setDouble(4, warp.getY());
            pstmt.setDouble(5, warp.getZ());
            pstmt.setFloat(6, warp.getYaw());
            pstmt.setFloat(7, warp.getPitch());
            pstmt.setString(8, warp.getType().name());
            if (warp.getOwnerUuid() != null) {
                pstmt.setString(9, warp.getOwnerUuid().toString());
            } else {
                pstmt.setNull(9, java.sql.Types.VARCHAR);
            }
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                return false;
            }
            this.plugin.getLogger().log(Level.SEVERE, "Failed to add warp", e);
            return false;
        }
    }

    /**
     * Delete a warp by name and type.
     * @return true if deleted, false if not found
     */
    public boolean deleteWarp(String name, WarpType type, UUID ownerUuid) {
        String sql;
        if (type == WarpType.PUBLIC) {
            sql = "DELETE FROM warps WHERE name = ? AND type = 'PUBLIC'";
        } else {
            sql = "DELETE FROM warps WHERE name = ? AND type = 'PRIVATE' AND owner_uuid = ?";
        }

        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, name);
            if (type == WarpType.PRIVATE) {
                pstmt.setString(2, ownerUuid.toString());
            }
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to delete warp", e);
            return false;
        }
    }

    /**
     * Rename a warp.
     * @return true if renamed, false if not found or new name conflicts with an existing warp
     */
    public boolean renameWarp(String name, WarpType type, UUID ownerUuid, String newName) {
        String sql;
        if (type == WarpType.PUBLIC) {
            sql = "UPDATE warps SET name = ? WHERE name = ? AND type = 'PUBLIC'";
        } else {
            sql = "UPDATE warps SET name = ? WHERE name = ? AND type = 'PRIVATE' AND owner_uuid = ?";
        }

        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, newName);
            pstmt.setString(2, name);
            if (type == WarpType.PRIVATE) {
                pstmt.setString(3, ownerUuid.toString());
            }
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                return false;
            }
            this.plugin.getLogger().log(Level.SEVERE, "Failed to rename warp", e);
            return false;
        }
    }

    /**
     * Get a warp by name and type for a player.
     * For PUBLIC: just name
     * For PRIVATE: name + ownerUuid
     */
    public Optional<Warp> getWarp(String name, WarpType type, UUID ownerUuid) {
        String sql;
        if (type == WarpType.PUBLIC) {
            sql = "SELECT * FROM warps WHERE name = ? AND type = 'PUBLIC'";
        } else {
            sql = "SELECT * FROM warps WHERE name = ? AND type = 'PRIVATE' AND owner_uuid = ?";
        }

        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, name);
            if (type == WarpType.PRIVATE) {
                pstmt.setString(2, ownerUuid.toString());
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to get warp", e);
        }
        return Optional.empty();
    }

    /**
     * List all public warps.
     */
    public List<Warp> getPublicWarps() {
        List<Warp> warps = new ArrayList<>();
        String sql = "SELECT * FROM warps WHERE type = 'PUBLIC' ORDER BY name";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                warps.add(mapRow(rs));
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to list public warps", e);
        }
        return warps;
    }

    /**
     * List all players who own at least one private warp.
     */
    public List<UUID> getPrivateWarpOwners() {
        List<UUID> owners = new ArrayList<>();
        String sql = "SELECT DISTINCT owner_uuid FROM warps WHERE type = 'PRIVATE' AND owner_uuid IS NOT NULL";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                owners.add(UUID.fromString(rs.getString("owner_uuid")));
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to list private warp owners", e);
        }
        return owners;
    }

    /**
     * List all private warps for a player.
     */
    public List<Warp> getPrivateWarps(UUID ownerUuid) {
        List<Warp> warps = new ArrayList<>();
        String sql = "SELECT * FROM warps WHERE type = 'PRIVATE' AND owner_uuid = ? ORDER BY name";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, ownerUuid.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    warps.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to list private warps", e);
        }
        return warps;
    }

    /**
     * Convert a Warp to a Bukkit Location.
     */
    public Location toLocation(Warp warp) {
        World world = Bukkit.getWorld(warp.getWorld());
        if (world == null) {
            return null;
        }
        return new Location(world, warp.getX(), warp.getY(), warp.getZ(),
            warp.getYaw(), warp.getPitch());
    }

    public synchronized void shutdown() {
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (SQLException e) {
                this.plugin.getLogger().log(Level.SEVERE, "Failed to close database connection", e);
            }
            this.connection = null;
        }
    }

    private Warp mapRow(ResultSet rs) throws SQLException {
        String ownerUuidStr = rs.getString("owner_uuid");
        UUID ownerUuid = ownerUuidStr != null ? UUID.fromString(ownerUuidStr) : null;
        return new Warp(
            rs.getInt("id"),
            rs.getString("name"),
            rs.getString("world"),
            rs.getDouble("x"),
            rs.getDouble("y"),
            rs.getDouble("z"),
            rs.getFloat("yaw"),
            rs.getFloat("pitch"),
            WarpType.valueOf(rs.getString("type")),
            ownerUuid,
            rs.getString("created_at")
        );
    }
}
