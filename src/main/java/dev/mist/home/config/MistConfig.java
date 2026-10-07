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
        int maxRadius = slotSize() / 2;
        List<HomeTier> parsed = new ArrayList<>();
        for (Map<?, ?> map : list) {
            int level = intOf(map.get("level"), parsed.size());
            Object nameObj = map.get("name");
            String name = nameObj != null ? String.valueOf(nameObj) : "档位" + level;
            int radius = intOf(map.get("radius"), 64);
            if (radius > maxRadius) {
                throw new IllegalArgumentException("tiers." + level + ".radius (" + radius
                        + ") 超过 slot-size/2 上限 (" + maxRadius + ")");
            }
            double price = map.get("upgrade-price") instanceof Number n ? n.doubleValue() : 0;
            parsed.add(new HomeTier(level, name, radius, price));
        }
        parsed.sort(Comparator.comparingInt(HomeTier::level));
        this.tiers = List.copyOf(parsed);
    }

    /** 校验关键配置：家园数/网格、档位半径等 */
    public void validate() {
        int hpw = homesPerWorld();
        int root = (int) Math.round(Math.sqrt(hpw));
        if (root * root != hpw) {
            throw new IllegalArgumentException("world.homes-per-world 必须是完全平方数（当前：" + hpw + "）");
        }
        if (slotSize() < 2) {
            throw new IllegalArgumentException("world.slot-size 必须 >= 2");
        }
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("tiers 配置不能为空");
        }
    }

    private static int intOf(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    // ---------- world ----------

    public String worldPrefix() {
        return config.getString("world.name-prefix", "misthome_");
    }

    public int homesPerWorld() {
        return Math.max(1, config.getInt("world.homes-per-world", 64));
    }

    public int slotSize() {
        return config.getInt("world.slot-size", 512);
    }

    public int gap() {
        return config.getInt("world.gap", 64);
    }

    public int unloadDelaySeconds() {
        return config.getInt("world.unload-delay-seconds", 300);
    }

    public int sweepIntervalSeconds() {
        return config.getInt("world.sweep-interval-seconds", 20);
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

    // ---------- visit ----------

    public int inviteExpireSeconds() {
        return config.getInt("visit.invite-expire-seconds", 30);
    }

    public int publicPageSize() {
        return config.getInt("visit.public-page-size", 45);
    }

    public String prefix() {
        return config.getString("messages.prefix", "&8[&bMistHome&8] &r");
    }
}
