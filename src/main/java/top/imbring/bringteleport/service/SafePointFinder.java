package top.imbring.bringteleport.service;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Set;

/**
 * 在死亡点附近寻找最近的安全落脚点（水平优先，再上下放宽）。
 * 安全判定：脚下是实心方块（非液体/岩浆/火），站立格与头格可站（非固体、非液体、非火）。
 */
public final class SafePointFinder {

    private static final Set<Material> LIQUIDS = Set.of(
        Material.WATER, Material.LAVA, Material.BUBBLE_COLUMN,
        Material.SEAGRASS, Material.TALL_SEAGRASS,
        Material.KELP, Material.KELP_PLANT);
    private static final Set<Material> FIRE = Set.of(Material.FIRE, Material.SOUL_FIRE);

    private SafePointFinder() {
    }

    /**
     * 从 origin 开始寻找最近的安全落脚点，找不到返回 null。
     * 搜索顺序：先同高度（水平半径递增），再逐层上下交替放宽。
     * 返回的 Location 中心点落在方块内，朝向沿用 origin。
     */
    public static Location findSafePoint(Location origin, int radiusH, int radiusV) {
        World world = origin.getWorld();
        if (world == null) return null;

        Block center = origin.getBlock();
        int bx = center.getX();
        int by = center.getY();
        int bz = center.getZ();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        for (int v = 0; v <= radiusV; v++) {
            // 同高度优先（v=0 只一次），之后 +1/-1 交替放宽
            for (int sign : v == 0 ? new int[]{0} : new int[]{-1, 1}) {
                int y = by + sign * v;
                if (y < minY || y > maxY) continue;

                for (int r = 0; r <= radiusH; r++) {
                    if (r == 0) {
                        if (isSafeSpot(world.getBlockAt(bx, y, bz))) {
                            return spot(world, bx, y, bz, origin);
                        }
                        continue;
                    }
                    // 半径 r 的方形环，先四边再四角（同一环内顺序不影响"最近"语义）
                    for (int dx = -r; dx <= r; dx++) {
                        if (isSafeSpot(world.getBlockAt(bx + dx, y, bz - r))) {
                            return spot(world, bx + dx, y, bz - r, origin);
                        }
                        if (isSafeSpot(world.getBlockAt(bx + dx, y, bz + r))) {
                            return spot(world, bx + dx, y, bz + r, origin);
                        }
                    }
                    for (int dz = -r + 1; dz <= r - 1; dz++) {
                        if (isSafeSpot(world.getBlockAt(bx - r, y, bz + dz))) {
                            return spot(world, bx - r, y, bz + dz, origin);
                        }
                        if (isSafeSpot(world.getBlockAt(bx + r, y, bz + dz))) {
                            return spot(world, bx + r, y, bz + dz, origin);
                        }
                    }
                }
            }
        }
        return null;
    }

    // 方块坐标对应的可站位置（玩家中心在方块水平中点，y 为站立格）
    private static Location spot(World world, int x, int y, int z, Location origin) {
        return new Location(world, x + 0.5, y, z + 0.5, origin.getYaw(), origin.getPitch());
    }

    // 判断以 block 为站立格的位置是否安全：脚下实心（非液体/非火），站立格与头格可站
    private static boolean isSafeSpot(Block block) {
        if (!isSolidFoot(block.getRelative(0, -1, 0))) return false;
        if (!isStandable(block) || !isStandable(block.getRelative(0, 1, 0))) return false;
        return true;
    }

    // 脚下格：实心方块且非液体且非火（岩浆块等伤害方块按简单版判定视为安全，不在此排除）
    private static boolean isSolidFoot(Block block) {
        Material m = block.getType();
        if (!m.isSolid()) return false;
        if (LIQUIDS.contains(m)) return false;
        if (FIRE.contains(m)) return false;
        return true;
    }

    // 可站格：非固体（空气/台阶等）且非液体且非火
    private static boolean isStandable(Block block) {
        Material m = block.getType();
        if (m.isSolid()) return false;
        if (LIQUIDS.contains(m)) return false;
        if (FIRE.contains(m)) return false;
        return true;
    }
}
