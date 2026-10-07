package dev.mist.home.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.model.HomeVisibility;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC 存储基类（HikariCP 连接池）。表结构见 DESIGN.md。
 * <p>
 * 方言差异（建表 DDL、驱动类、连接池大小）由子类提供；
 * CRUD 使用标准 SQL，SQLite/MySQL 通用。
 */
public abstract class JdbcStorage implements Storage {

    protected final MistHomePlugin plugin;
    protected HikariDataSource dataSource;

    protected JdbcStorage(MistHomePlugin plugin) {
        this.plugin = plugin;
    }

    /** 单元测试专用（不经 init()，直接注入 dataSource） */
    JdbcStorage() {
        this.plugin = null;
    }

    // ---------- 方言抽象 ----------

    protected abstract String jdbcUrl();

    protected abstract String driverClass();

    /** 建表语句（按方言生成，含 IF NOT EXISTS） */
    protected abstract String[] ddl();

    protected int poolSize() {
        return plugin.mistConfig().mysqlSection() != null
                ? plugin.mistConfig().mysqlSection().getInt("pool-size", 4) : 4;
    }

    /** 子类可补充连接池参数（如 MySQL 账号密码） */
    protected void configurePool(HikariConfig hc) {
    }

    // ---------- 生命周期 ----------

    @Override
    public void init() throws Exception {
        plugin.getDataFolder().mkdirs();
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(jdbcUrl());
        hc.setDriverClassName(driverClass());
        hc.setMaximumPoolSize(poolSize());
        configurePool(hc);
        dataSource = new HikariDataSource(hc);
        createTables();
    }

