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
        try {
            mistConfig = new MistConfig(getConfig());
            mistConfig.validate();
        } catch (IllegalArgumentException e) {
            getLogger().log(Level.SEVERE, "配置文件校验失败，插件停用: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        warnIsolation();

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

        // 家园领域服务（世界池启动时的崩溃核对依赖它）
        homeService = new HomeService(this, storage);

        // 世界池管理（停放台账/按需加载/卸载归档）
        worldManager = new HomeWorldManager(this);
        worldManager.start();

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

    /**
     * 隔离告警：相邻家园可用区域边缘距离 = slotSize - 2*maxRadius。
     * 小于模拟距离时，两家园贴边的机械会互相加载 tick。
     */
    private void warnIsolation() {
        int simDist = mistConfig.simulationDistanceChunks() * 16;
        int margin = mistConfig.slotSize() - 2 * mistConfig.maxTier().radius();
        if (margin < simDist) {
            getLogger().warning("相邻家园可用区域边缘距离仅 " + margin + " 格"
                    + "，小于模拟距离 " + simDist + " 格（simulation-distance="
                    + mistConfig.simulationDistanceChunks() + "）。"
                    + "两家贴边时机械/红石会互相加载，且空槽可能被污染。"
                    + "建议调低家园最大档半径或增大槽位边长。");
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
        if (worldManager != null
                && (next.homesPerWorld() != mistConfig.homesPerWorld()
                        || next.slotSize() != mistConfig.slotSize())) {
            getLogger().warning("homes-per-world/slot-size 运行中变更会导致"
                    + "已停放家园的槽位映射错乱，请重启服务器后再修改几何参数");
        }
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
