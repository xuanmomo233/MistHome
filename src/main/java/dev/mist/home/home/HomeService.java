package dev.mist.home.home;

import org.bukkit.World;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.storage.DuplicateKeyException;
import dev.mist.home.storage.Storage;
import dev.mist.home.storage.StorageException;
import dev.mist.home.world.HomeRegion;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 家园领域服务：内存缓存 + Storage 持久化（按需懒加载）。
 * <p>
 * 世界池模型下，家园的物理位置由停放台账（worldManager.parkingOf）
 * 决定；homeAt 反查走「坐标 -> 槽位 -> slotOwner -> homeId」链路。
 * DB 操作走专用线程池，永不阻塞主线程。
 */
public class HomeService {

    private final MistHomePlugin plugin;
    private final Storage storage;
    private final ExecutorService dbExecutor;

    /** ownerUuid -> Home */
    private final Map<UUID, Home> byOwner = new ConcurrentHashMap<>();
    /** homeId -> Home */
    private final Map<Long, Home> byId = new ConcurrentHashMap<>();
    /** homeId -> (playerUuid -> role)，角色懒加载缓存 */
    private final Map<Long, Map<UUID, HomeRole>> roleCache = new ConcurrentHashMap<>();
    /** 正在异步加载中的 key，防止重复查库 */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    private static final int CREATE_SLOT_MAX_RETRY = 8;

    public HomeService(MistHomePlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.dbExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "MistHome-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public Storage storage() {
        return storage;
    }

