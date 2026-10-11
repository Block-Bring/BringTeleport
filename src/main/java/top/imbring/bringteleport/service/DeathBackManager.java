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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * 死亡点存储：每个玩家仅保留最近一次死亡位置，使用一次即清除。
 * 读取走内存缓存，SQL 全部在数据库线程上执行，主线程不阻塞在文件 I/O 上。
 */
public class DeathBackManager {

    // 不用 UPSERT（ON CONFLICT ... DO UPDATE）：sqlite.purejava 模式下
    // 内置的 NestedVM SQLite 版本过旧（3.8.x），INSERT OR REPLACE 全版本可用
    private static final String UPSERT_SQL = """
        INSERT OR REPLACE INTO death_backs (player_uuid, world, x, y, z, yaw, pitch, safe_x, safe_y, safe_z, dangerous)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    // 缓存未命中时查库的等待上限（毫秒）
    private static final long DB_QUERY_TIMEOUT_MS = 3000L;

    // 安全点可空：死亡点危险时找到的最近安全落脚点，找不到则为 null（回退传死亡点）。
    // dangerous=true 表示死亡点本身危险（如岩浆/虚空）且未找到安全点，/back 需 confirm 确认
    public record DeathRecord(String world, double x, double y, double z, float yaw, float pitch,
                              Double safeX, Double safeY, Double safeZ, boolean dangerous) {}

    private final BringTeleportPlugin plugin;
    private final String dbUrl;
    private Connection connection;

    // 数据库专用单线程：独占 Connection（SQLite JDBC 的 Connection 非线程安全）。
    // 所有写入都在此线程排队执行，顺序与提交顺序一致
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "BringTeleport-DeathBack-DB");
        thread.setDaemon(true);
        return thread;
    });

    // 死亡记录缓存：用 Optional 包住以支持"该玩家没有记录"的负缓存
    private final ConcurrentHashMap<UUID, Optional<DeathRecord>> cache = new ConcurrentHashMap<>();

    // 进行中的异步安全点搜索：/back 需等待其完成再读取记录，避免落回危险死亡点
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> pendingSearches = new ConcurrentHashMap<>();

    public DeathBackManager(BringTeleportPlugin plugin) {
        this.plugin = plugin;
        File dbFile = new File(plugin.getDataFolder(), "data.db");
        this.dbUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        ensureSchema();
    }

    // 连接在构造期由主线程建立，之后只允许数据库线程访问，故无需加锁
    private Connection getConnection() throws SQLException {
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
        UUID uuid = player.getUniqueId();
        String world = location.getWorld() != null ? location.getWorld().getName() : "";
        DeathRecord record = new DeathRecord(world, location.getX(), location.getY(), location.getZ(),
            location.getYaw(), location.getPitch(), null, null, null, dangerous);
        // 先落缓存：复活提示与 /back 立即读到最新记录，不必为一次死亡再往返一次数据库
        cache.put(uuid, Optional.of(record));
        String playerName = player.getName();
        submitWrite(() -> upsertDeathRecord(uuid, record, playerName));
    }

    // 数据库线程上执行的写入
    private void upsertDeathRecord(UUID uuid, DeathRecord record, String playerName) {
        try (PreparedStatement pstmt = getConnection().prepareStatement(UPSERT_SQL)) {
            pstmt.setString(1, uuid.toString());
            pstmt.setString(2, record.world());
            pstmt.setDouble(3, record.x());
            pstmt.setDouble(4, record.y());
            pstmt.setDouble(5, record.z());
            pstmt.setFloat(6, record.yaw());
            pstmt.setFloat(7, record.pitch());
            pstmt.setNull(8, java.sql.Types.REAL);
            pstmt.setNull(9, java.sql.Types.REAL);
            pstmt.setNull(10, java.sql.Types.REAL);
            pstmt.setInt(11, record.dangerous() ? 1 : 0);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to save death location for " + playerName, e);
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
            // 回填放回主线程：缓存更新与写任务提交都在主线程，和 /back 的读取、清除保持同一顺序
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

    // 两次记录是否同一死亡落点（比对库中用于匹配的字段）
    private static boolean sameSpot(DeathRecord a, DeathRecord b) {
        return a.world().equals(b.world())
            && Double.compare(a.x(), b.x()) == 0
            && Double.compare(a.y(), b.y()) == 0
            && Double.compare(a.z(), b.z()) == 0;
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
        // 取出坐标，避免把可变的 Location 带到数据库线程上
        double x = safe.getX();
        double y = safe.getY();
        double z = safe.getZ();

        // 同步缓存，避免 /back 读到仍带危险标记的旧记录
        cache.computeIfPresent(uuid, (k, current) -> current.map(r -> new DeathRecord(
            r.world(), r.x(), r.y(), r.z(), r.yaw(), r.pitch(), x, y, z, false)));

        submitWrite(() -> writeSafePoint(uuid, x, y, z));
    }

    // 数据库线程上执行的安全点回填
    private void writeSafePoint(UUID uuid, double x, double y, double z) {
        String sql = """
            UPDATE death_backs SET safe_x = ?, safe_y = ?, safe_z = ?, dangerous = 0
            WHERE player_uuid = ?
            """;
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setDouble(1, x);
            pstmt.setDouble(2, y);
            pstmt.setDouble(3, z);
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

    /**
     * 读取死亡记录。缓存优先（含"无记录"的负缓存），未命中才查库并记入缓存，
     * 使复活提示与 /back 在本次会话内不再触发主线程数据库查询。
     */
    public Optional<DeathRecord> getDeathRecord(UUID playerUuid) {
        Optional<DeathRecord> cached = cache.get(playerUuid);
        if (cached != null) return cached;

        Optional<DeathRecord> loaded = loadDeathRecord(playerUuid);
        if (loaded == null) return Optional.empty(); // 查询失败：不写缓存，下次仍可重试
        cache.put(playerUuid, loaded);
        return loaded;
    }

    // 查库读取记录（交给数据库线程执行，主线程只等结果）；
    // 查询失败返回 null，与"确实没有记录"的空 Optional 区分开
    private Optional<DeathRecord> loadDeathRecord(UUID playerUuid) {
        try {
            return dbExecutor.submit(() -> queryDeathRecord(playerUuid))
                .get(DB_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return null; // 插件正在停用，队列已关闭
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to get death location for " + playerUuid, e);
            return null;
        }
    }

    // 数据库线程上执行的查询；SQL 失败返回 null
    private Optional<DeathRecord> queryDeathRecord(UUID playerUuid) {
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
                return Optional.empty();
            }
        } catch (SQLException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Failed to get death location for " + playerUuid, e);
            return null;
        }
    }

    /**
     * 清除死亡记录；仅当记录仍指向 {@code expected} 那次死亡时生效。
     * 传送是异步的，期间玩家可能再次死亡并写入新记录，此时不能把新记录一并删掉。
     */
    public void clearIfCurrent(UUID playerUuid, DeathRecord expected) {
        // 缓存同步：仍是这条记录则记为"无记录"（负缓存），与库里的匹配删除保持一致
        cache.computeIfPresent(playerUuid, (k, current) ->
            current.isPresent() && sameSpot(current.get(), expected) ? Optional.<DeathRecord>empty() : current);

        submitWrite(() -> deleteDeathRecord(playerUuid, expected));
    }

    // 数据库线程上执行的匹配删除
    private void deleteDeathRecord(UUID playerUuid, DeathRecord expected) {
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

    // 提交数据库写任务：失败仅发生在插件停用、队列已关闭时
    private void submitWrite(Runnable task) {
        try {
            dbExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            this.plugin.getLogger().warning("Death back database already closed, skipped a write");
        }
    }

    public void shutdown() {
        // 收尾挂起的搜索，避免 /back 的等待回调在插件停用后无处落地
        pendingSearches.values().forEach(future -> future.complete(null));
        pendingSearches.clear();
        // 同一线程串行执行，排在后面的关连接必然在已有写入之后
        try {
            dbExecutor.execute(this::closeConnection);
        } catch (RejectedExecutionException ignored) {
            return; // 已停用：队列与连接都已关闭
        }
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                this.plugin.getLogger().warning("Timed out while closing death back database");
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeConnection() {
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
