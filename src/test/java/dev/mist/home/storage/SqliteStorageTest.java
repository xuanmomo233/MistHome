package dev.mist.home.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.model.HomeVisibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JdbcStorage CRUD 集成测试（SQLite 内存库）。
 * 内存库与单连接生命周期绑定，连接池固定为 1。
 */
class SqliteStorageTest {

    private SqliteStorage storage;
    private HikariDataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        storage = new SqliteStorage(null);   // 不经 init()，plugin 仅用于读配置/数据目录
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:sqlite::memory:");
        hc.setDriverClassName("org.sqlite.JDBC");
        hc.setMaximumPoolSize(1);
        ds = new HikariDataSource(hc);
        storage.dataSource = ds;
        storage.createTables();
    }

    @AfterEach
    void tearDown() {
        ds.close();
    }

    private static UUID uuid() {
        return UUID.randomUUID();
    }

    private Home createHome(UUID owner, int slot) {
        return storage.createHome(owner, "家园" + slot, slot, 0, null,
                0.5, 65.0, 0.5);
    }

    // ---------- home CRUD ----------

    @Test
    void createAndFindByOwner() {
        UUID owner = uuid();
        Home home = createHome(owner, 0);

        assertTrue(home.id() > 0);
        assertEquals(HomeVisibility.PRIVATE, home.visibility());

        Optional<Home> found = storage.findHomeByOwner(owner);
        assertTrue(found.isPresent());
        assertEquals(home.id(), found.get().id());
        assertEquals(owner, found.get().owner());
        assertEquals(0, found.get().slotIndex());
        assertEquals(65.0, found.get().spawnY());
    }

    @Test
    void findByIdAndSlot() {
        Home home = createHome(uuid(), 5);
        assertTrue(storage.findHomeById(home.id()).isPresent());
        assertTrue(storage.findHomeBySlot(5).isPresent());
        assertTrue(storage.findHomeBySlot(6).isEmpty());
        assertTrue(storage.findHomeById(999).isEmpty());
    }

    @Test
    void listHomesInSlotsRange() {
        createHome(uuid(), 0);
        createHome(uuid(), 1);
        createHome(uuid(), 64);   // world 1 的第一个槽位

        List<Home> world0 = storage.listHomesInSlots(0, 64);
        assertEquals(2, world0.size());

        List<Home> world1 = storage.listHomesInSlots(64, 128);
        assertEquals(1, world1.size());
    }

    // ---------- allocateSlot ----------

    @Test
    void allocateSlotPicksMinFree() {
        assertEquals(0, storage.allocateSlot());

        createHome(uuid(), 0);
        createHome(uuid(), 2);      // 故意留空 1
        assertEquals(1, storage.allocateSlot());

        createHome(uuid(), 1);
        assertEquals(3, storage.allocateSlot());
    }

    @Test
    void duplicateSlotThrows() {
        createHome(uuid(), 0);
        DuplicateKeyException e = assertThrows(DuplicateKeyException.class,
                () -> createHome(uuid(), 0));
        assertEquals("slot_index", e.constraint());
    }

    @Test
    void duplicateOwnerThrows() {
        UUID owner = uuid();
        createHome(owner, 0);
        DuplicateKeyException e = assertThrows(DuplicateKeyException.class,
                () -> createHome(owner, 1));
        assertEquals("owner_uuid", e.constraint());
    }

    // ---------- update / delete ----------

    @Test
    void updateHomePersists() {
        Home home = createHome(uuid(), 0);
        home.setName("新名字");
        home.setTierLevel(2);
        home.setVisibility(HomeVisibility.PUBLIC);
        home.setSpawn(1.5, 70.0, -2.5, 90f, 45f);
        storage.updateHome(home);

        Home reloaded = storage.findHomeById(home.id()).orElseThrow();
        assertEquals("新名字", reloaded.name());
        assertEquals(2, reloaded.tierLevel());
        assertEquals(HomeVisibility.PUBLIC, reloaded.visibility());
        assertEquals(90f, reloaded.spawnYaw());
        assertEquals(-2.5, reloaded.spawnZ());
    }

    @Test
    void deleteHomeCascades() {
        Home home = createHome(uuid(), 0);
        UUID member = uuid();
        storage.setRole(home.id(), member, HomeRole.MEMBER);
        storage.ban(home.id(), uuid());

        storage.deleteHome(home.id());

        assertTrue(storage.findHomeById(home.id()).isEmpty());
        assertTrue(storage.listMembers(home.id()).isEmpty());
        assertFalse(storage.isBanned(home.id(), member));
    }

    // ---------- public list ----------

    @Test
    void publicHomesPagination() {
        for (int i = 0; i < 3; i++) {
            Home h = createHome(uuid(), i);
            if (i < 2) {                     // 0、1 设公开
                h.setVisibility(HomeVisibility.PUBLIC);
                storage.updateHome(h);
            }
        }
        assertEquals(2, storage.countPublicHomes());
        assertEquals(2, storage.listPublicHomes(0, 45).size());
        assertEquals(1, storage.listPublicHomes(0, 1).size());
        assertEquals(1, storage.listPublicHomes(1, 1).size());
    }

    // ---------- members ----------

    @Test
    void memberRoleLifecycle() {
        Home home = createHome(uuid(), 0);
        UUID member = uuid();

        assertEquals(HomeRole.VISITOR, storage.roleOf(home.id(), member));

        storage.setRole(home.id(), member, HomeRole.MEMBER);
        assertEquals(HomeRole.MEMBER, storage.roleOf(home.id(), member));
        assertEquals(List.of(member), storage.listMembers(home.id()));

        storage.setRole(home.id(), member, HomeRole.OPERATOR);
        assertEquals(HomeRole.OPERATOR, storage.roleOf(home.id(), member));
        assertEquals(1, storage.listMembers(home.id()).size());   // 覆盖而非重复插入

        storage.removeMember(home.id(), member);
        assertEquals(HomeRole.VISITOR, storage.roleOf(home.id(), member));
    }

    // ---------- bans ----------

    @Test
    void banOverridesMemberAndUnbanRestores() {
        Home home = createHome(uuid(), 0);
        UUID member = uuid();
        storage.setRole(home.id(), member, HomeRole.MEMBER);

        storage.ban(home.id(), member);
        assertTrue(storage.isBanned(home.id(), member));
        assertEquals(HomeRole.BANNED, storage.roleOf(home.id(), member));

        // 解封后成员身份恢复（ban 不清除 member 记录）
        storage.unban(home.id(), member);
        assertFalse(storage.isBanned(home.id(), member));
        assertEquals(HomeRole.MEMBER, storage.roleOf(home.id(), member));
    }

    @Test
    void banIsIdempotent() {
        Home home = createHome(uuid(), 0);
        UUID p = uuid();
        storage.ban(home.id(), p);
        assertDoesNotThrow(() -> storage.ban(home.id(), p));
        assertTrue(storage.isBanned(home.id(), p));
    }
}
