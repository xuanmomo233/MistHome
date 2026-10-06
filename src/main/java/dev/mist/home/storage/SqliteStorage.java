package dev.mist.home.storage;

import dev.mist.home.MistHomePlugin;

import java.io.File;

/**
 * SQLite 存储实现（默认）。数据库文件：plugins/MistHome/misthome.db
 * TODO: 建表 SQL、CRUD 实现 —— 见 DESIGN.md 数据表结构一节。
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
}
