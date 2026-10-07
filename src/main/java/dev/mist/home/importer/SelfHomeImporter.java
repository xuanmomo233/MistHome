package dev.mist.home.importer;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.model.HomeVisibility;
import dev.mist.home.world.SlotAllocator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * SelfHome 旧插件数据导入器。
 * <p>
 * 旧模型：每个家园 = 服务器根目录下的同名世界文件夹，家数据在
 * SelfHomeMain/playerdata/&lt;家园名&gt;.yml（X/Y/Z 出生点、Members/OP/Denys 成员、
 * Level、Public）。
 * <p>
 * 新模型：家园 = homes/&lt;名_uuid8&gt;/ 下一组归一化 region 文件 + meta.json 基坐标。
 * 导入 = 以旧出生点为中心截取 span×span 的 region 窗口，文件归一化拷贝，
 * meta 记录原基坐标 —— 首次进家时 RegionRelocator 自动平移全部绝对坐标
 * （实体/装配体/POI/方块实体），与正常换槽位完全同链路。
 * <p>
 * 窗口外的 region（跑图噪声）丢弃并在报告中列出；name→UUID 走 Bukkit
 * getOfflinePlayer（离线模式 UUIDv3 确定性，与本服玩家一致）。
 */
public final class SelfHomeImporter {

    private static final String[] SUB_DIRS = {"region", "entities", "poi"};

    private final MistHomePlugin plugin;

