package dev.mist.home.template;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.world.HomeRegion;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 家园模板服务。
 * <p>
 * 优先走 WorldEdit 粘贴 plugins/MistHome/templates 下的 .schem；
 * 未安装 WorldEdit 时降级为在区域中心生成一个小平台。
 */
public class TemplateService {

    private final MistHomePlugin plugin;
    private boolean worldEditAvailable;

    public TemplateService(MistHomePlugin plugin) {
        this.plugin = plugin;
    }

    public void hook() {
        worldEditAvailable = Bukkit.getPluginManager().getPlugin("WorldEdit") != null;
        if (worldEditAvailable) {
            plugin.getLogger().info("已检测到 WorldEdit，模板粘贴可用");
        } else {
            plugin.getLogger().info("未检测到 WorldEdit，家园将使用降级平台");
        }
    }

    public boolean worldEditAvailable() {
        return worldEditAvailable;
    }

    /** 列出可用模板文件名（不含扩展名） */
    public List<String> listTemplates() {
        File dir = new File(plugin.getDataFolder(), plugin.mistConfig().templateDir());
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".schem"));
        List<String> names = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                names.add(f.getName().substring(0, f.getName().length() - ".schem".length()));
            }
        }
        return names;
    }

    /**
     * 在指定家园区域内粘贴模板（异步完成）。
     * TODO: WorldEdit Clipboard API 粘贴实现（EditSession + ClipboardHolder），
     * 注意 Mohist 上 WE 需为 Bukkit 插件版。
     */
    public CompletableFuture<Void> paste(String template, World world, HomeRegion region) {
        if (!worldEditAvailable || template == null) {
            return CompletableFuture.runAsync(() ->
                    pasteFallbackPlatform(world, region),
                    r -> Bukkit.getScheduler().runTask(plugin, r));
        }
        // TODO: 读取 templates/<name>.schem -> ClipboardFormat -> ClipboardReader
        //       -> EditSession paste 到 region 中心，Operation 可分批提交防卡服
        return CompletableFuture.completedFuture(null);
    }

    /** 降级：在区域中心生成 fallback-platform-size 边长的平台 */
    public void pasteFallbackPlatform(World world, HomeRegion region) {
        int size = plugin.mistConfig().fallbackPlatformSize();
        Material material = Material.matchMaterial(plugin.mistConfig().fallbackPlatformBlock());
        if (material == null) {
            material = Material.GRASS_BLOCK;
        }
        int y = 64;
        int half = size / 2;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                world.getBlockAt(region.centerX() + dx, y, region.centerZ() + dz)
                        .setType(material, false);
            }
        }
    }
}
