package dev.mist.home.storage;

import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.model.HomeVisibility;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 家园数据存储层抽象。实现：SQLite（默认）/ MySQL。
 * 所有方法均在异步线程调用，实现方需保证线程安全（HikariCP）。
 */
public interface Storage {

    void init() throws Exception;

    void close();

    // ---------- home ----------

    Optional<Home> findHomeByOwner(UUID owner);

    Optional<Home> findHomeById(long homeId);

    /** 按全局槽位索引查家园 */
    Optional<Home> findHomeBySlot(int slotIndex);

    /**
     * 批量读取指定槽位区间内的家园（世界加载时预热缓存用）。
     * 区间 [fromInclusive, toExclusive)。
     */
    List<Home> listHomesInSlots(int fromInclusive, int toExclusive);

    /** 分配下一个空闲的全局槽位索引（最小未占用值） */
    int allocateSlot();

    Home createHome(UUID owner, String name, int slotIndex, int tierLevel,
                    String template, double spawnX, double spawnY, double spawnZ);

    void updateHome(Home home);

    void deleteHome(long homeId);

    /** 公共家园列表（按创建时间倒序分页） */
    List<Home> listPublicHomes(int offset, int limit);

    int countPublicHomes();

    // ---------- members ----------

    HomeRole roleOf(long homeId, UUID player);

    void setRole(long homeId, UUID player, HomeRole role);

    void removeMember(long homeId, UUID player);

    List<UUID> listMembers(long homeId);

    // ---------- bans ----------

    boolean isBanned(long homeId, UUID player);

    void ban(long homeId, UUID player);

    void unban(long homeId, UUID player);
}