    public SelfHomeImporter(MistHomePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 异步批量导入（IO 重，绝不跑主线程）。
     *
     * @param serverRoot 旧服务端根目录（家园世界文件夹所在）
     * @param dataDir    SelfHomeMain 配置目录（含 playerdata/）
     * @param cleanup    true 时把导入成功的旧世界目录移入 serverRoot/_已迁移/
     */
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();

    public CompletableFuture<Report> importAsync(File serverRoot, File dataDir, boolean cleanup) {
        if (!running.compareAndSet(false, true)) {
            Report r = new Report();
            r.lines.add("§c已有导入任务进行中，请等待其完成");
            return CompletableFuture.completedFuture(r);
        }
        return CompletableFuture.supplyAsync(() -> {
                    try {
                        return doImport(serverRoot, dataDir, cleanup);
                    } finally {
                        running.set(false);
                    }
                })
                .exceptionally(t -> {
                    plugin.getLogger().log(Level.WARNING, "SelfHome 导入异常", t);
                    Report r = new Report();
                    r.lines.add("§c导入异常: " + t.getMessage());
                    return r;
                });
    }

    /** 导入报告 */
    public static final class Report {
        public final List<String> lines = new ArrayList<>();
        public int imported;
        public int skipped;
        public int failed;
    }

    /** 旧家园数据（数据源无关：MySQL 行 或 playerdata yml 解析产物） */
    private record OldHome(String name, List<String> members, List<String> ops,
                           List<String> denys, boolean isPublic, int level,
                           double x, double y, double z, String server) {
    }

    public Report doImport(File serverRoot, File dataDir, boolean cleanup) {
        Report report = new Report();
        var cfg = plugin.mistConfig();
        int span = SlotAllocator.regionSpan(cfg.slotSize());
        int maxTier = cfg.maxTier().level();
        File migratedDir = new File(serverRoot, "_已迁移");

        // ---- 汇总数据源：MySQL（BungeeCord 模式为权威源）优先，yml 补充 ----
        List<OldHome> homes = new ArrayList<>();
        java.util.Set<String> covered = new java.util.HashSet<>();
        File selfCfg = new File(dataDir, "config.yml");
        if (selfCfg.isFile()) {
            YamlConfiguration sc = YamlConfiguration.loadConfiguration(selfCfg);
            if ("mysql".equalsIgnoreCase(sc.getString("Type", ""))) {
                List<OldHome> rows = loadMysql(sc, report);
                for (OldHome h : rows) {
                    homes.add(h);
                    covered.add(h.name().toLowerCase());
                }
                report.lines.add("§7数据源: MySQL " + sc.getString("Database")
                        + ".SelfHomeMain_Users（" + rows.size() + " 行）");
            }
        }
        File pdDir = new File(dataDir, "playerdata");
        File[] ymls = pdDir.listFiles((d, n) -> n.endsWith(".yml"));
        int ymlExtra = 0;
        if (ymls != null) {
            for (File yml : ymls) {
                String name = yml.getName().substring(0, yml.getName().length() - 4);
                if (covered.contains(name.toLowerCase())) {
                    continue;   // MySQL 已覆盖，跳过重复 yml
                }
                OldHome h = fromYml(name, yml);
                if (h != null) {
                    homes.add(h);
                    ymlExtra++;
                }
            }
        }
        if (ymls != null && ymls.length > 0) {
            report.lines.add("§7数据源: playerdata yml（补充 " + ymlExtra + " 个）");
        }
        if (homes.isEmpty()) {
            report.lines.add("§c没有任何可导入的家园数据（MySQL 无行 / 无 yml）");
            return report;
        }

        for (OldHome h : homes) {
            try {
                Status st = importOne(h, serverRoot, span, maxTier, report);
                // 当次导入成功 或 早已存在记录（上次已导入）时，才允许移动旧世界目录
                if (cleanup && (st == Status.IMPORTED || st == Status.ALREADY_EXISTS)) {
                    moveToMigrated(h.name(), serverRoot, migratedDir, report);
                }
            } catch (Exception e) {
                report.failed++;
                report.lines.add("§c" + h.name() + " 导入失败: " + e.getMessage());
                plugin.getLogger().log(Level.WARNING, "导入家园失败 " + h.name(), e);
            }
        }
        report.lines.add(0, String.format("§a导入完成：成功 %d / 跳过 %d / 失败 %d（共 %d）%s",
                report.imported, report.skipped, report.failed, homes.size(),
                cleanup ? "，成功项已移入 _已迁移/" : ""));
        return report;
    }

    /** 读旧 SelfHomeMain config.yml 里的 MySQL 连接信息，拉取 SelfHomeMain_Users 全表 */
    private List<OldHome> loadMysql(YamlConfiguration sc, Report report) {
        List<OldHome> out = new ArrayList<>();
        String url = "jdbc:mysql://" + sc.getString("Host", "127.0.0.1")
                + ":" + sc.getInt("Port", 3306)
                + "/" + sc.getString("Database")
                + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
        String user = sc.getString("Username", "root");
        String pass = sc.getString("Password", "");
        try {
            // 驱动被 shade 重定位过，先试新包名再回退原始包名
            try {
                Class.forName("dev.mist.home.libs.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException ignored) {
                Class.forName("com.mysql.cj.jdbc.Driver");
            }
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, user, pass);
                 java.sql.Statement st = c.createStatement();
                 java.sql.ResultSet rs = st.executeQuery("SELECT * FROM SelfHomeMain_Users")) {
                while (rs.next()) {
                    out.add(new OldHome(
                            rs.getString("Name"),
                            splitCsv(rs.getString("Members")),
                            splitCsv(rs.getString("OP")),
                            splitCsv(rs.getString("Denys")),
                            Boolean.parseBoolean(rs.getString("Public")),
                            parseInt(rs.getString("Level"), 1),
                            parseDouble(rs.getString("X")),
                            parseDouble(rs.getString("Y")),
                            parseDouble(rs.getString("Z")),
                            String.valueOf(rs.getString("Server") == null
                                    ? "" : rs.getString("Server"))));
                }
            }
        } catch (Exception e) {
            report.lines.add("§c读取旧 MySQL 失败: " + e.getMessage());
            plugin.getLogger().log(Level.WARNING, "读取 SelfHomeMain MySQL 失败", e);
        }
        return out;
    }

