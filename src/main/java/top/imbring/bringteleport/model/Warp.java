package top.imbring.bringteleport.model;

import org.bukkit.Location;

import java.util.UUID;

public class Warp {

    private final int id;
    private final String name;
    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final WarpType type;
    private final UUID ownerUuid;
    private final String createdAt;

    public Warp(int id, String name, String world, double x, double y, double z,
                    float yaw, float pitch, WarpType type, UUID ownerUuid, String createdAt) {
        this.id = id;
        this.name = name;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.type = type;
        this.ownerUuid = ownerUuid;
        this.createdAt = createdAt;
    }

    public static Warp fromLocation(String name, Location location, WarpType type, UUID ownerUuid) {
        return new Warp(
            0, name,
            location.getWorld().getName(),
            location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch(),
            type, ownerUuid, null
        );
    }

    public int getId() {
        return this.id;
    }

    public String getName() {
        return this.name;
    }

    public String getWorld() {
        return this.world;
    }

    public double getX() {
        return this.x;
    }

    public double getY() {
        return this.y;
    }

    public double getZ() {
        return this.z;
    }

    public float getYaw() {
        return this.yaw;
    }

    public float getPitch() {
        return this.pitch;
    }

    public WarpType getType() {
        return this.type;
    }

    public UUID getOwnerUuid() {
        return this.ownerUuid;
    }

    public String getCreatedAt() {
        return this.createdAt;
    }

    public enum WarpType {
        PUBLIC,
        PRIVATE
    }
}
