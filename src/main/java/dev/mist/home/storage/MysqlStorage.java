package dev.mist.home.storage;

import org.bukkit.configuration.ConfigurationSection;
import dev.mist.home.MistHomePlugin;

/**
 * MySQL 存储实现（多服共享数据时使用）。
 */
public class MysqlStorage extends JdbcStorage {

    public MysqlStorage(MistHomePlugin plugin) {
        super(plugin);
    }

    @Override
    protected String jdbcUrl() {
        ConfigurationSection s = plugin.mistConfig().mysqlSection();
        if (s == null) {
            throw new IllegalStateException("storage.mysql 配置缺失");
        }
        return "jdbc:mysql://" + s.getString("host", "127.0.0.1")
                + ":" + s.getInt("port", 3306)
                + "/" + s.getString("database", "misthome")
                + "?useSSL=" + s.getBoolean("use-ssl", false)
                + "&characterEncoding=utf8&serverTimezone=UTC";
    }
}
