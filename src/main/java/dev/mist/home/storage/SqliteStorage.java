package dev.mist.home.storage;

import dev.mist.home.MistHomePlugin;

import java.io.File;

/**
 * SQLite 存储实现（默认）。数据库文件：plugins/MistHome/misthome.db
 * <p>
 * SQLite 是单写者模型，连接池固定为 1 串行化写操作，
 * 避免 "database is locked"。
 */
public class SqliteStorage extends JdbcStorage {

    public SqliteStorage(MistHomePlugin plugin) {
        super(plugin);
    }

    @Override
    protected String jdbcUrl() {
        File db = new File(plugin.getDataFolder(), "misthome.db");
        return "jdbc:sqlite:" + db.getAbsolutePath();
    }

    @Override
    protected String driverClass() {
        return "org.sqlite.JDBC";
    }

    @Override
    protected int poolSize() {
        return 1;
    }

    @Override
    protected String[] ddl() {
        return new String[]{
                """
                CREATE TABLE IF NOT EXISTS homes (
                  id          INTEGER PRIMARY KEY AUTOINCREMENT,
                  owner_uuid  VARCHAR(36) NOT NULL UNIQUE,
                  name        VARCHAR(64) NOT NULL,
                  slot_index  INTEGER NOT NULL UNIQUE,
                  tier_level  INTEGER NOT NULL DEFAULT 0,
                  template    VARCHAR(64),
                  visibility  VARCHAR(8)  NOT NULL DEFAULT 'PRIVATE',
                  spawn_x     REAL    NOT NULL DEFAULT 0,
                  spawn_y     REAL    NOT NULL DEFAULT 0,
                  spawn_z     REAL    NOT NULL DEFAULT 0,
                  spawn_yaw   REAL    NOT NULL DEFAULT 0,
                  spawn_pitch REAL    NOT NULL DEFAULT 0,
                  created_at  INTEGER NOT NULL,
                  server      VARCHAR(64) NOT NULL DEFAULT ''
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS home_members (
                  home_id     INTEGER     NOT NULL,
                  player_uuid VARCHAR(36) NOT NULL,
                  role        VARCHAR(16) NOT NULL,
                  added_at    INTEGER     NOT NULL,
                  PRIMARY KEY (home_id, player_uuid)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS home_bans (
                  home_id     INTEGER     NOT NULL,
                  player_uuid VARCHAR(36) NOT NULL,
                  banned_at   INTEGER     NOT NULL,
                  PRIMARY KEY (home_id, player_uuid)
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS pending_actions (
                  player_uuid VARCHAR(36) NOT NULL,
                  home_id     INTEGER     NOT NULL,
                  created_at  INTEGER     NOT NULL,
                  PRIMARY KEY (player_uuid)
                )
                """,
                "CREATE INDEX IF NOT EXISTS idx_members_player ON home_members(player_uuid)"
        };
    }
}
