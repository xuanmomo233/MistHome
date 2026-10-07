package dev.mist.home.storage;

import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.model.HomeVisibility;

import java.util.List;
import java.util.Map;
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
                    String template, double spawnX, double spawnY, double spawnZ,
                    String server);

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

    // ---------- 跨服待办（BungeeCord 跳服握手） ----------

    /** 记录玩家落地目标服后要去的家园（跳服前写入，目标服 join 时消费） */
    void setPendingAction(UUID player, long homeId);

    /** 取出并删除玩家的待办家园，无返回 empty */
    Optional<Long> pollPendingAction(UUID player);

    // ---------- 存储后端迁移（切换 sqlite/mysql 时全量搬运） ----------

    List<Home> listAllHomes();

    /** 保留 id 的插入（迁移专用）；冲突抛 DuplicateKeyException */
    Home insertHome(Home home);

    /** 成员及角色（迁移用，含 OPERATOR/MEMBER 区分） */
    Map<UUID, HomeRole> listMemberRoles(long homeId);

    List<UUID> listBanned(long homeId);

    /** 跨服归属认领：仅迁移/启动回填使用，把家的 server 标记为指定值 */
    void updateHomeServer(long homeId, String server);
}