    void createTables() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String sql : ddl()) {
                st.execute(sql);
            }
        }
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // ---------- 通用工具 ----------

    private Connection conn() throws SQLException {
        return dataSource.getConnection();
    }

    private static Home mapHome(ResultSet rs) throws SQLException {
        return new Home(
                rs.getLong("id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("name"),
                rs.getInt("slot_index"),
                rs.getInt("tier_level"),
                rs.getString("template"),
                HomeVisibility.of(rs.getString("visibility")),
                rs.getDouble("spawn_x"), rs.getDouble("spawn_y"), rs.getDouble("spawn_z"),
                rs.getFloat("spawn_yaw"), rs.getFloat("spawn_pitch"),
                rs.getLong("created_at"));
    }

    private static Optional<Home> firstHome(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(mapHome(rs)) : Optional.empty();
        }
    }

    private static List<Home> listHomes(PreparedStatement ps) throws SQLException {
        List<Home> result = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapHome(rs));
            }
        }
        return result;
    }

    private static StorageException wrap(SQLException e) {
        if (isUniqueViolation(e)) {
            return new DuplicateKeyException(parseConstraint(e.getMessage()), e.getMessage(), e);
        }
        return new StorageException(e.getMessage(), e);
    }

    private static boolean isUniqueViolation(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        int code = e.getErrorCode();
        // MySQL: 1062 ER_DUP_ENTRY；SQLite: 19 SQLITE_CONSTRAINT / 2067 SQLITE_CONSTRAINT_UNIQUE
        return code == 19 || code == 2067 || code == 1062;
    }

    private static String parseConstraint(String message) {
        if (message == null) {
            return null;
        }
        if (message.contains("slot_index")) {
            return "slot_index";
        }
        if (message.contains("owner_uuid")) {
            return "owner_uuid";
        }
        return null;
    }

    // ---------- home ----------

    @Override
    public Optional<Home> findHomeByOwner(UUID owner) {
        String sql = "SELECT * FROM homes WHERE owner_uuid = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            return firstHome(ps);
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public Optional<Home> findHomeById(long homeId) {
        String sql = "SELECT * FROM homes WHERE id = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, homeId);
            return firstHome(ps);
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public Optional<Home> findHomeBySlot(int slotIndex) {
        String sql = "SELECT * FROM homes WHERE slot_index = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, slotIndex);
            return firstHome(ps);
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public List<Home> listHomesInSlots(int fromInclusive, int toExclusive) {
        String sql = "SELECT * FROM homes WHERE slot_index >= ? AND slot_index < ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fromInclusive);
            ps.setInt(2, toExclusive);
            return listHomes(ps);
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public int allocateSlot() {
        // 最小未占用槽位：候选集 = {0} ∪ {已占用+1}，过滤掉仍被占用的候选后取最小
        String sql = "SELECT MIN(t.candidate) AS next_slot FROM ("
                + "SELECT 0 AS candidate UNION ALL SELECT h.slot_index + 1 FROM homes h"
                + ") t WHERE NOT EXISTS (SELECT 1 FROM homes h2 WHERE h2.slot_index = t.candidate)";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public Home createHome(UUID owner, String name, int slotIndex, int tierLevel,
                           String template, double spawnX, double spawnY, double spawnZ) {
        String sql = "INSERT INTO homes(owner_uuid, name, slot_index, tier_level, template,"
                + " visibility, spawn_x, spawn_y, spawn_z, spawn_yaw, spawn_pitch, created_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
        long createdAt = System.currentTimeMillis();
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner.toString());
            ps.setString(2, name);
            ps.setInt(3, slotIndex);
            ps.setInt(4, tierLevel);
            ps.setString(5, template);
            ps.setString(6, HomeVisibility.PRIVATE.name());
            ps.setDouble(7, spawnX);
            ps.setDouble(8, spawnY);
            ps.setDouble(9, spawnZ);
            ps.setFloat(10, 0f);
            ps.setFloat(11, 0f);
            ps.setLong(12, createdAt);
            ps.executeUpdate();

            long id = -1;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys != null && keys.next()) {
                    id = keys.getLong(1);
                }
            }
            return new Home(id, owner, name, slotIndex, tierLevel, template,
                    HomeVisibility.PRIVATE, spawnX, spawnY, spawnZ, 0f, 0f, createdAt);
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public void updateHome(Home home) {
        String sql = "UPDATE homes SET name=?, tier_level=?, template=?, visibility=?,"
                + " spawn_x=?, spawn_y=?, spawn_z=?, spawn_yaw=?, spawn_pitch=? WHERE id=?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, home.name());
            ps.setInt(2, home.tierLevel());
            ps.setString(3, home.template());
            ps.setString(4, home.visibility().name());
            ps.setDouble(5, home.spawnX());
            ps.setDouble(6, home.spawnY());
            ps.setDouble(7, home.spawnZ());
            ps.setFloat(8, home.spawnYaw());
            ps.setFloat(9, home.spawnPitch());
            ps.setLong(10, home.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public void deleteHome(long homeId) {
        try (Connection c = conn()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement delBans = c.prepareStatement(
                         "DELETE FROM home_bans WHERE home_id=?");
                 PreparedStatement delMembers = c.prepareStatement(
                         "DELETE FROM home_members WHERE home_id=?");
                 PreparedStatement delHome = c.prepareStatement(
                         "DELETE FROM homes WHERE id=?")) {
                delBans.setLong(1, homeId);
                delBans.executeUpdate();
                delMembers.setLong(1, homeId);
                delMembers.executeUpdate();
                delHome.setLong(1, homeId);
                delHome.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public List<Home> listPublicHomes(int offset, int limit) {
        String sql = "SELECT * FROM homes WHERE visibility='PUBLIC'"
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            return listHomes(ps);
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public int countPublicHomes() {
        String sql = "SELECT COUNT(*) FROM homes WHERE visibility='PUBLIC'";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    // ---------- members ----------

    @Override
    public HomeRole roleOf(long homeId, UUID player) {
        if (isBanned(homeId, player)) {
            return HomeRole.BANNED;
        }
        String sql = "SELECT role FROM home_members WHERE home_id=? AND player_uuid=?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, homeId);
            ps.setString(2, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? HomeRole.valueOf(rs.getString(1)) : HomeRole.VISITOR;
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public void setRole(long homeId, UUID player, HomeRole role) {
        try (Connection c = conn()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement(
                         "DELETE FROM home_members WHERE home_id=? AND player_uuid=?");
                 PreparedStatement ins = c.prepareStatement(
                         "INSERT INTO home_members(home_id, player_uuid, role, added_at)"
                                 + " VALUES(?,?,?,?)")) {
                del.setLong(1, homeId);
                del.setString(2, player.toString());
                del.executeUpdate();
                ins.setLong(1, homeId);
                ins.setString(2, player.toString());
                ins.setString(3, role.name());
                ins.setLong(4, System.currentTimeMillis());
                ins.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public void removeMember(long homeId, UUID player) {
        String sql = "DELETE FROM home_members WHERE home_id=? AND player_uuid=?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, homeId);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public List<UUID> listMembers(long homeId) {
        String sql = "SELECT player_uuid FROM home_members WHERE home_id=?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, homeId);
            List<UUID> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(UUID.fromString(rs.getString(1)));
                }
            }
            return result;
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    // ---------- bans ----------

    @Override
    public boolean isBanned(long homeId, UUID player) {
        String sql = "SELECT 1 FROM home_bans WHERE home_id=? AND player_uuid=?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, homeId);
            ps.setString(2, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public void ban(long homeId, UUID player) {
        String sql = "INSERT INTO home_bans(home_id, player_uuid, banned_at) VALUES(?,?,?)";
        try (Connection c = conn()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement(
                         "DELETE FROM home_bans WHERE home_id=? AND player_uuid=?");
                 PreparedStatement ins = c.prepareStatement(sql)) {
                del.setLong(1, homeId);
                del.setString(2, player.toString());
                del.executeUpdate();
                ins.setLong(1, homeId);
                ins.setString(2, player.toString());
                ins.setLong(3, System.currentTimeMillis());
                ins.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public void unban(long homeId, UUID player) {
        String sql = "DELETE FROM home_bans WHERE home_id=? AND player_uuid=?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, homeId);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }
}
