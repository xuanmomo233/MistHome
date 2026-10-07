package dev.mist.home.home;

import org.bukkit.World;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.storage.DuplicateKeyException;
import dev.mist.home.storage.Storage;
import dev.mist.home.storage.StorageException;
import dev.mist.home.world.HomeRegion;
import dev.mist.home.world.SlotAllocator;

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
 * 缓存策略：
 * <ul>
 *   <li>读路径零阻塞：所有查询先查缓存，未命中异步回填并返回空结果</li>
 *   <li>世界加载时 {@link #warmWorld(int)} 批量预热该世界内全部家园，
 *       保证玩家落地前 homeAt 判定就绪</li>
 *   <li>DB 操作走专用线程池，永不阻塞主线程</li>
 * </ul>
 */
public class HomeService {

    private final MistHomePlugin plugin;
    private final Storage storage;
    private final ExecutorService dbExecutor;

    /** ownerUuid -> Home */
    private final Map<UUID, Home> byOwner = new ConcurrentHashMap<>();
    /** homeId -> Home */
    private final Map<Long, Home> byId = new ConcurrentHashMap<>();
    /** slotIndex -> Home（homeAt 反查索引） */
    private final Map<Integer, Home> bySlot = new ConcurrentHashMap<>();
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

    /**
     * 由世界坐标反查家园区域（用于保护判定，主线程热路径）。
     * 纯内存：坐标 → slotIndex → bySlot 缓存。
     * 未命中时异步预热该槽位后返回 empty；
     * 世界加载时 warmWorld 已批量预热，正常路径不会 miss。
     */
    public Optional<Home> homeAt(World world, int x, int z) {
        int worldIndex = plugin.worldManager().parseWorldIndex(world.getName());
        if (worldIndex < 0) {
            return Optional.empty();
        }
        var cfg = plugin.mistConfig();
        var slotOpt = SlotAllocator.slotIndexAt(worldIndex, cfg.homesPerWorld(),
                cfg.slotSize(), cfg.gap(), x, z);
        if (slotOpt.isEmpty()) {
            return Optional.empty();
        }
        int slotIndex = slotOpt.getAsInt();
        Home cached = bySlot.get(slotIndex);
        if (cached != null) {
            return Optional.of(cached);
        }
        warmSlot(slotIndex);
        return Optional.empty();
    }

    /** 计算家园的当前区域信息 */
    public HomeRegion regionOf(Home home) {
        var cfg = plugin.mistConfig();
        int usable = cfg.tier(home.tierLevel()).radius();
        return SlotAllocator.regionOf(home.slotIndex(), cfg.homesPerWorld(),
                cfg.slotSize(), cfg.gap(), usable);
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
     * 创建家园：异步执行。取最小空闲槽位后插入，
     * slot_index 唯一冲突时换新槽位重试（多节点/并发安全）。
     */
    public CompletableFuture<Home> createHomeAsync(UUID owner, String name, String template) {
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 0; attempt < CREATE_SLOT_MAX_RETRY; attempt++) {
                int slot = storage.allocateSlot();
                try {
                    HomeRegion region = SlotAllocator.regionOf(slot,
                            plugin.mistConfig().homesPerWorld(),
                            plugin.mistConfig().slotSize(),
                            plugin.mistConfig().gap(), 0);
                    Home home = storage.createHome(owner, name, slot, 0, template,
                            region.centerX() + 0.5, 65.0, region.centerZ() + 0.5);
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
        bySlot.put(home.slotIndex(), home);
    }

    public void evict(Home home) {
        byOwner.remove(home.owner());
        byId.remove(home.id());
        bySlot.remove(home.slotIndex());
        roleCache.remove(home.id());
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

    /**
     * 世界加载成功后批量预热该世界内全部家园，
     * 消除玩家落地后 homeAt 的冷缓存窗口。
     */
    public void warmWorld(int worldIndex) {
        int hpw = plugin.mistConfig().homesPerWorld();
        int from = worldIndex * hpw;
        int to = from + hpw;
        CompletableFuture.runAsync(() -> {
            for (Home home : storage.listHomesInSlots(from, to)) {
                cache(home);
            }
        }, dbExecutor).exceptionally(t -> {
            plugin.getLogger().log(Level.WARNING, "预热家园缓存失败 world=" + worldIndex, t);
            return null;
        });
    }

    private void warmSlot(int slotIndex) {
        if (!pending.add("slot:" + slotIndex)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                storage.findHomeBySlot(slotIndex).ifPresent(this::cache);
            } finally {
                pending.remove("slot:" + slotIndex);
            }
        }, dbExecutor).exceptionally(t -> {
            plugin.getLogger().log(Level.WARNING, "预热槽位缓存失败 slot=" + slotIndex, t);
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
