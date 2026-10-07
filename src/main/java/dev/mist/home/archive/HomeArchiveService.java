package dev.mist.home.archive;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.Home;
import dev.mist.home.world.SlotAllocator;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 家园存档服务：以 region 文件为粒度，在「世界目录」和「插件存档目录」之间搬运家园数据。
 * <p>
 * 目录布局：
 * <pre>
 *   plugins/MistHome/homes/&lt;玩家名_uuid8&gt;/
 *       region/r.0.0.mca  r.1.0.mca  ...   （归一化到槽位本地坐标）
 *       entities/r.0.0.mca ...
 *       poi/r.0.0.mca ...
 *       meta.json                          （文件清单+校验+时间戳）
 * </pre>
 * 存档中文件名使用槽位本地坐标（0 起），恢复时按目标槽位的 region 基坐标重命名，
 * 家园与物理位置完全解耦 —— 这是"自由拼接"的实现基础。
 * <p>
 * 铁律：只在世界未加载时碰其文件（世界加载期间 region 文件句柄归 Minecraft 所有）。
 */
public class HomeArchiveService {

    /** region 文件所在目录名（mca 三件套） */
    private static final String[] SUB_DIRS = {"region", "entities", "poi"};

    private final MistHomePlugin plugin;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public HomeArchiveService(MistHomePlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- 路径 ----------

    /** 家园存档根目录 plugins/MistHome/homes/<名字_uuid8>/ */
    public Path homeDir(Home home) {
        Path base = plugin.getDataFolder().toPath()
                .resolve(plugin.mistConfig().archiveDir());
        OfflinePlayer owner = Bukkit.getOfflinePlayer(home.owner());
        String name = owner.getName();
        if (name == null || name.isBlank()) {
            name = "unknown";
        }
        name = name.replaceAll("[^a-zA-Z0-9_\\-一-龥]", "_");
        String shortUuid = home.owner().toString().substring(0, 8);
        Path preferred = base.resolve(name + "_" + shortUuid);
        if (Files.isDirectory(preferred)) {
            return preferred;
        }
        // 玩家改名兜底：按 _uuid8 后缀找回旧目录，避免存档失联
        if (Files.isDirectory(base)) {
            try (var stream = Files.list(base)) {
                var found = stream.filter(p -> p.getFileName().toString()
                                .endsWith("_" + shortUuid)
                                && Files.isDirectory(p))
                        .findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (IOException ignored) {
            }
        }
        return preferred;
    }

    public boolean hasArchive(Home home) {
        return Files.isDirectory(homeDir(home));
    }

    /** 世界目录（服务端根目录下的世界文件夹） */
    public File worldDir(String worldName) {
        return new File(Bukkit.getWorldContainer(), worldName);
    }

    // ---------- 归档（世界 -> 插件） ----------

    /**
     * 把槽位的 mca 文件组拷贝到家园存档目录（归一化为本地坐标）。
     * 必须在世界已卸载/未加载状态下调用。
     *
     * @return 拷贝成功的文件数（0 不代表失败：虚空家园可能没有任何文件）
     */
    public int archive(Home home, File worldDir, int slotIndex) {
        var cfg = plugin.mistConfig();
        int baseX = SlotAllocator.regionBaseX(slotIndex, cfg.homesPerWorld(), cfg.slotSize());
        int baseZ = SlotAllocator.regionBaseZ(slotIndex, cfg.homesPerWorld(), cfg.slotSize());
        int span = SlotAllocator.regionSpan(cfg.slotSize());

        Path homeDir = homeDir(home);
        List<String> copied = new ArrayList<>();
        try {
            for (String sub : SUB_DIRS) {
                for (int i = 0; i < span; i++) {
                    for (int j = 0; j < span; j++) {
                        File src = new File(worldDir, sub + File.separator
                                + "r." + (baseX + i) + "." + (baseZ + j) + ".mca");
                        if (!src.isFile()) {
                            continue;   // 该 region 从未写入过（虚空）
                        }
                        Path dst = homeDir.resolve(sub)
                                .resolve("r." + i + "." + j + ".mca");
                        Files.createDirectories(dst.getParent());
                        Files.copy(src.toPath(), dst, StandardCopyOption.REPLACE_EXISTING);
                        copied.add(sub + "/r." + i + "." + j + ".mca");
                    }
                }
            }
            writeMeta(homeDir.resolve("meta.json"),
                    new Meta(home.id(), home.owner().toString(), copied,
                            System.currentTimeMillis(), baseX, baseZ));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "归档家园失败 home=" + home.id() + " world=" + worldDir.getName(), e);
            return -1;
        }
        return copied.size();
    }

    // ---------- 恢复（插件 -> 世界） ----------

    /**
     * 把家园存档写入世界目录的目标槽位（本地坐标 → 目标 region 基坐标）。
     * 文件已存在则跳过不覆盖（运行世界文件可能更新）。无存档返回 false。
     */
    public boolean restore(Home home, File worldDir, int slotIndex) {
        var cfg = plugin.mistConfig();
        Path homeDir = homeDir(home);
        if (!Files.isDirectory(homeDir)) {
            return false;
        }
        int baseX = SlotAllocator.regionBaseX(slotIndex, cfg.homesPerWorld(), cfg.slotSize());
        int baseZ = SlotAllocator.regionBaseZ(slotIndex, cfg.homesPerWorld(), cfg.slotSize());
        int span = SlotAllocator.regionSpan(cfg.slotSize());

        // 归档时记录的物理基坐标 -> 换槽位时需要平移文件内绝对坐标
        Meta meta = readMeta(homeDir);
        Integer savedX = meta != null ? meta.regionBaseX() : null;
        Integer savedZ = meta != null ? meta.regionBaseZ() : null;
        int dRegX = savedX != null ? baseX - savedX : 0;
        int dRegZ = savedZ != null ? baseZ - savedZ : 0;
        if (dRegX != 0 || dRegZ != 0) {
            plugin.getLogger().info("家园换槽位：实体/POI 坐标随文件平移 home=" + home.id()
                    + " dRegion=(" + dRegX + "," + dRegZ + ")"
                    + " dBlock=(" + dRegX * 512 + "," + dRegZ * 512 + ")");
        }
        if ((meta == null || savedX == null || savedZ == null)
                && Files.exists(homeDir.resolve("region"))) {
            plugin.getLogger().info("家园存档缺少 region 基坐标记录，跳过实体坐标平移 home=" + home.id());
        }

        try {
            for (String sub : SUB_DIRS) {
                Path subDir = homeDir.resolve(sub);
                if (!Files.isDirectory(subDir)) {
                    continue;
                }
                for (int i = 0; i < span; i++) {
                    for (int j = 0; j < span; j++) {
                        Path src = subDir.resolve("r." + i + "." + j + ".mca");
                        if (!Files.isRegularFile(src)) {
                            continue;
                        }
                        File dst = new File(worldDir, sub + File.separator
                                + "r." + (baseX + i) + "." + (baseZ + j) + ".mca");
                        if (dst.exists()) {
                            continue;   // 不缺不补，世界文件优先
                        }
                        Files.createDirectories(dst.getParentFile().toPath());
                        Files.copy(src, dst.toPath());
                        if (dRegX != 0 || dRegZ != 0) {
                            // 文件内实体 Pos / POI / 方块实体坐标同步平移到新槽位
                            RegionRelocator.relocateFile(dst, sub, dRegX, dRegZ, plugin.getLogger());
                        }
                    }
                }
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "恢复家园存档失败 home=" + home.id() + " world=" + worldDir.getName(), e);
            return false;
        }
    }

    /** 删除家园存档（admin 删除/重置家园时用） */
    public void deleteArchive(Home home) {
        Path dir = homeDir(home);
        if (Files.isDirectory(dir)) {
            deleteRecursively(dir.toFile());
        }
    }

    // ---------- 元数据 ----------

    /**
     * @param regionBaseX 归档时家园所在槽位的 region 基坐标 X（Integer 可空：旧存档无此字段时
     *                    gson 反序列化为 null，恢复时按"无平移"处理并打日志）
     * @param regionBaseZ 同上 Z
     */
    private record Meta(long homeId, String owner, List<String> files, long archivedAt,
                        Integer regionBaseX, Integer regionBaseZ) {
    }

    private void writeMeta(Path metaFile, Meta meta) throws IOException {
        Files.createDirectories(metaFile.getParent());
        try (Writer w = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8)) {
            gson.toJson(meta, w);
        }
    }

