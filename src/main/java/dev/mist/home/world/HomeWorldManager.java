package dev.mist.home.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.archive.HomeArchiveService;
import dev.mist.home.config.MistConfig;
import dev.mist.home.model.Home;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 家园世界池管理器（一次性画布模型）。
 * <p>
 * 池世界是数量有限的家园运行画布：家园进入运行状态时，
 * 其存档文件被写入池中某个<b>从未使用过</b>的槽位；
 * 世界无人超时后整体卸载、各家园归档回插件目录、
 * 世界文件夹删除 —— 下次需要时重建同名世界，槽位全部重置。
 * <p>
 * 不变式：
 * <ul>
 *   <li>槽位在世界一次生命周期内只使用一次（写一次文件，不覆写）</li>
 *   <li>世界加载期间插件不碰该世界的任何 region 文件</li>
 *   <li>世界目录存在 = 世界正在使用或崩溃残留（启动时核对）</li>
 * </ul>
 */
public class HomeWorldManager implements Listener {

    /** 家园停放记录：停在哪个世界的哪个内槽位 */
    public record Parking(long homeId, int worldIndex, int innerSlot) {
        public int globalSlot(int homesPerWorld) {
            return worldIndex * homesPerWorld + innerSlot;
        }
    }

    private final MistHomePlugin plugin;
    private final HomeArchiveService archive;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** 池世界名有序列表（索引即 worldIndex），持久化于 pool-state.json */
    private final List<String> poolWorlds = new ArrayList<>();
    /** worldIndex -> 已加载的世界 */
    private final Map<Integer, World> loaded = new ConcurrentHashMap<>();
    /** worldIndex -> 正在进行的加载任务 */
    private final Map<Integer, CompletableFuture<World>> loading = new ConcurrentHashMap<>();
    /** worldIndex -> 正在进行的卸载后清理（归档+删目录），期间不可再加载 */
    private final Map<Integer, CompletableFuture<Void>> cleaning = new ConcurrentHashMap<>();
    /** worldIndex -> 世界最后变空的时间戳 */
    private final Map<Integer, Long> emptySince = new ConcurrentHashMap<>();
    /** homeId -> 停放记录 */
    private final Map<Long, Parking> parked = new ConcurrentHashMap<>();
    /** globalSlot -> homeId（家园坐标反查） */
    private final Map<Integer, Long> slotOwner = new ConcurrentHashMap<>();
    /** worldIndex -> 已使用（烧过）的内槽位集合（含当前停放中的） */
    private final Map<Integer, Set<Integer>> used = new ConcurrentHashMap<>();
    /** worldIndex -> 被污染的空闲内槽位（玩家接近导致虚空区块进过内存） */
    private final Map<Integer, Set<Integer>> tainted = new ConcurrentHashMap<>();

    private BukkitTask sweepTask;

    public HomeWorldManager(MistHomePlugin plugin) {
        this.plugin = plugin;
        this.archive = new HomeArchiveService(plugin);
    }

    private MistConfig cfg() {
        return plugin.mistConfig();
    }

    public HomeArchiveService archive() {
        return archive;
    }

    // ---------- 启动 / 状态持久化 ----------

