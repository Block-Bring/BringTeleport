package top.imbring.bringteleport.service;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 在死亡点附近寻找最近的安全落脚点（水平优先，再上下放宽，先下后上）。
 * 安全判定：脚下是实心方块（非液体/岩浆/火），站立格与头格可站（非固体、非液体、非火）。
 *
 * 主线程上只做"死亡点本身是否安全"的快速判定（3 次方块读取，玩家死亡处的区块必然已加载）；
 * 需要大范围搜索时走 {@link #findSafePointAsync}，只读已加载区块的不可变快照，
 * 全程不阻塞服务器主线程。
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
     * 主线程快速判定：origin 所在格是否安全。安全则无需搜索，直接以死亡点为记录。
     */
    public static boolean isSpotSafe(Location origin) {
        World world = origin.getWorld();
        if (world == null) return false;
        Block block = origin.getBlock();
        return isSafeSpot(block.getX(), block.getY(), block.getZ(),
            (x, y, z) -> world.getBlockAt(x, y, z).getType());
    }

    /**
     * 异步查找最近安全落脚点（找不到则完成值为 null）。
     * 只对搜索范围内已加载的区块取快照（保持"跳过未加载区块"的语义），
     * 快照不可变、读取线程安全，搜索强制在公共线程池执行，不阻塞主线程。
     */
    public static CompletableFuture<Location> findSafePointAsync(Location origin, int radiusH, int radiusV) {
        World world = origin.getWorld();
        if (world == null) return CompletableFuture.completedFuture(null);

        Block center = origin.getBlock();
        int bx = center.getX();
        int by = center.getY();
        int bz = center.getZ();
        int minChunkX = (bx - radiusH) >> 4;
        int maxChunkX = (bx + radiusH) >> 4;
        int minChunkZ = (bz - radiusH) >> 4;
        int maxChunkZ = (bz + radiusH) >> 4;

        List<CompletableFuture<ChunkSnapshot>> futures = new ArrayList<>();
        List<int[]> coords = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                // 1.21.1 无 getChunkSnapshotAsync：先异步取已加载的区块，再在线程池捕获线程安全快照
                futures.add(world.getChunkAtAsync(cx, cz).thenApplyAsync(Chunk::getChunkSnapshot));
                coords.add(new int[]{cx, cz});
            }
        }
        if (futures.isEmpty()) return CompletableFuture.completedFuture(null);

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
            // 任一快照失败（如区块在抓取前卸载）不影响其余：吞掉异常后逐个收集
            .handle((ignored, ex) -> null)
            // 强制在公共线程池执行搜索，避免快照已就绪时在调用线程（主线程）上同步跑完
            .thenApplyAsync(ignored -> {
                Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        ChunkSnapshot snapshot = futures.get(i).join();
                        if (snapshot != null) {
                            int[] c = coords.get(i);
                            snapshots.put(key(c[0], c[1]), snapshot);
                        }
                    } catch (Exception e) {
                        // 单区块快照失败，跳过该区块
                    }
                }
                return search(world, bx, by, bz, radiusH, radiusV, origin,
                    (x, y, z) -> {
                        ChunkSnapshot cs = snapshots.get(key(x >> 4, z >> 4));
                        return cs == null ? null : cs.getBlockType(x & 15, y, z & 15);
                    });
            });
    }

    // 搜索主逻辑：先同高度（水平半径递增），再逐层上下交替放宽（先下后上）。
    // reader 返回 null 表示该位置所在区块不可用（未加载/快照缺失），按不安全跳过。
    private static Location search(World world, int bx, int by, int bz, int radiusH, int radiusV,
                                   Location origin, BlockReader reader) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        for (int v = 0; v <= radiusV; v++) {
            // 同高度优先（v=0 只一次），之后 -1/+1 交替放宽（先下后上）
            for (int sign : v == 0 ? new int[]{0} : new int[]{-1, 1}) {
                int y = by + sign * v;
                if (y < minY || y > maxY) continue;

                for (int r = 0; r <= radiusH; r++) {
                    if (r == 0) {
                        if (isSafeSpot(bx, y, bz, reader)) {
                            return spot(world, bx, y, bz, origin);
                        }
                        continue;
                    }
                    // 半径 r 的方形环，先四边再四角（同一环内顺序不影响"最近"语义）
                    for (int dx = -r; dx <= r; dx++) {
                        if (isSafeSpot(bx + dx, y, bz - r, reader)) {
                            return spot(world, bx + dx, y, bz - r, origin);
                        }
                        if (isSafeSpot(bx + dx, y, bz + r, reader)) {
                            return spot(world, bx + dx, y, bz + r, origin);
                        }
                    }
                    for (int dz = -r + 1; dz <= r - 1; dz++) {
                        if (isSafeSpot(bx - r, y, bz + dz, reader)) {
                            return spot(world, bx - r, y, bz + dz, origin);
                        }
                        if (isSafeSpot(bx + r, y, bz + dz, reader)) {
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

    // 判断以 (x, y, z) 为站立格的位置是否安全：脚下实心（非液体/非火），站立格与头格可站
    private static boolean isSafeSpot(int x, int y, int z, BlockReader reader) {
        Material foot = reader.type(x, y - 1, z);
        Material stand = reader.type(x, y, z);
        Material head = reader.type(x, y + 1, z);
        if (foot == null || stand == null || head == null) return false; // 区块不可用
        return isSolidFoot(foot) && isStandable(stand) && isStandable(head);
    }

    // 脚下格：实心方块且非液体且非火（岩浆块等伤害方块按简单版判定视为安全，不在此排除）
    private static boolean isSolidFoot(Material material) {
        if (!material.isSolid()) return false;
        if (LIQUIDS.contains(material)) return false;
        if (FIRE.contains(material)) return false;
        return true;
    }

    // 可站格：非固体（空气/台阶等）且非液体且非火
    private static boolean isStandable(Material material) {
        if (material.isSolid()) return false;
        if (LIQUIDS.contains(material)) return false;
        if (FIRE.contains(material)) return false;
        return true;
    }

    // 区块坐标 (cx, cz) → long key
    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // 方块类型读取器：返回 null 表示该位置所在区块不可用
    @FunctionalInterface
    private interface BlockReader {
        Material type(int x, int y, int z);
    }
}
