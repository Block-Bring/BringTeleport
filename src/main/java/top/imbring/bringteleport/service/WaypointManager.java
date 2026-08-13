package top.imbring.bringteleport.service;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import top.imbring.bringteleport.model.Waypoint;
import top.imbring.bringteleport.model.Waypoint.WaypointType;

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

public class WaypointManager {

    private final JavaPlugin plugin;
    private final String dbUrl;
    private Connection connection;
    private boolean schemaReady;

    public WaypointManager(JavaPlugin plugin) {
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
                    CREATE TABLE IF NOT EXISTS waypoints (
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

                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_waypoint_public_name " +
                    "ON waypoints(name) WHERE type = 'PUBLIC'");

                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_waypoint_private_name " +
                    "ON waypoints(name, owner_uuid) WHERE type = 'PRIVATE'");
            }
            this.schemaReady = true;
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to initialize database", e);
        }
    }

    /**
     * Add a new waypoint.
     * @return true if successful, false if name already exists
     */
    public boolean addWaypoint(Waypoint waypoint) {
        String sql = "INSERT INTO waypoints (name, world, x, y, z, yaw, pitch, type, owner_uuid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, waypoint.getName());
            pstmt.setString(2, waypoint.getWorld());
            pstmt.setDouble(3, waypoint.getX());
            pstmt.setDouble(4, waypoint.getY());
            pstmt.setDouble(5, waypoint.getZ());
            pstmt.setFloat(6, waypoint.getYaw());
            pstmt.setFloat(7, waypoint.getPitch());
            pstmt.setString(8, waypoint.getType().name());
            if (waypoint.getOwnerUuid() != null) {
                pstmt.setString(9, waypoint.getOwnerUuid().toString());
            } else {
                pstmt.setNull(9, java.sql.Types.VARCHAR);
            }
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                return false;
            }
            this.plugin.getLogger().log(Level.SEVERE, "Failed to add waypoint", e);
            return false;
        }
    }

    /**
     * Delete a waypoint by name and type.
     * @return true if deleted, false if not found
     */
    public boolean deleteWaypoint(String name, WaypointType type, UUID ownerUuid) {
        String sql;
        if (type == WaypointType.PUBLIC) {
            sql = "DELETE FROM waypoints WHERE name = ? AND type = 'PUBLIC'";
        } else {
            sql = "DELETE FROM waypoints WHERE name = ? AND type = 'PRIVATE' AND owner_uuid = ?";
        }

        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, name);
            if (type == WaypointType.PRIVATE) {
                pstmt.setString(2, ownerUuid.toString());
            }
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to delete waypoint", e);
            return false;
        }
    }

    /**
     * Get a waypoint by name and type for a player.
     * For PUBLIC: just name
     * For PRIVATE: name + ownerUuid
     */
    public Optional<Waypoint> getWaypoint(String name, WaypointType type, UUID ownerUuid) {
        String sql;
        if (type == WaypointType.PUBLIC) {
            sql = "SELECT * FROM waypoints WHERE name = ? AND type = 'PUBLIC'";
        } else {
            sql = "SELECT * FROM waypoints WHERE name = ? AND type = 'PRIVATE' AND owner_uuid = ?";
        }

        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, name);
            if (type == WaypointType.PRIVATE) {
                pstmt.setString(2, ownerUuid.toString());
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to get waypoint", e);
        }
        return Optional.empty();
    }

    /**
     * List all public waypoints.
     */
    public List<Waypoint> getPublicWaypoints() {
        List<Waypoint> waypoints = new ArrayList<>();
        String sql = "SELECT * FROM waypoints WHERE type = 'PUBLIC' ORDER BY name";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                waypoints.add(mapRow(rs));
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to list public waypoints", e);
        }
        return waypoints;
    }

    /**
     * List all players who own at least one private waypoint.
     */
    public List<UUID> getPrivateWaypointOwners() {
        List<UUID> owners = new ArrayList<>();
        String sql = "SELECT DISTINCT owner_uuid FROM waypoints WHERE type = 'PRIVATE' AND owner_uuid IS NOT NULL";
        try (Statement stmt = getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                owners.add(UUID.fromString(rs.getString("owner_uuid")));
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to list private waypoint owners", e);
        }
        return owners;
    }

    /**
     * List all private waypoints for a player.
     */
    public List<Waypoint> getPrivateWaypoints(UUID ownerUuid) {
        List<Waypoint> waypoints = new ArrayList<>();
        String sql = "SELECT * FROM waypoints WHERE type = 'PRIVATE' AND owner_uuid = ? ORDER BY name";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, ownerUuid.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    waypoints.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to list private waypoints", e);
        }
        return waypoints;
    }

    /**
     * Convert a Waypoint to a Bukkit Location.
     */
    public Location toLocation(Waypoint waypoint) {
        World world = Bukkit.getWorld(waypoint.getWorld());
        if (world == null) {
            return null;
        }
        return new Location(world, waypoint.getX(), waypoint.getY(), waypoint.getZ(),
            waypoint.getYaw(), waypoint.getPitch());
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

    private Waypoint mapRow(ResultSet rs) throws SQLException {
        String ownerUuidStr = rs.getString("owner_uuid");
        UUID ownerUuid = ownerUuidStr != null ? UUID.fromString(ownerUuidStr) : null;
        return new Waypoint(
            rs.getInt("id"),
            rs.getString("name"),
            rs.getString("world"),
            rs.getDouble("x"),
            rs.getDouble("y"),
            rs.getDouble("z"),
            rs.getFloat("yaw"),
            rs.getFloat("pitch"),
            WaypointType.valueOf(rs.getString("type")),
            ownerUuid,
            rs.getString("created_at")
        );
    }
}
