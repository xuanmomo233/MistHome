package dev.mist.home;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import dev.mist.home.boundary.BorderService;
import dev.mist.home.channel.BungeeChannel;
import dev.mist.home.command.MistHomeCommand;
import dev.mist.home.config.MistConfig;
import dev.mist.home.economy.EconomyService;
import dev.mist.home.gui.MenuListener;
import dev.mist.home.home.HomeService;
import dev.mist.home.invite.InviteManager;
import dev.mist.home.protect.ProtectionListener;
import dev.mist.home.storage.DuplicateKeyException;
import dev.mist.home.storage.MysqlStorage;
import dev.mist.home.storage.SqliteStorage;
import dev.mist.home.storage.Storage;
import dev.mist.home.teleport.TeleportService;
import dev.mist.home.template.TemplateService;
import dev.mist.home.world.HomeWorldManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
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
    private BungeeChannel bungee;
    private dev.mist.home.importer.SelfHomeImporter selfHomeImporter;

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
            migrateStorageIfNeeded();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "存储初始化/迁移失败，插件停用", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // BungeeCord 消息通道（跨服跳服 + 缓存失效广播；未启用时为 no-op）
        bungee = new BungeeChannel(this);
        bungee.register();

        // 家园领域服务（世界池启动时的崩溃核对依赖它）
        homeService = new HomeService(this, storage);

        // 世界池管理（停放台账/按需加载/卸载归档）
        worldManager = new HomeWorldManager(this);
        worldManager.start();
        claimLocalHomes();

        // 软依赖钩子
        economy = new EconomyService(this);
        economy.hook();
        templates = new TemplateService(this);
        templates.hook();
        borders = new BorderService(this);
        borders.hook();

        invites = new InviteManager(this);

        // 旧插件导入器（SelfHome 世界/yml → 家园存档）
        selfHomeImporter = new dev.mist.home.importer.SelfHomeImporter(this);

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
        if (bungee != null) {
            bungee.unregister();
        }
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
     * 存储后端切换自动迁移。
     * <p>
     * storage-type.txt 标记文件记录上一次使用的后端；与当前 storage.type 不一致时，
     * 实例化旧后端全量导出 homes/成员角色/封禁并写入新库（保留 home id，
     * 使插件目录下的家园存档与 DB 记录继续对应），完成后更新标记。
     * 迁移失败抛异常中止启动——宁可停服也不在新空库上继续运行导致数据分叉。
     */
    private void migrateStorageIfNeeded() {
        File marker = new File(getDataFolder(), "storage-type.txt");
        String current = mistConfig.storageType().trim().toLowerCase();
        if (!marker.exists()) {
            writeStorageMarker(marker, current);
            return;
        }
        String previous;
        try {
            previous = Files.readString(marker.toPath()).trim().toLowerCase();
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "读取存储标记失败，按当前后端继续", e);
            writeStorageMarker(marker, current);
            return;
        }
        if (previous.isEmpty() || previous.equals(current)) {
            if (previous.isEmpty()) {
                writeStorageMarker(marker, current);
            }
            return;
        }
        getLogger().info("检测到存储后端切换：" + previous + " -> " + current + "，开始迁移…");
        Storage old = "mysql".equals(previous) ? new MysqlStorage(this) : new SqliteStorage(this);
        try {
            old.init();
            int homes = 0;
            int roles = 0;
            int bans = 0;
            int dupHomes = 0;
            for (dev.mist.home.model.Home h : old.listAllHomes()) {
                try {
                    storage.insertHome(h);
                    homes++;
                } catch (DuplicateKeyException e) {
                    dupHomes++;
                }
                for (var e : old.listMemberRoles(h.id()).entrySet()) {
                    storage.setRole(h.id(), e.getKey(), e.getValue());
                    roles++;
                }
                for (UUID u : old.listBanned(h.id())) {
                    storage.ban(h.id(), u);
                    bans++;
                }
            }
            writeStorageMarker(marker, current);
            getLogger().info("存储迁移完成：家园 +" + homes
                    + (dupHomes > 0 ? "（跳过已存在 " + dupHomes + "）" : "")
                    + "，成员/角色 " + roles + "，封禁 " + bans);
        } catch (Exception e) {
            throw new IllegalStateException("存储迁移失败（" + previous + " -> " + current
                    + "）。请检查旧库连接配置；若确认放弃迁移，删除 "
                    + marker.getAbsolutePath() + " 后重启", e);
        } finally {
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 归属认领：跨服开启后，把 server 列为空（旧版本数据/迁移数据）
     * 且归档目录在本机的家园标记为本服所有。
     * 归档不在本机的空标记家保持 ""，访问时按"归属未登记"拒绝本地停放。
     */
    private void claimLocalHomes() {
        if (!mistConfig.crossServerEnabled()) {
            return;
        }
        String local = mistConfig.serverName();
        try {
            int claimed = 0;
            for (dev.mist.home.model.Home h : storage.listAllHomes()) {
                if (!h.server().isEmpty()) {
                    continue;
                }
                if (!worldManager.archive().hasArchive(h)) {
                    continue;
                }
                storage.updateHomeServer(h.id(), local);
                claimed++;
            }
            if (claimed > 0) {
                getLogger().info("跨服：已将 " + claimed + " 个归档在本机的家园归属到 " + local);
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "家园归属认领失败（不影响启动，访问时按未登记处理）", e);
        }
    }

    private void writeStorageMarker(File marker, String type) {
        try {
            getDataFolder().mkdirs();
            Files.writeString(marker.toPath(), type);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "写入存储标记失败", e);
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
        if (!next.storageType().equalsIgnoreCase(mistConfig.storageType())
                || next.crossServerEnabled() != mistConfig.crossServerEnabled()
                || !next.serverName().equals(mistConfig.serverName())) {
            getLogger().warning("storage/cross-server 配置变更需重启生效"
                    + "（热重载不切换存储后端、不迁移数据、不改变本服名）");
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

    public BungeeChannel bungee() {
        return bungee;
    }

    public dev.mist.home.importer.SelfHomeImporter selfHomeImporter() {
        return selfHomeImporter;
    }
}