    public void start() {
        loadState();
        reconcileLeftovers();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long period = cfg().sweepIntervalSeconds() * 20L;
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, period, period);
    }

    private Path stateFile() {
        return plugin.getDataFolder().toPath().resolve(cfg().poolStateFile());
    }

    private static class PoolState {
        List<String> worlds = new ArrayList<>();
        Map<String, int[]> parking = new HashMap<>();   // homeId -> [worldIndex, innerSlot]
    }

    private void loadState() {
        poolWorlds.clear();
        poolWorlds.addAll(cfg().poolWorldNames());
        Path f = stateFile();
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                PoolState st = gson.fromJson(r, PoolState.class);
                if (st != null) {
                    for (String w : st.worlds) {
                        if (!poolWorlds.contains(w)) {
                            poolWorlds.add(w);
                        }
                    }
                    for (Map.Entry<String, int[]> e : st.parking.entrySet()) {
                        try {
                            long homeId = Long.parseLong(e.getKey());
                            int wi = e.getValue()[0];
                            int inner = e.getValue()[1];
                            parked.put(homeId, new Parking(homeId, wi, inner));
                            used.computeIfAbsent(wi, k -> ConcurrentHashMap.newKeySet()).add(inner);
                        } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "读取池状态失败", e);
            }
        }
        if (poolWorlds.isEmpty()) {
            poolWorlds.add(cfg().worldPrefix() + "0");
        }
        // 重建 slotOwner（需要 homeService 已初始化则在 park 时建立，启动时按 parked 恢复）
        for (Parking p : parked.values()) {
            slotOwner.put(p.globalSlot(cfg().homesPerWorld()), p.homeId());
        }
    }

    private void saveState() {
        PoolState st = new PoolState();
        st.worlds = new ArrayList<>(poolWorlds);
        for (Parking p : parked.values()) {
            st.parking.put(String.valueOf(p.homeId()),
                    new int[]{p.worldIndex(), p.innerSlot()});
        }
        Path f = stateFile();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(st, w);
            }
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "写入池状态失败", e);
        }
    }

    /**
     * 启动核对：扫描池世界目录残留文件（崩溃/强制停止时未走正常卸载）。
     * 台账对应的家园重新归档，孤儿文件隔离。
     */
    private void reconcileLeftovers() {
        for (int i = 0; i < poolWorlds.size(); i++) {
            File dir = archive.worldDir(poolWorlds.get(i));
            if (!dir.isDirectory()) {
                continue;
            }
            Map<Integer, Long> slotHome = new HashMap<>();
            int wi = i;
            for (Parking p : parked.values()) {
                if (p.worldIndex() == wi) {
                    slotHome.put(p.innerSlot(), p.homeId());
                }
            }
            archive.reconcile(dir, i, slotHome,
                    id -> plugin.homeService() == null ? null : plugin.homeService().byId(id).orElse(null));
        }
    }

    // ---------- 世界名 / 索引 ----------

    public String worldName(int worldIndex) {
        return poolWorlds.get(worldIndex);
    }

    public List<String> poolWorlds() {
        return List.copyOf(poolWorlds);
    }

    public boolean isHomeWorld(String worldName) {
        return worldName != null && poolWorlds.contains(worldName);
    }

    public int parseWorldIndex(String worldName) {
        return poolWorlds.indexOf(worldName);
    }

    public boolean isLoaded(int worldIndex) {
        return loaded.containsKey(worldIndex);
    }

    // ---------- 停放（核心入口） ----------

    /**
     * 确保家园已停放（其区块文件已写入某池世界的空闲槽位）。
     * 已停放 → 确保世界加载；未停放 → 分配空闲槽位、写入存档、加载世界。
     * 全部在主线程/主线程回调中完成。
     *
     * @return future 完成时为停放记录；失败为异常（如池满且禁止自动扩池）
     */
    public CompletableFuture<Parking> ensureParked(Home home) {
        Parking existing = parked.get(home.id());
        if (existing != null) {
            return ensureLoaded(existing.worldIndex())
                    .thenApply(w -> {
                        archive.restore(home, archive.worldDir(worldName(existing.worldIndex())),
                                existing.globalSlot(cfg().homesPerWorld()));
                        return existing;
                    });
        }

        // 分配槽位：优先已加载世界（避免不必要的加载），再未加载世界，最后自动扩池
        int wi = -1;
        int inner = -1;
        for (int i = 0; i < poolWorlds.size(); i++) {
            if (cleaning.containsKey(i)) {
                continue;
            }
            if (!loaded.containsKey(i) && archive.worldDir(poolWorlds.get(i)).isDirectory()) {
                continue;   // 目录存在却未加载（清理中/异常），跳过
            }
            int slot = findFreeSlot(i);
            if (slot >= 0 && loaded.containsKey(i)) {
                wi = i;
                inner = slot;
                break;
            }
            if (slot >= 0 && wi < 0) {
                wi = i;
                inner = slot;   // 未加载世界作备选，继续找已加载的
            }
        }
        if (wi < 0) {
            int max = cfg().maxWorlds();
            if (!cfg().autoGrow() || (max > 0 && poolWorlds.size() >= max)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("家园世界池已满"
                                + (cfg().autoGrow() ? "（已达上限 " + max + "）"
                                        : "，且未开启自动扩池")));
            }
            poolWorlds.add(cfg().worldPrefix() + poolWorlds.size());
            wi = poolWorlds.size() - 1;
            inner = findFreeSlot(wi);
            plugin.getLogger().info("池已满，自动创建家园世界: " + worldName(wi));
        }
        if (inner < 0) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("无法分配空闲槽位"));
        }

        Parking np = new Parking(home.id(), wi, inner);
        parked.put(home.id(), np);
        slotOwner.put(np.globalSlot(cfg().homesPerWorld()), home.id());
        used.computeIfAbsent(wi, k -> ConcurrentHashMap.newKeySet()).add(inner);
        saveState();

        int fwi = wi;
        return ensureLoaded(wi).thenApply(w -> {
            archive.restore(home, archive.worldDir(worldName(fwi)),
                    np.globalSlot(cfg().homesPerWorld()));
            return np;
        });
    }

    /** 在世界 worldIndex 中找一个空闲内槽位；找不到返回 -1 */
    private int findFreeSlot(int worldIndex) {
        int hpw = cfg().homesPerWorld();
        Set<Integer> burned = used.getOrDefault(worldIndex, Set.of());
        Set<Integer> dirty = tainted.getOrDefault(worldIndex, Set.of());
        File dir = archive.worldDir(poolWorlds.get(worldIndex));
        for (int inner = 0; inner < hpw; inner++) {
            if (burned.contains(inner) || dirty.contains(inner)) {
                continue;
            }
            if (loaded.containsKey(worldIndex) && slotFilesExist(dir, inner)) {
                continue;   // 磁盘铁证：该槽位已被写过
            }
            return inner;
        }
        return -1;
    }

    /** 槽位的任一 mca 文件是否存在（磁盘铁证） */
    private boolean slotFilesExist(File dir, int inner) {
        int hpw = cfg().homesPerWorld();
        int baseX = SlotAllocator.regionBaseX(inner, hpw, cfg().slotSize());
        int baseZ = SlotAllocator.regionBaseZ(inner, hpw, cfg().slotSize());
        int span = SlotAllocator.regionSpan(cfg().slotSize());
        for (String sub : new String[]{"region", "entities", "poi"}) {
            for (int i = 0; i < span; i++) {
                for (int j = 0; j < span; j++) {
                    if (new File(dir, sub + File.separator
                            + "r." + (baseX + i) + "." + (baseZ + j) + ".mca").isFile()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 家园当前停放位置（未停放返回 empty） */
    public Optional<Parking> parkingOf(long homeId) {
        return Optional.ofNullable(parked.get(homeId));
    }

    /** 停放记录对应的世界对象（未加载返回 null） */
    public World worldOf(Parking p) {
        return loaded.get(p.worldIndex());
    }

    /** 家园当前可用区域（未停放返回 empty） */
    public Optional<HomeRegion> regionOf(Home home, int usableRadius) {
        Parking p = parked.get(home.id());
        if (p == null) {
            return Optional.empty();
        }
        return Optional.of(SlotAllocator.regionOf(
                p.globalSlot(cfg().homesPerWorld()), cfg().homesPerWorld(),
                cfg().slotSize(), usableRadius));
    }

    /** 坐标反查：世界内坐标 -> 停放该槽位的 homeId（无停放返回 null） */
    public Long homeIdAt(int worldIndex, double x, double z) {
        var slot = SlotAllocator.slotIndexAt(worldIndex, cfg().homesPerWorld(),
                cfg().slotSize(), x, z);
        return slot.isEmpty() ? null : slotOwner.get(slot.getAsInt());
    }

    // ---------- 世界加载 ----------

    /**
     * 确保池世界已加载（主线程安全）。
     * 正在清理中 → 等清理完成后再加载（世界重建）。
     */
    public CompletableFuture<World> ensureLoaded(int worldIndex) {
        World existing = loaded.get(worldIndex);
        if (existing != null) {
            return CompletableFuture.completedFuture(existing);
        }
        CompletableFuture<World> inFlight = loading.get(worldIndex);
        if (inFlight != null) {
            return inFlight;
        }
        CompletableFuture<Void> clean = cleaning.get(worldIndex);
        if (clean != null) {
            CompletableFuture<World> after = new CompletableFuture<>();
            clean.whenComplete((v, err) -> ensureLoaded(worldIndex)
                    .whenComplete((w, e2) -> {
                        if (e2 != null) after.completeExceptionally(e2);
                        else after.complete(w);
                    }));
            return after;
        }

        CompletableFuture<World> future = new CompletableFuture<>();
        loading.put(worldIndex, future);

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                String name = worldName(worldIndex);
                World already = Bukkit.getWorld(name);
                World world = already != null ? already : new WorldCreator(name)
                        .generator(new VoidGenerator())
                        .environment(cfg().environment())
                        .generateStructures(false)
                        .createWorld();
                if (world == null) {
                    future.completeExceptionally(new IllegalStateException("世界加载失败: " + name));
                } else {
                    applyWorldRules(world);
                    loaded.put(worldIndex, world);
                    emptySince.remove(worldIndex);
                    future.complete(world);
                    plugin.getLogger().info("家园世界已加载: " + name);
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            } finally {
                loading.remove(worldIndex);
            }
        });
        return future;
    }

    private void applyWorldRules(World world) {
        world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(org.bukkit.GameRule.DO_FIRE_TICK, false);
        world.setGameRule(org.bukkit.GameRule.MOB_GRIEFING, false);
        world.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setTime(6000L);
    }

    // ---------- 卸载 / 退休 ----------

    private void sweep() {
        long now = System.currentTimeMillis();
        long delayMs = cfg().unloadDelaySeconds() * 1000L;

        for (Map.Entry<Integer, World> entry : new ArrayList<>(loaded.entrySet())) {
            int index = entry.getKey();
            World world = entry.getValue();
            if (!world.getPlayers().isEmpty()) {
                emptySince.remove(index);
                continue;
            }
            long since = emptySince.computeIfAbsent(index, k -> now);
            if (now - since >= delayMs) {
                unloadWorld(index, world);
            }
        }
        emptySince.keySet().retainAll(loaded.keySet());
    }

    /**
     * 卸载世界：保存 → 卸载 → 异步归档各停放家园 → 删除世界目录。
     * 卸载失败重试告警（MythicDungeons 教训）。
     */
    private void unloadWorld(int index, World world) {
        if (!world.getPlayers().isEmpty()) {
            emptySince.remove(index);
            return;
        }
        String name = world.getName();
        try {
            world.save();
            boolean ok = Bukkit.unloadWorld(world, true);
            if (!ok) {
                plugin.getLogger().warning("家园世界卸载失败（可能被其他插件阻止）: "
                        + name + "，将在下个扫描周期重试");
                return;
            }
            loaded.remove(index);
            emptySince.remove(index);
            plugin.getLogger().info("家园世界已卸载: " + name);
            retireAsync(index);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "卸载家园世界异常: " + name, t);
        }
    }

    /**
     * 世界卸载后的收尾（异步执行）：
     * 归档每个停放家园 -> 清台账 -> 删世界目录 -> 移除 cleaning 标记。
     */
    private void retireAsync(int index) {
        String name = poolWorlds.get(index);
        File dir = archive.worldDir(name);
        CompletableFuture<Void> clean = new CompletableFuture<>();
        cleaning.put(index, clean);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // 1. 归档停放家园（解除停放）
                List<Parking> toArchive = new ArrayList<>();
                for (Parking p : parked.values()) {
                    if (p.worldIndex() == index) {
                        toArchive.add(p);
                    }
                }
                for (Parking p : toArchive) {
                    Home home = plugin.homeService() == null ? null
                            : plugin.homeService().byId(p.homeId()).orElse(null);
                    if (home == null) {
                        continue;
                    }
                    int n = archive.archive(home, dir,
                            p.globalSlot(cfg().homesPerWorld()));
                    if (n < 0) {
                        plugin.getLogger().warning("家园归档失败，保留世界文件待人工处理: home="
                                + p.homeId() + " world=" + name);
                        continue;   // 归档失败不删世界、不解除停放
                    }
                    parked.remove(p.homeId());
                    slotOwner.remove(p.globalSlot(cfg().homesPerWorld()));
                }
                // 2. 全部归档成功才删世界目录
                boolean anyLeft = parked.values().stream()
                        .anyMatch(p -> p.worldIndex() == index);
                if (!anyLeft && cfg().deleteWorldOnUnload()) {
                    if (archive.deleteRecursively(dir)) {
                        plugin.getLogger().info("家园世界已删除（下次重建）: " + name);
                    }
                }
                // 3. 清理该世界的槽位痕迹（世界销毁=全新一世）
                used.remove(index);
                tainted.remove(index);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "世界收尾异常: " + name, t);
            } finally {
                cleaning.remove(index);
                saveState();
                clean.complete(null);
            }
        });
    }

    /**
     * 强制卸载指定家园世界（管理员命令）。
     */
    public boolean forceUnload(int worldIndex) {
        World world = loaded.get(worldIndex);
        if (world == null) {
            return false;
        }
        World fallback = Bukkit.getWorlds().stream()
                .filter(w -> !isHomeWorld(w.getName()))
                .findFirst()
                .orElse(null);
        for (Player p : new ArrayList<>(world.getPlayers())) {
            if (fallback != null) {
                p.teleport(fallback.getSpawnLocation());
            }
        }
        unloadWorld(worldIndex, world);
        return true;
    }

    public int loadedCount() {
        return loaded.size();
    }

    /** 池状态概览（admin info 用） */
    public String poolInfo() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < poolWorlds.size(); i++) {
            sb.append("  §f").append(poolWorlds.get(i)).append(" §7- ")
                    .append(loaded.containsKey(i) ? "§a已加载" : "§7未加载")
                    .append(" §8| §f槽位 ").append(used.getOrDefault(i, Set.of()).size())
                    .append('/').append(cfg().homesPerWorld())
                    .append(" §8| §c污染 ").append(tainted.getOrDefault(i, Set.of()).size());
            if (cleaning.containsKey(i)) {
                sb.append(" §8| §e清理中");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public void shutdown() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        World fallback = Bukkit.getWorlds().stream()
                .filter(w -> !isHomeWorld(w.getName()))
                .findFirst()
                .orElse(null);
        for (Map.Entry<Integer, World> entry : loaded.entrySet()) {
            World world = entry.getValue();
            for (Player p : world.getPlayers()) {
                if (fallback != null) {
                    p.teleport(fallback.getSpawnLocation());
                }
            }
            try {
                world.save();
                Bukkit.unloadWorld(world, true);
            } catch (Throwable ignored) {
            }
        }
        // 同步归档+删除（关服时不能依赖异步调度器）
        for (int i = 0; i < poolWorlds.size(); i++) {
            File dir = archive.worldDir(poolWorlds.get(i));
            if (!dir.isDirectory()) {
                continue;
            }
            List<Parking> toArchive = new ArrayList<>();
            for (Parking p : parked.values()) {
                if (p.worldIndex() == i) {
                    toArchive.add(p);
                }
            }
            for (Parking p : toArchive) {
                Home home = plugin.homeService() == null ? null
                        : plugin.homeService().byId(p.homeId()).orElse(null);
                if (home == null) {
                    continue;
                }
                if (archive.archive(home, dir, p.globalSlot(cfg().homesPerWorld())) >= 0) {
                    parked.remove(p.homeId());
                    slotOwner.remove(p.globalSlot(cfg().homesPerWorld()));
                }
            }
            int fi = i;
            boolean anyLeft = parked.values().stream().anyMatch(p -> p.worldIndex() == fi);
            if (!anyLeft && cfg().deleteWorldOnUnload()) {
                archive.deleteRecursively(dir);
            }
            used.remove(fi);
            tainted.remove(fi);
        }
        loaded.clear();
        emptySince.clear();
        saveState();
    }

    // ---------- 污染标记 ----------

    /**
     * 玩家在家园世界移动时，把其模拟距离覆盖到的空闲槽位标记为污染。
     * 污染槽位不再用于停放（其虚空区块可能已驻留内存）。
     * 槽内留边 >= 模拟距离时此机制理论上永不触发，作为自定义配置的兜底。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        World world = e.getPlayer().getWorld();
        int worldIndex = parseWorldIndex(world.getName());
        if (worldIndex < 0) {
            return;
        }
        int reach = cfg().simulationDistanceChunks() * 16 + 16;
        var to = e.getTo();
        if (to == null) {
            return;
        }
        int hpw = cfg().homesPerWorld();
        int slotSize = cfg().slotSize();
        int origin = SlotAllocator.gridOrigin(hpw, slotSize);
        int cols = SlotAllocator.cols(hpw);

        int gx1 = (int) Math.floor((to.getX() - reach - origin) / (double) slotSize);
        int gx2 = (int) Math.floor((to.getX() + reach - origin) / (double) slotSize);
        int gz1 = (int) Math.floor((to.getZ() - reach - origin) / (double) slotSize);
        int gz2 = (int) Math.floor((to.getZ() + reach - origin) / (double) slotSize);

        Set<Integer> burned = used.getOrDefault(worldIndex, Set.of());
        Set<Integer> dirty = tainted.computeIfAbsent(worldIndex, k -> ConcurrentHashMap.newKeySet());
        for (int gx = gx1; gx <= gx2; gx++) {
            for (int gz = gz1; gz <= gz2; gz++) {
                if (gx < 0 || gx >= cols || gz < 0 || gz >= cols) {
                    continue;
                }
                int inner = gz * cols + gx;
                if (inner < hpw && !burned.contains(inner) && dirty.add(inner)) {
                    plugin.getLogger().info("槽位污染标记: " + world.getName()
                            + " slot#" + inner + "（玩家接近空槽）");
                }
            }
        }
    }
}