    private OldHome fromYml(String name, File yml) {
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(yml);
            return new OldHome(name,
                    nameList(y, "Members"), nameList(y, "OP"), nameList(y, "Denys"),
                    y.getBoolean("Public"), y.getInt("Level", 1),
                    y.getDouble("X"), y.getDouble("Y"), y.getDouble("Z"),
                    y.getString("Server", ""));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "解析 yml 失败 " + name, e);
            return null;
        }
    }

    private static List<String> splitCsv(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            if (!p.isBlank()) {
                out.add(p.trim());
            }
        }
        return out;
    }

    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String s) {
        try {
            return s == null ? 0 : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 把旧世界目录整体移入 _已迁移/（同卷为瞬时 rename；跨卷报失败不动数据） */
    private void moveToMigrated(String name, File serverRoot, File migratedDir, Report report) {
        File src = new File(serverRoot, name);
        if (!src.isDirectory()) {
            return;
        }
        try {
            Files.createDirectories(migratedDir.toPath());
            Path dst = migratedDir.toPath().resolve(name);
            int n = 1;
            while (Files.exists(dst)) {
                dst = migratedDir.toPath().resolve(name + "." + n++);
            }
            Files.move(src.toPath(), dst);
            report.lines.add("  §7└ 旧世界目录已移至 _已迁移/" + dst.getFileName());
        } catch (IOException e) {
            report.lines.add("  §e└ 旧世界目录移动失败（数据未动）: " + e.getMessage());
            plugin.getLogger().log(Level.WARNING, "移动旧世界目录失败 " + name, e);
        }
    }

    private enum Status { IMPORTED, ALREADY_EXISTS, SKIPPED }

    /** @return 导入结果状态 */
    private Status importOne(OldHome oh, File serverRoot,
                             int span, int maxTier, Report report) throws Exception {
        String name = oh.name();
        // 1. 旧世界目录必须存在（服务器根下同名文件夹）
        File worldDir = new File(serverRoot, name);
        if (!new File(worldDir, "region").isDirectory()) {
            report.skipped++;
            report.lines.add("§7" + name + "：无世界目录，跳过");
            return Status.SKIPPED;
        }

        // 2. 旧数据
        double sx = oh.x();
        double sy = oh.y();
        double sz = oh.z();
        int level = oh.level();
        boolean isPublic = oh.isPublic();
        String server = oh.server();

        // 3. 玩家 UUID（离线模式确定性）
        OfflinePlayer owner = Bukkit.getOfflinePlayer(name);
        UUID ownerUuid = owner.getUniqueId();
        if (plugin.storage().findHomeByOwner(ownerUuid).isPresent()) {
            report.skipped++;
            report.lines.add("§7" + name + "：已有家园记录，跳过");
            return Status.ALREADY_EXISTS;
        }

        // 4. 选窗口：出生点已设 → 含出生点且覆盖最多；未设(0,0) → 全局最密窗口
        boolean spawnUnset = (sx == 0 && sz == 0);
        int[] window = pickWindow(worldDir, spRegionX(sx), spRegionZ(sz), span, spawnUnset);
        int baseX = window[0];
        int baseZ = window[1];
        List<String> dropped = droppedFiles(worldDir, baseX, baseZ, span);

        // 5. 先用临时 Home 算出存档目录并把文件拷好——拷贝失败不留半成品记录
        Home probe = new Home(0, ownerUuid, name, 0, 0, "selfhome-import",
                HomeVisibility.PRIVATE, 0, 0, 0, 0f, 0f, 0,
                plugin.mistConfig().serverName());
        Path homeDir = plugin.worldManager().archive().homeDir(probe);
        List<String> copied;
        try {
            copied = copyWindow(worldDir, homeDir, baseX, baseZ, span);
        } catch (IOException e) {
            deleteQuietly(homeDir);
            throw e;
        }
        if (copied.isEmpty()) {
            deleteQuietly(homeDir);
            report.skipped++;
            report.lines.add("§7" + name + "：窗口内无 region 文件，跳过");
            return Status.SKIPPED;
        }

        // 6. 建档
        int tier = Math.min(Math.max(level - 1, 0), maxTier);
        // 出生点偏移 = 旧绝对坐标 - 窗口中心（窗口中心即新槽位中心语义）
        double winCenterX = baseX * 512.0 + span * 256.0;
        double winCenterZ = baseZ * 512.0 + span * 256.0;
        double offX = spawnUnset ? 0.5 : sx - winCenterX;
        double offZ = spawnUnset ? 0.5 : sz - winCenterZ;
        double spawnY = (sy == 0) ? 65.0 : sy;

        // 6. 建档 + 角色迁移 + meta —— 任一步失败整体回滚（删记录/缓存/已拷目录），
        //    避免留下"有记录但缺 meta"的残破家园（缺 meta 恢复时不平移，坐标全错）
        int roles = 0;
        Home home = null;
        try {
            int slot = plugin.storage().allocateSlot();
            home = plugin.storage().createHome(ownerUuid, name + "的家园", slot, tier,
                    "selfhome-import", offX, spawnY, offZ,
                    plugin.mistConfig().serverName());
            if (isPublic) {
                home.setVisibility(HomeVisibility.PUBLIC);
                plugin.storage().updateHome(home);
            }
            plugin.homeService().cache(home);

            // 角色迁移：Members→MEMBER，OP→OPERATOR，Denys→BANNED
            for (String m : oh.members()) {
                if (m.equalsIgnoreCase(name)) continue;
                plugin.storage().setRole(home.id(), uuidOf(m), HomeRole.MEMBER);
                roles++;
            }
            for (String m : oh.ops()) {
                if (m.equalsIgnoreCase(name)) continue;
                plugin.storage().setRole(home.id(), uuidOf(m), HomeRole.OPERATOR);
                roles++;
            }
            for (String m : oh.denys()) {
                plugin.storage().ban(home.id(), uuidOf(m));
                roles++;
            }

            // 写 meta（记录旧基坐标，首次进家 RegionRelocator 平移）
            plugin.worldManager().archive().writeArchiveMeta(homeDir, home.id(),
                    ownerUuid, copied, baseX, baseZ);
        } catch (Exception e) {
            if (home != null) {
                try {
                    plugin.storage().deleteHome(home.id());
                } catch (Exception ignored) {
                }
                plugin.homeService().evict(home);
            }
            deleteQuietly(homeDir);
            throw e;
        }

        report.imported++;
        String dropInfo = dropped.isEmpty() ? ""
                : "，丢弃窗外 " + dropped.size() + " 个: "
                + String.join(",", dropped.subList(0, Math.min(8, dropped.size())))
                + (dropped.size() > 8 ? "..." : "");
        report.lines.add("§a" + name + "：导入 " + copied.size() + " 个 region 文件"
                + "（窗口基 r." + baseX + "." + baseZ + dropInfo
                + (server.isEmpty() ? "" : "，旧服=" + server)
                + (roles > 0 ? "，角色 " + roles : "") + "）");
        return Status.IMPORTED;
    }

    private static int spRegionX(double sx) {
        return Math.floorDiv((int) Math.floor(sx), 512);
    }

    private static int spRegionZ(double sz) {
        return Math.floorDiv((int) Math.floor(sz), 512);
    }

    /**
     * 选覆盖文件最多的 span×span 窗口。
     * 出生点已设：只在包含出生点 region 的候选中选；未设：以每个现存文件为锚穷举。
     *
     * @return {baseX, baseZ}
     */
    private int[] pickWindow(File worldDir, int spRegionX, int spRegionZ,
                             int span, boolean spawnUnset) {
        List<int[]> candidates = new ArrayList<>();
        if (spawnUnset) {
            // 出生点未设：对每个现存文件，尝试以它为角落/内部的所有可能窗口基
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int[] rc : presentRegionCoords(worldDir)) {
                for (int dx = 0; dx < span; dx++) {
                    for (int dz = 0; dz < span; dz++) {
                        int bx = rc[0] - dx, bz = rc[1] - dz;
                        if (seen.add(bx + "," + bz)) {
                            candidates.add(new int[]{bx, bz});
                        }
                    }
                }
            }
        } else {
            for (int bx = spRegionX - span + 1; bx <= spRegionX; bx++) {
                for (int bz = spRegionZ - span + 1; bz <= spRegionZ; bz++) {
                    candidates.add(new int[]{bx, bz});
                }
            }
        }
        int bestX = 0, bestZ = 0, bestScore = -1;
        for (int[] c : candidates) {
            int score = countInWindow(worldDir, c[0], c[1], span);
            if (score > bestScore) {
                bestScore = score;
                bestX = c[0];
                bestZ = c[1];
            }
        }
        return new int[]{bestX, bestZ};
    }

    /** 列出世界目录下所有 region/entities/poi 的 region 坐标（去重） */
    private List<int[]> presentRegionCoords(File worldDir) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<int[]> out = new ArrayList<>();
        for (String sub : SUB_DIRS) {
            File[] files = new File(worldDir, sub)
                    .listFiles((d, n) -> n.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));
            if (files == null) continue;
            for (File f : files) {
                String[] p = f.getName().split("\\.");
                if (seen.add(p[1] + "," + p[2])) {
                    out.add(new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2])});
                }
            }
        }
        return out;
    }

    /** 窗口外将被丢弃的文件名清单（sub/r.x.z.mca） */
    private List<String> droppedFiles(File worldDir, int baseX, int baseZ, int span) {
        List<String> out = new ArrayList<>();
        for (String sub : SUB_DIRS) {
            File[] files = new File(worldDir, sub)
                    .listFiles((d, n) -> n.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));
            if (files == null) continue;
            for (File f : files) {
                String[] p = f.getName().split("\\.");
                int rx = Integer.parseInt(p[1]);
                int rz = Integer.parseInt(p[2]);
                if (rx < baseX || rx >= baseX + span || rz < baseZ || rz >= baseZ + span) {
                    out.add(sub + "/" + f.getName());
                }
            }
        }
        return out;
    }

    private int countInWindow(File worldDir, int baseX, int baseZ, int span) {
        int n = 0;
        for (String sub : SUB_DIRS) {
            for (int i = 0; i < span; i++) {
                for (int j = 0; j < span; j++) {
                    if (new File(worldDir, sub + File.separator
                            + "r." + (baseX + i) + "." + (baseZ + j) + ".mca").isFile()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** 窗口内文件归一化拷贝到存档目录，返回相对路径清单 */
    private List<String> copyWindow(File worldDir, Path homeDir,
                                    int baseX, int baseZ, int span) throws IOException {
        List<String> copied = new ArrayList<>();
        for (String sub : SUB_DIRS) {
            for (int i = 0; i < span; i++) {
                for (int j = 0; j < span; j++) {
                    File src = new File(worldDir, sub + File.separator
                            + "r." + (baseX + i) + "." + (baseZ + j) + ".mca");
                    if (!src.isFile()) {
                        continue;
                    }
                    Path dst = homeDir.resolve(sub).resolve("r." + i + "." + j + ".mca");
                    Files.createDirectories(dst.getParent());
                    Files.copy(src.toPath(), dst, StandardCopyOption.REPLACE_EXISTING);
                    copied.add(sub + "/r." + i + "." + j + ".mca");
                }
            }
        }
        return copied;
    }

    /** yml 中成员字段兼容两种形态：列表 [...] 或映射 {名: true} */
    private List<String> nameList(YamlConfiguration y, String key) {
        List<String> list = y.getStringList(key);
        if (!list.isEmpty()) {
            return list;
        }
        var sec = y.getConfigurationSection(key);
        return sec != null ? new ArrayList<>(sec.getKeys(false)) : List.of();
    }

    private UUID uuidOf(String playerName) {
        return Bukkit.getOfflinePlayer(playerName).getUniqueId();
    }

    private void deleteQuietly(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
