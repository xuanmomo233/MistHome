package dev.mist.home.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC 存储基类（HikariCP 连接池）。表结构见 DESIGN.md。
 */
public abstract class JdbcStorage implements Storage {

    protected final MistHomePlugin plugin;
    protected HikariDataSource dataSource;

    protected JdbcStorage(MistHomePlugin plugin) {
        this.plugin = plugin;
    }

    protected abstract String jdbcUrl();

    @Override
    public void init() throws Exception {
        plugin.getDataFolder().mkdirs();
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl());
        hc.setMaximumPoolSize(plugin.mistConfig().mysqlSection() != null
                ? plugin.mistConfig().mysqlSection().getInt("pool-size", 4) : 4);
        dataSource = new HikariDataSource(hc);
        createTables();
    }

    private void createTables() {
        // TODO: CREATE TABLE IF NOT EXISTS homes / home_members / home_bans
        // 表结构见 DESIGN.md「数据表结构」一节
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ===== 以下为待实现方法，签名已按 Storage 接口冻结 =====

    @Override
    public Optional<Home> findHomeByOwner(UUID owner) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public Optional<Home> findHomeById(long homeId) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public int allocateSlot() {
        throw new UnsupportedOperationException("TODO: SELECT MIN 未占用 slot_index");
    }

    @Override
    public Home createHome(UUID owner, String name, int slotIndex, int tierLevel,
                           String template, double spawnX, double spawnY, double spawnZ) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public void updateHome(Home home) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public void deleteHome(long homeId) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public List<Home> listPublicHomes(int offset, int limit) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public int countPublicHomes() {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public HomeRole roleOf(long homeId, UUID player) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public void setRole(long homeId, UUID player, HomeRole role) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public void removeMember(long homeId, UUID player) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public List<UUID> listMembers(long homeId) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public boolean isBanned(long homeId, UUID player) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public void ban(long homeId, UUID player) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }

    @Override
    public void unban(long homeId, UUID player) {
        throw new UnsupportedOperationException("TODO: JDBC 实现");
    }
}
