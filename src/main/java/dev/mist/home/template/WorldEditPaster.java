package dev.mist.home.template;

import org.bukkit.World;
import dev.mist.home.world.HomeRegion;

import java.io.File;
import java.io.FileInputStream;

/**
 * WorldEdit 委托类：全部 WE API 引用隔离在本类中。
 * <p>
 * 软依赖类加载规则：JVM 验证方法体时会急切加载参与类型检查
 * （参数赋值/类型转换）的类 —— 例如 createPaste(Extent) 需要
 * 加载 Extent 接口做赋值校验。因此主服务类绝不直接引用 WE 类型，
 * 只在确认 worldEditAvailable 后才调用本类。
 */
final class WorldEditPaster {

    private WorldEditPaster() {
    }

    /**
     * 读取 .schem 并粘贴到区域中心（水平居中，地面 y=64）。
     * pastePos = origin + desiredMin - regionMin。
     */
    static void paste(File file, World world, HomeRegion region) throws Exception {
        com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat format =
                com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats.findByFile(file);
        if (format == null) {
            throw new IllegalStateException("无法识别模板格式: " + file.getName());
        }

        try (var reader = format.getReader(new FileInputStream(file));
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
        }
    }
}
