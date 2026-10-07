package dev.mist.home;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import dev.mist.home.boundary.BorderService;
import dev.mist.home.command.MistHomeCommand;
import dev.mist.home.config.MistConfig;
import dev.mist.home.economy.EconomyService;
import dev.mist.home.gui.MenuListener;
import dev.mist.home.home.HomeService;
import dev.mist.home.invite.InviteManager;
import dev.mist.home.protect.ProtectionListener;
import dev.mist.home.storage.MysqlStorage;
import dev.mist.home.storage.SqliteStorage;
import dev.mist.home.storage.Storage;
import dev.mist.home.teleport.TeleportService;
import dev.mist.home.template.TemplateService;
import dev.mist.home.world.HomeWorldManager;

import java.util.logging.Level;

/**
 * MistHome - 玩家家园插件。
 * 共享虚空世界分区 + 副本式按需加载/无人超时卸载。
 * 目标平台：Mohist 1.20.1（Bukkit/Spigot API）。
 */
public final class MistHomePlugin extends JavaPlugin {

    private MistConfig mistConfig;
    private HomeWorldManager worldManager;
    private HomeService homeService;
    private EconomyService economy;
    private TemplateService templates;
    private BorderService borders;
    private InviteManager invites;
    private TeleportService teleportService;
    private Storage storage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mistConfig = new MistConfig(getConfig());
        try {
            mistConfig.validate();
        } catch (IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "配置文件校验失败，插件停用: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 存储层
        storage = "mysql".equalsIgnoreCase(mistConfig.storageType())
                ? new MysqlStorage(this) : new SqliteStorage(this);
        try {
            storage.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "存储初始化失败，插件停用", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 世界管理（副本式加载/卸载）
        worldManager = new HomeWorldManager(this);
        worldManager.start();

        // 家园领域服务
        homeService = new HomeService(this, storage);

        // 软依赖钩子
        economy = new EconomyService(this);
        economy.hook();
        templates = new TemplateService(this);
        templates.hook();
        borders = new BorderService(this);
        borders.hook();

        invites = new InviteManager(this);

        // 传送服务（吟唱/冷却/打断，本身是监听器）
        teleportService = new TeleportService(this);
        Bukkit.getPluginManager().registerEvents(teleportService, this);

        // 监听器
        Bukkit.getPluginManager().registerEvents(new ProtectionListener(this, homeService), this);
        Bukkit.getPluginManager().registerEvents(new MenuListener(), this);

        // 命令
        PluginCommand cmd = getCommand("misthome");
        if (cmd != null) {
            MistHomeCommand executor = new MistHomeCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getLogger().info("MistHome 已启用");
    }

    @Override
    public void onDisable() {
        if (worldManager != null) {
            worldManager.shutdown();
        }
        if (homeService != null) {
            homeService.shutdown();
        }
        if (borders != null) {
            borders.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
    }

    public MistConfig mistConfig() {
        return mistConfig;
    }

    /** 热重载配置（admin reload）。校验失败抛 IllegalArgumentException */
    public void reloadMistConfig() {
        reloadConfig();
        MistConfig next = new MistConfig(getConfig());
        next.validate();
        this.mistConfig = next;
    }

    public HomeWorldManager worldManager() {
        return worldManager;
    }

    public HomeService homeService() {
        return homeService;
    }

    public EconomyService economy() {
        return economy;
    }

    public TemplateService templates() {
        return templates;
    }

    public BorderService borders() {
        return borders;
    }

    public InviteManager invites() {
        return invites;
    }

    public TeleportService teleportService() {
        return teleportService;
    }

    public Storage storage() {
        return storage;
    }
}