    /** 供旧插件导入器写入归档元数据（regionBase = 旧世界被截取窗口的基坐标） */
    public void writeArchiveMeta(Path homeDir, long homeId, java.util.UUID owner,
                                 List<String> files, int regionBaseX, int regionBaseZ)
            throws IOException {
        writeMeta(homeDir.resolve("meta.json"),
                new Meta(homeId, owner.toString(), files,
                        System.currentTimeMillis(), regionBaseX, regionBaseZ));
    }

    public Meta readMeta(Path homeDir) {
        Path metaFile = homeDir.resolve("meta.json");
        if (!Files.isRegularFile(metaFile)) {
            return null;
        }
        try (Reader r = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
            return gson.fromJson(r, Meta.class);
        } catch (IOException e) {
            return null;
        }
    }

    // ---------- 崩溃核对 ----------

    /**
     * 启动时核对世界目录残留文件。
     * 崩溃/强制停止后世界文件夹还在：台账里的停放家园文件视为最新数据
     * 重新归档；无台账的孤儿文件移入 recovery 目录隔离。
     *
     * @param worldDir 世界目录
     * @param worldIndex 世界在池中的索引
     * @param parkedHomeIds 台账中停在该世界的 homeId 列表（含内槽位映射）
     * @param homeLookup 由 homeId 取 Home
     */
    public void reconcile(File worldDir, int worldIndex,
                          Map<Integer, Long> slotHomeMap,
                          java.util.function.Function<Long, Home> homeLookup) {
        if (!worldDir.isDirectory()) {
            return;
        }
        var cfg = plugin.mistConfig();
        int hpw = cfg.homesPerWorld();
        int slotSize = cfg.slotSize();
        boolean foundAny = false;

        for (String sub : SUB_DIRS) {
            File subDir = new File(worldDir, sub);
            File[] files = subDir.listFiles((d, n) -> n.endsWith(".mca"));
            if (files == null) {
                continue;
            }
            for (File f : files) {
                foundAny = true;
                // 文件名 r.x.z.mca -> region 坐标 -> 内槽位
                String[] parts = f.getName().split("\\.");
                if (parts.length != 4) {
                    quarantine(f, worldDir);
                    continue;
                }
                int rx, rz;
                try {
                    rx = Integer.parseInt(parts[1]);
                    rz = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    quarantine(f, worldDir);
                    continue;
                }
                Integer inner = innerSlotOfRegion(rx, rz, hpw, slotSize);
                if (inner == null) {
                    continue;   // 网格外文件（世界出生点等基础设施），不是家园数据
                }
                Long homeId = slotHomeMap.get(inner);
                Home home = homeId == null ? null : homeLookup.apply(homeId);
                if (home != null) {
                    // 台账知道这家园停在这 -> 最新数据归档回插件目录
                    archiveSingle(f, home, rx, rz, inner, slotSize);
                } else {
                    quarantine(f, worldDir);
                }
            }
        }
        if (foundAny) {
            plugin.getLogger().info("世界目录残留已核对: " + worldDir.getName());
        }
    }

