package dev.mist.home.storage;

import com.zaxxer.hikari.HikariConfig;
import org.bukkit.configuration.ConfigurationSection;
import dev.mist.home.MistHomePlugin;

/**
 * MySQL 存储实现（多服共享数据时使用）。
 */
public class MysqlStorage extends JdbcStorage {

    public MysqlStorage(MistHomePlugin plugin) {
        super(plugin);
    }

    private ConfigurationSection section() {
        ConfigurationSection s = plugin.mistConfig().mysqlSection();
        if (s == null) {
            throw new IllegalStateException("storage.mysql 配置缺失");
        }
        return s;
    }

    @Override
    protected String jdbcUrl() {
        ConfigurationSection s = section();
        return "jdbc:mysql://" + s.getString("host", "127.0.0.1")
                + ":" + s.getInt("port", 3306)
                + "/" + s.getString("database", "misthome")
                + "?useSSL=" + s.getBoolean("use-ssl", false)
                + "&characterEncoding=utf8&serverTimezone=UTC";
    }

    @Override
    protected String driverClass() {
        return "com.mysql.cj.jdbc.Driver";
    }

    @Override
    protected int poolSize() {
        return section().getInt("pool-size", 4);
    }

    @Override
    protected void configurePool(HikariConfig hc) {
        ConfigurationSection s = section();
        hc.setUsername(s.getString("user", "root"));
        hc.setPassword(s.getString("password", ""));
    }

    @Override
    protected String[] ddl() {
        return new String[]{
                """
                CREATE TABLE IF NOT EXISTS homes (
                  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
                  owner_uuid  VARCHAR(36) NOT NULL UNIQUE,
                  name        VARCHAR(64) NOT NULL,
                  slot_index  INT NOT NULL UNIQUE,
                  tier_level  INT NOT NULL DEFAULT 0,
                  template    VARCHAR(64),
                  visibility  VARCHAR(8)  NOT NULL DEFAULT 'PRIVATE',
                  spawn_x     DOUBLE NOT NULL DEFAULT 0,
                  spawn_y     DOUBLE NOT NULL DEFAULT 0,
                  spawn_z     DOUBLE NOT NULL DEFAULT 0,
                  spawn_yaw   FLOAT  NOT NULL DEFAULT 0,
                  spawn_pitch FLOAT  NOT NULL DEFAULT 0,
                  created_at  BIGINT NOT NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """,
                """
                CREATE TABLE IF NOT EXISTS home_members (
                  home_id     BIGINT      NOT NULL,
                  player_uuid VARCHAR(36) NOT NULL,
                  role        VARCHAR(16) NOT NULL,
                  added_at    BIGINT      NOT NULL,
                  PRIMARY KEY (home_id, player_uuid),
                  KEY idx_members_player (player_uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """,
                """
                CREATE TABLE IF NOT EXISTS home_bans (
                  home_id     BIGINT      NOT NULL,
                  player_uuid VARCHAR(36) NOT NULL,
                  banned_at   BIGINT      NOT NULL,
                  PRIMARY KEY (home_id, player_uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """
        };
    }
}
