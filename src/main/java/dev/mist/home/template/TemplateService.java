package dev.mist.home.template;

import org.bukkit.Bukkit;
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
    private volatile boolean pasting;

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

    public boolean isPasting() {
        return pasting;
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
     * 在指定家园区域内粘贴模板（异步完成，实际在主线程执行方块操作）。
     * TODO: WorldEdit Clipboard API 粘贴实现（EditSession + ClipboardHolder），
     * 注意 Mohist 上 WE 需为 Bukkit 插件版。
     */
    public CompletableFuture<Void> paste(String template, World world, HomeRegion region) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            pasting = true;
            try {
                if (!worldEditAvailable || template == null) {
                    pasteFallbackPlatform(world, region);
                } else {
                    pasteWorldEdit(template, world, region);
                }
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                pasting = false;
            }
        });
        return future;
    }

    /**
     * WorldEdit .schem 粘贴实现。
     * <p>
     * 读取 plugins/MistHome/templates/&lt;template&gt;.schem → Clipboard →
     * EditSession 粘贴到区域中心（水平居中，地面 y=64）。
     * 粘贴位置换算：期望区域最小点落在 center-dim/2 处，
     * pastePos = origin + desiredMin - regionMin。
     */
    private void pasteWorldEdit(String template, World world, HomeRegion region)
            throws Exception {
        File file = new File(new File(plugin.getDataFolder(),
                plugin.mistConfig().templateDir()), template + ".schem");
        if (!file.isFile()) {
            plugin.getLogger().warning("模板文件不存在: " + file.getPath() + "，降级平台");
            pasteFallbackPlatform(world, region);
            return;
        }

        com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat format =
                com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats.findByFile(file);
        if (format == null) {
            throw new IllegalStateException("无法识别模板格式: " + file.getName());
        }

        try (var reader = format.getReader(new java.io.FileInputStream(file));
             var session = com.sk89q.worldedit.WorldEdit.getInstance().newEditSession(
                     com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(world))) {

            var clipboard = reader.read();
            var min = clipboard.getRegion().getMinimumPoint();
            var dim = clipboard.getDimensions();
            var origin = clipboard.getOrigin();
            int baseY = 64;
            var pastePos = com.sk89q.worldedit.math.BlockVector3.at(
                    region.centerX() - dim.getX() / 2 - min.getX() + origin.getX(),
                    baseY - min.getY() + origin.getY(),
                    region.centerZ() - dim.getZ() / 2 - min.getZ() + origin.getZ());

            var op = new com.sk89q.worldedit.session.ClipboardHolder(clipboard)
                    .createPaste(session)
                    .to(pastePos)
                    .ignoreAirBlocks(true)
                    .build();
            com.sk89q.worldedit.function.operation.Operations.complete(op);
            plugin.getLogger().info("模板已粘贴: " + template + " -> 槽位 ("
                    + region.gridX() + "," + region.gridZ() + ")");
        }
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
