package dev.mist.home.home;

import org.bukkit.World;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.storage.Storage;
import dev.mist.home.world.HomeRegion;
import dev.mist.home.world.SlotAllocator;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 家园领域服务：内存缓存 + Storage 持久化。
 * 家园数量 = 玩家数级别，缓存可全量驻留；仅缓存已加载家园与热点查询。
 */
public class HomeService {

    private final MistHomePlugin plugin;
    private Storage storage;

    /** ownerUuid -> Home */
    private final Map<UUID, Home> byOwner = new ConcurrentHashMap<>();
    /** homeId -> Home */
    private final Map<Long, Home> byId = new ConcurrentHashMap<>();

    public HomeService(MistHomePlugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public Storage storage() {
        return storage;
    }

    public Optional<Home> homeOf(UUID owner) {
        Home cached = byOwner.get(owner);
        if (cached != null) {
            return Optional.of(cached);
        }
        // TODO: 异步查库回填缓存（需调用方异步上下文）
        return Optional.empty();
    }

    /**
     * 由世界坐标反查家园区域（用于保护判定）。
     * 先从坐标反算槽位网格，再查该槽位归属。
     * TODO: 槽位 -> 家园 的反向索引
     */
    public Optional<Home> homeAt(World world, int x, int z) {
        if (!plugin.worldManager().isHomeWorld(world.getName())) {
            return Optional.empty();
        }
        // TODO: 反算 gridX/gridZ -> slotIndex -> byId 查找
        return Optional.empty();
    }

    /** 计算家园的当前区域信息 */
    public HomeRegion regionOf(Home home) {
        var cfg = plugin.mistConfig();
        int usable = cfg.tier(home.tierLevel()).radius();
        return SlotAllocator.regionOf(home.slotIndex(), cfg.homesPerWorld(),
                cfg.slotSize(), cfg.gap(), usable);
    }

    /** 玩家在某家园中的角色；非成员为 VISITOR，被封为 BANNED */
    public HomeRole roleOf(Home home, UUID player) {
        if (home.owner().equals(player)) {
            return HomeRole.OWNER;
        }
        // TODO: 查 members / bans 表（可缓存）
        return HomeRole.VISITOR;
    }

    public void cache(Home home) {
        byOwner.put(home.owner(), home);
        byId.put(home.id(), home);
    }

    public void evict(Home home) {
        byOwner.remove(home.owner());
        byId.remove(home.id());
    }
}