    /** 关闭 DB 线程池（插件 onDisable 调用） */
    public void shutdown() {
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 查询 ----------

    /** 同步查缓存（不触发加载）。命中返回 home，否则 empty */
    public Optional<Home> homeOf(UUID owner) {
        return Optional.ofNullable(byOwner.get(owner));
    }

    /** 异步查库并回填缓存（推荐入口，绝不阻塞主线程） */
    public CompletableFuture<Optional<Home>> homeOfAsync(UUID owner) {
        Home cached = byOwner.get(owner);
        if (cached != null) {
            return CompletableFuture.completedFuture(Optional.of(cached));
        }
        return CompletableFuture.supplyAsync(() -> {
            Optional<Home> home = storage.findHomeByOwner(owner);
            home.ifPresent(this::cache);
            return home;
        }, dbExecutor);
    }

    public Optional<Home> byId(long homeId) {
        return Optional.ofNullable(byId.get(homeId));
    }

    /**
     * 缓存未命中时同步查库回填。
     * 仅供启动核对/卸载归档等冷路径使用，勿在事件热路径调用（阻塞）。
     */
    public Optional<Home> byIdBlocking(long homeId) {
        Home cached = byId.get(homeId);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<Home> home = storage.findHomeById(homeId);
        home.ifPresent(this::cache);
        return home;
    }

    /**
     * 由世界坐标反查家园区域（用于保护判定，主线程热路径）。
     * 纯内存：坐标 → 内槽位 → 停放台账 → byId 缓存。
     * 未命中（重启后台账恢复但缓存未预热）异步回填后返回 empty。
     */
    public Optional<Home> homeAt(World world, int x, int z) {
        int worldIndex = plugin.worldManager().parseWorldIndex(world.getName());
        if (worldIndex < 0) {
            return Optional.empty();
        }
        Long homeId = plugin.worldManager().homeIdAt(worldIndex, x, z);
        if (homeId == null) {
            return Optional.empty();
        }
        Home cached = byId.get(homeId);
        if (cached != null) {
            return Optional.of(cached);
        }
        warmHome(homeId);
        return Optional.empty();
    }

    /**
     * 家园当前可用区域（未停放返回 empty）。
     * 位置由停放台账动态决定，随每次停放变化。
     */
    public Optional<HomeRegion> regionOf(Home home) {
        var cfg = plugin.mistConfig();
        int usable = cfg.tier(home.tierLevel()).radius();
        return plugin.worldManager().regionOf(home, usable);
    }

    /**
     * 玩家在某家园中的角色：OWNER 直接判定；
     * 成员/封禁走懒加载缓存，未命中返回 VISITOR 并异步回填。
     * BANNED 覆盖一切（含公开家园）。
     */
    public HomeRole roleOf(Home home, UUID player) {
        if (home.owner().equals(player)) {
            return HomeRole.OWNER;
        }
        Map<UUID, HomeRole> roles = roleCache.get(home.id());
        if (roles != null) {
            HomeRole cached = roles.get(player);
            if (cached != null) {
                return cached;
            }
        }
        warmRole(home.id(), player);
        return HomeRole.VISITOR;
    }

    // ---------- 创建（唯一索引 + 冲突重试） ----------

    /**
     * 创建家园：异步执行。取最小空闲槽位号（仅作唯一序号，不决定物理位置）后插入，
     * slot_index 唯一冲突时换号重试（多节点/并发安全）。
     * 出生点初始为槽位中心偏移 (0.5, 65, 0.5)，粘贴后校正。
     */
    public CompletableFuture<Home> createHomeAsync(UUID owner, String name, String template) {
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 0; attempt < CREATE_SLOT_MAX_RETRY; attempt++) {
                int slot = storage.allocateSlot();
                try {
                    Home home = storage.createHome(owner, name, slot, 0, template,
                            0.5, 65.0, 0.5, plugin.mistConfig().serverName());
                    cache(home);
                    return home;
                } catch (DuplicateKeyException e) {
                    if (!"slot_index".equals(e.constraint())) {
                        // owner_uuid 冲突 = 该玩家已有家园，不再重试
                        throw new StorageException("玩家已有家园: " + owner, e);
                    }
                    // slot 被并发节点抢占，重算下一个空闲槽位
                }
            }
            throw new StorageException("槽位分配重试超限（" + CREATE_SLOT_MAX_RETRY + " 次）", null);
        }, dbExecutor);
    }

    // ---------- 缓存管理 ----------

    public void cache(Home home) {
        byOwner.put(home.owner(), home);
        byId.put(home.id(), home);
    }

    public void evict(Home home) {
        byOwner.remove(home.owner());
        byId.remove(home.id());
        roleCache.remove(home.id());
    }

    /** 跨服缓存失效广播的接收端：按 homeId 清掉本地缓存 */
    public void evictById(long homeId) {
        Home h = byId.get(homeId);
        if (h != null) {
            evict(h);
        } else {
            roleCache.remove(homeId);
        }
    }

    public void cacheRole(long homeId, UUID player, HomeRole role) {
        roleCache.computeIfAbsent(homeId, k -> new ConcurrentHashMap<>())
                .put(player, role);
    }

    public void evictRole(long homeId, UUID player) {
        Map<UUID, HomeRole> roles = roleCache.get(homeId);
        if (roles != null) {
            roles.remove(player);
        }
    }

    // ---------- 异步预热 ----------

    private void warmHome(long homeId) {
        if (!pending.add("home:" + homeId)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                storage.findHomeById(homeId).ifPresent(this::cache);
            } finally {
                pending.remove("home:" + homeId);
            }
        }, dbExecutor).exceptionally(t -> {
            plugin.getLogger().log(Level.WARNING, "预热家园缓存失败 home=" + homeId, t);
            return null;
        });
    }

    private void warmRole(long homeId, UUID player) {
        String key = "role:" + homeId + ":" + player;
        if (!pending.add(key)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                HomeRole role = storage.roleOf(homeId, player);
                cacheRole(homeId, player, role);
            } finally {
                pending.remove(key);
            }
        }, dbExecutor).exceptionally(t -> {
            plugin.getLogger().log(Level.WARNING, "预热角色缓存失败 home=" + homeId, t);
            return null;
        });
    }
}
