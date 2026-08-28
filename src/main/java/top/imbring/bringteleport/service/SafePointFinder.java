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
     * 主线程快速判定：origin 所在位置是否安全。安全则无需搜索，直接以死亡点为记录。
     * 玩家碰撞箱为 0.6×0.6（每侧外扩 0.3），可能同时覆盖相邻方块，
     * 所有被碰撞箱覆盖的方块列都必须安全（避免中心格安全、边缘却是岩浆等被误判）。
     */
    public static boolean isSpotSafe(Location origin) {
        World world = origin.getWorld();
        if (world == null) return false;
        double x = origin.getX();
        double z = origin.getZ();
        int minX = (int) Math.floor(x - 0.3);
        int maxX = (int) Math.floor(x + 0.3);
        int minZ = (int) Math.floor(z - 0.3);
        int maxZ = (int) Math.floor(z + 0.3);
        int y = origin.getBlockY();
        BlockReader reader = (bx, by, bz) -> world.getBlockAt(bx, by, bz).getType();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (!isSafeSpot(bx, y, bz, reader)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 异步查找最近安全落脚点（找不到则完成值为 null）。
     * 快照不可变、读取线程安全，搜索强制在公共线程池执行，不阻塞主线程。
     * effort（努力程度，1-3）：
     * 1 = 只搜已加载区块；2 = 找不到时异步加载范围内未加载区块再搜一轮；
     * 3 = 标准基础上再把搜索半径扩大一倍。
     */
    public static CompletableFuture<Location> findSafePointAsync(Location origin, int radiusH, int radiusV, int effort) {
        World world = origin.getWorld();
        if (world == null) return CompletableFuture.completedFuture(null);
        Block center = origin.getBlock();
        int bx = center.getX();
        int by = center.getY();
        int bz = center.getZ();

        CompletableFuture<Location> first = searchArea(world, bx, by, bz, radiusH, radiusV, origin, false);
        if (effort < 2) return first;
        return first.thenComposeAsync(safe -> {
            if (safe != null) return CompletableFuture.completedFuture(safe);
            CompletableFuture<Location> second = searchArea(world, bx, by, bz, radiusH, radiusV, origin, true);
            if (effort < 3) return second;
            return second.thenComposeAsync(safe2 -> {
                if (safe2 != null) return CompletableFuture.completedFuture(safe2);
                return searchArea(world, bx, by, bz, radiusH * 2, radiusV * 2, origin, true);
            });
        });
    }

    // 在指定范围内收集区块快照并搜索（forceLoad=true 时未加载区块也会异步加载后再搜）
    private static CompletableFuture<Location> searchArea(World world, int bx, int by, int bz,
                                                         int radiusH, int radiusV, Location origin,
                                                         boolean forceLoad) {
        int minChunkX = (bx - radiusH) >> 4;
        int maxChunkX = (bx + radiusH) >> 4;
        int minChunkZ = (bz - radiusH) >> 4;
        int maxChunkZ = (bz + radiusH) >> 4;

        List<CompletableFuture<ChunkSnapshot>> futures = new ArrayList<>();
        List<int[]> coords = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!forceLoad && !world.isChunkLoaded(cx, cz)) continue;
                // 1.21.1 无 getChunkSnapshotAsync：先异步取区块，再在线程池捕获线程安全快照
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
                return searchFor(world, bx, by, bz, radiusH, radiusV, origin,
                    (x, y, z) -> {
                        ChunkSnapshot cs = snapshots.get(key(x >> 4, z >> 4));
                        return cs == null ? null : cs.getBlockType(x & 15, y, z & 15);
                    });
            });
    }

    // 按死亡场景选择搜索算法：
    // - 高空坠落（站立格脚下是空气）：垂直优先，先沿同 X、Z 列向下找地面落脚点，
    //   到地面后再以地面高度水平展开；
    // - 其他场景：先同高度水平（半径递增），再上下交替放宽（默认）。
    private static Location searchFor(World world, int bx, int by, int bz,
                                      int radiusH, int radiusV, Location origin, BlockReader reader) {
        Material foot = reader.type(bx, by - 1, bz);
        if (foot != null && foot.isAir()) {
            return searchVerticalFirst(world, bx, by, bz, radiusH, radiusV, origin, reader);
        }
        return search(world, bx, by, bz, radiusH, radiusV, origin, reader);
    }

    /**
     * 末地虚空兜底（必须在主线程调用）：
     * 1. 在死亡点同 X、Z 列垂直寻找 Y 0~128 内可站立的方块，找到则返回该落脚点；
     * 2. 找不到则在同 X、Z 正下方找空旷处，生成以玩家为中心的 3×3 末地石平台并返回平台站位置；
     * 3. 两者都失败返回 null。
     */
    public static Location handleEndVoid(World world, int bx, int bz, Location origin) {
        BlockReader reader = (x, y, z) -> world.getBlockAt(x, y, z).getType();
        // 第一步：垂直寻找 Y 0~128 内可站立的方块（从高处向下）
        for (int y = 128; y >= 0; y--) {
            if (isSafeSpot(bx, y, bz, reader)) {
                return spot(world, bx, y, bz, origin);
            }
        }
        // 第二步：同 X、Z 下找空旷处生成 3×3 末地石平台（从高处向下找第一个空旷层）
        int platformY = findEmptyPlatformY(world, bx, bz);
        if (platformY < 0) return null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(bx + dx, platformY, bz + dz).setType(Material.END_STONE);
            }
        }
        return new Location(world, bx + 0.5, platformY + 1, bz + 0.5, origin.getYaw(), origin.getPitch());
    }

    // 平台层及上方 2 格（站立格+头格）的 3×3 区域全部空旷才视为可生成层
    private static int findEmptyPlatformY(World world, int bx, int bz) {
        for (int y = 128; y >= 0; y--) {
            boolean empty = true;
            for (int dx = -1; dx <= 1 && empty; dx++) {
                for (int dz = -1; dz <= 1 && empty; dz++) {
                    for (int dy = 0; dy <= 2; dy++) {
                        Material m = world.getBlockAt(bx + dx, y + dy, bz + dz).getType();
                        if (m.isSolid() || LIQUIDS.contains(m) || FIRE.contains(m)) {
                            empty = false;
                            break;
                        }
                    }
                }
            }
            if (empty) return y;
        }
        return -1;
    }

    // 垂直优先搜索：高空坠落场景。先沿死亡点所在 X、Z 列垂直向下找第一个安全落脚点；
    // 向下途中遇到危险方块（岩浆/水等液体）说明已到地面表面，
    // 立即以该高度为基准做水平优先搜索（同高度，上下交替放宽），不再继续向下。
    private static Location searchVerticalFirst(World world, int bx, int by, int bz,
                                                int radiusH, int radiusV, Location origin, BlockReader reader) {
        int minY = world.getMinHeight();
        int surfaceY = -1;
        boolean foundSurface = false;
        for (int y = by; y >= minY; y--) {
            if (isSafeSpot(bx, y, bz, reader)) {
                return spot(world, bx, y, bz, origin);
            }
            if (!foundSurface) {
                Material stand = reader.type(bx, y, bz);
                if (stand != null && LIQUIDS.contains(stand)) {
                    // 向下遇到危险方块（岩浆/水等）说明已到地面表面：
                    // 以该高度水平搜索，不再继续向下
                    surfaceY = y;
                    foundSurface = true;
                    break;
                }
            }
        }
        if (!foundSurface) return null; // 下方直到世界底都没有地面（虚空），无安全点
        return search(world, bx, surfaceY, bz, radiusH, radiusV, origin, reader);
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
