package dev.mist.home.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import dev.mist.home.model.HomeTier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * config.yml 读取封装。
 */
public class MistConfig {

    private final FileConfiguration config;
    private List<HomeTier> tiers = List.of(new HomeTier(0, "默认", 64, 0));

    public MistConfig(FileConfiguration config) {
        this.config = config;
        loadTiers();
    }

    private void loadTiers() {
        List<Map<?, ?>> list = config.getMapList("tiers");
        if (list.isEmpty()) {
            return;
        }
        List<HomeTier> parsed = new ArrayList<>();
        for (Map<?, ?> map : list) {
            int level = intOf(map.get("level"), parsed.size());
            Object nameObj = map.get("name");
            String name = nameObj != null ? String.valueOf(nameObj) : "档位" + level;
            int radius = intOf(map.get("radius"), 64);
            if (radius > slotSize() / 2) {
                throw new IllegalArgumentException("tiers." + level + ".radius (" + radius
                        + ") 超过 slot-size/2 上限 (" + slotSize() / 2 + ")");
            }
            double price = map.get("upgrade-price") instanceof Number n ? n.doubleValue() : 0;
            parsed.add(new HomeTier(level, name, radius, price));
        }
        parsed.sort(Comparator.comparingInt(HomeTier::level));
        this.tiers = List.copyOf(parsed);
    }

    /** 校验关键配置：家园数/网格、槽位对齐、档位半径等 */
    public void validate() {
        int hpw = homesPerWorld();
        int root = (int) Math.round(Math.sqrt(hpw));
        if (root * root != hpw) {
            throw new IllegalArgumentException("world.homes-per-world 必须是完全平方数（当前：" + hpw + "）");
        }
        // 槽位必须对齐 region 文件边界（512 的倍数），家园数据才能独占 mca 文件组
        if (slotSize() % 512 != 0 || slotSize() < 512) {
            throw new IllegalArgumentException("world.slot-size 必须是 512 的倍数（region 对齐），当前：" + slotSize());
        }
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("tiers 配置不能为空");
        }
    }

    /**
     * 槽位内边缘留白 = slotSize/2 - maxTierRadius。
     * 小于模拟距离（160）时，玩家走到可用区边缘会把邻居空槽区块虚空生成进内存。
     */
    public int slotMargin() {
        return slotSize() / 2 - maxTier().radius();
    }

    /** 模拟距离（区块），用于污染判定与隔离告警 */
    public int simulationDistanceChunks() {
        return config.getInt("world.simulation-distance", 10);
    }

    /** 模拟距离（格） */
    public int simulationDistanceBlocks() {
        return simulationDistanceChunks() * 16;
    }

    private static int intOf(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    // ---------- world ----------

    public String worldPrefix() {
        return config.getString("world.name-prefix", "misthome_");
    }

    /** 固定指定的家园世界名列表（有序，索引即 worldIndex） */
    public List<String> poolWorldNames() {
        return config.getStringList("world.worlds");
    }

    /** 池满时是否自动生成新家园世界 */
    public boolean autoGrow() {
        return config.getBoolean("world.auto-grow", true);
    }

    /** 世界池容量上限（含自动生成），0 = 不限 */
    public int maxWorlds() {
        return config.getInt("world.max-worlds", 16);
    }

    /** 池世界环境 */
    public org.bukkit.World.Environment environment() {
        String s = config.getString("world.environment", "NORMAL");
        try {
            return org.bukkit.World.Environment.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return org.bukkit.World.Environment.NORMAL;
        }
    }

    /** 停放台账文件名（相对插件数据文件夹） */
    public String poolStateFile() {
        return config.getString("world.pool-state-file", "pool-state.json");
    }

    public int homesPerWorld() {
        return Math.max(1, config.getInt("world.homes-per-world", 16));
    }

    public int slotSize() {
        return config.getInt("world.slot-size", 1024);
    }

    public int unloadDelaySeconds() {
        return config.getInt("world.unload-delay-seconds", 300);
    }

    public int sweepIntervalSeconds() {
        return config.getInt("world.sweep-interval-seconds", 20);
    }

    // ---------- archive ----------

    /** 家园存档根目录（相对插件数据文件夹） */
    public String archiveDir() {
        return config.getString("archive.dir", "homes");
    }

    /** 归档成功后是否删除世界文件夹 */
    public boolean deleteWorldOnUnload() {
        return config.getBoolean("archive.delete-world-on-unload", true);
    }

    // ---------- tiers ----------

    public List<HomeTier> tiers() {
        return tiers;
    }

    public HomeTier tier(int level) {
        for (HomeTier t : tiers) {
            if (t.level() == level) {
                return t;
            }
        }
        return tiers.get(0);
    }

    public HomeTier maxTier() {
        return tiers.get(tiers.size() - 1);
    }

    public HomeTier nextTier(int currentLevel) {
        for (HomeTier t : tiers) {
            if (t.level() > currentLevel) {
                return t;
            }
        }
        return null;
    }

    // ---------- storage ----------

    public String storageType() {
        return config.getString("storage.type", "sqlite");
    }

    public ConfigurationSection mysqlSection() {
        return config.getConfigurationSection("storage.mysql");
    }

    // ---------- economy ----------

    public boolean economyEnabled() {
        return config.getBoolean("economy.enabled", true);
    }

    public double createPrice() {
        return config.getDouble("economy.create-price", 0);
    }

    // ---------- teleport ----------

    public int warmupSeconds() {
        return config.getInt("teleport.warmup-seconds", 3);
    }

    public boolean interruptOnMove() {
        return config.getBoolean("teleport.interrupt-on-move", true);
    }

    public boolean interruptOnDamage() {
        return config.getBoolean("teleport.interrupt-on-damage", true);
    }

    public int cooldownSeconds() {
        return config.getInt("teleport.cooldown-seconds", 60);
    }

    // ---------- template ----------

    public String templateDir() {
        return config.getString("template.dir", "templates");
    }

    public int fallbackPlatformSize() {
        return config.getInt("template.fallback-platform-size", 5);
    }

    public String fallbackPlatformBlock() {
        return config.getString("template.fallback-platform-block", "GRASS_BLOCK");
    }

    // ---------- boundary ----------

    public boolean preferProtocolLib() {
        return config.getBoolean("boundary.prefer-protocollib", true);
    }

    public int particleIntervalTicks() {
        return config.getInt("boundary.particle-interval-ticks", 20);
    }

    /** 强制叠加粒子边界（PL 发包不可见时排障用） */
    public boolean forceParticles() {
        return config.getBoolean("boundary.force-particles", false);
    }

    /** 边界判定调试日志 */
    /** 距可用区边缘多少格内提前显示边界 */
    public int approachDistance() {
        return config.getInt("boundary.approach-distance", 24);
    }

    public boolean borderDebug() {
        return config.getBoolean("boundary.debug", false);
    }

    // ---------- visit ----------

    public int inviteExpireSeconds() {
        return config.getInt("visit.invite-expire-seconds", 30);
    }

    public int publicPageSize() {
        return config.getInt("visit.public-page-size", 45);
    }

    public String prefix() {
        // 配置里用 & 写颜色符号（YAML 里 § 容易出编码问题），发出前转成 §
        return org.bukkit.ChatColor.translateAlternateColorCodes('&',
                config.getString("messages.prefix", "&8[&bMistHome&8] &r"));
    }
}