    /** region 坐标 -> 世界内槽位号（网格外 region 如出生点文件返回 null） */
    private Integer innerSlotOfRegion(int rx, int rz, int hpw, int slotSize) {
        int cols = SlotAllocator.cols(hpw);
        int origin = SlotAllocator.gridOrigin(hpw, slotSize);
        // region -> 方块坐标 -> 槽位网格；floorDiv 避免负坐标向零截断
        int blockX = rx * SlotAllocator.REGION_SIZE;
        int blockZ = rz * SlotAllocator.REGION_SIZE;
        int gx = Math.floorDiv(blockX - origin, slotSize);
        int gz = Math.floorDiv(blockZ - origin, slotSize);
        if (gx < 0 || gx >= cols || gz < 0 || gz >= cols) {
            return null;
        }
        return gz * cols + gx;
    }

    /** 单个 region 文件直接归档到家园目录（崩溃恢复路径） */
    private void archiveSingle(File f, Home home, int rx, int rz,
                               int innerSlot, int slotSize) {
        int hpw = plugin.mistConfig().homesPerWorld();
        // 槽位网格布局在世界内一致，直接用内槽位号换算基坐标
        int baseX = SlotAllocator.regionBaseX(innerSlot, hpw, slotSize);
        int baseZ = SlotAllocator.regionBaseZ(innerSlot, hpw, slotSize);
        String sub = f.getParentFile().getName();
        Path dst = homeDir(home).resolve(sub)
                .resolve("r." + (rx - baseX) + "." + (rz - baseZ) + ".mca");
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(f.toPath(), dst, StandardCopyOption.REPLACE_EXISTING);
            // 崩溃归档也登记 region 基坐标，否则恢复时拿不到平移基准
            mergeMeta(home, sub + "/r." + (rx - baseX) + "." + (rz - baseZ) + ".mca", baseX, baseZ);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "崩溃归档单文件失败: " + f.getPath(), e);
        }
    }

    /** 合并写 meta：保留已有文件清单，更新 region 基坐标为本次物理槽位 */
    private void mergeMeta(Home home, String file, int baseX, int baseZ) {
        Path homeDir = homeDir(home);
        Meta old = readMeta(homeDir);
        List<String> files = new ArrayList<>(
                old != null && old.files() != null ? old.files() : List.of());
        if (!files.contains(file)) {
            files.add(file);
        }
        try {
            writeMeta(homeDir.resolve("meta.json"),
                    new Meta(home.id(), home.owner().toString(), files,
                            System.currentTimeMillis(), baseX, baseZ));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "写 meta.json 失败: " + homeDir, e);
        }
    }

    private void quarantine(File f, File worldDir) {
        Path dst = plugin.getDataFolder().toPath()
                .resolve("recovery").resolve(worldDir.getName())
                .resolve(f.getParentFile().getName()).resolve(f.getName());
        try {
            Files.createDirectories(dst.getParent());
            Files.move(f.toPath(), dst, StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("孤儿 region 文件已隔离: " + f.getPath());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "隔离失败: " + f.getPath(), e);
        }
    }

    /** 递归删除目录（删除世界文件夹/存档用） */
    public boolean deleteRecursively(File dir) {
        if (!dir.exists()) {
            return true;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        return dir.delete();
    }

}
