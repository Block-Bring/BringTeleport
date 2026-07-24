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
    private Connection connection;

    public WaypointManager(JavaPlugin plugin) {
        this.plugin = plugin;
        initDatabase();
    }

    private void initDatabase() {
        try {
            File dataFolder = this.plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            File dbFile = new File(dataFolder, "data.db");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement stmt = this.connection.createStatement()) {
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

                // Public waypoints must have unique names globally
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_waypoint_public_name " +
                    "ON waypoints(name) WHERE type = 'PUBLIC'");

                // Private waypoints must be unique per player
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_waypoint_private_name " +
                    "ON waypoints(name, owner_uuid) WHERE type = 'PRIVATE'");
            }
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
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql)) {
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

        try (PreparedStatement pstmt = this.connection.prepareStatement(sql)) {
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

        try (PreparedStatement pstmt = this.connection.prepareStatement(sql)) {
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
        try (Statement stmt = this.connection.createStatement();
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
     * List all private waypoints for a player.
     */
    public List<Waypoint> getPrivateWaypoints(UUID ownerUuid) {
        List<Waypoint> waypoints = new ArrayList<>();
        String sql = "SELECT * FROM waypoints WHERE type = 'PRIVATE' AND owner_uuid = ? ORDER BY name";
        try (PreparedStatement pstmt = this.connection.prepareStatement(sql)) {
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

    public void shutdown() {
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (SQLException e) {
                this.plugin.getLogger().log(Level.SEVERE, "Failed to close database connection", e);
            }
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
