package dev.mist.home.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.config.MistConfig;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * 家园世界生命周期管理（副本式按需加载 / 无人超时卸载）。
 * <p>
 * 世界持久化为普通世界文件夹（服务端目录下 misthome_N），
 * 仅在需要进入时 WorldCreator 加载；所有玩家离开超过
 * {@code unload-delay-seconds} 后保存并卸载。
 * <p>
 * 卸载失败处理参考 MythicDungeons 的教训：其他插件可能通过
 * WorldUnloadEvent 取消卸载或持有强引用，导致世界泄漏在内存中。
 * 这里做两件事：a) 卸载失败打 WARN 并进入重试队列；
 * b) 周期兜底扫描，超时未卸载成功的持续告警。
 */
public class HomeWorldManager {

    private final MistHomePlugin plugin;
    private final MistConfig config;

    /** worldIndex -> 已加载的世界 */
    private final Map<Integer, World> loaded = new HashMap<>();
    /** worldIndex -> 世界最后变空的时间戳（毫秒），未变空则无记录 */
    private final Map<Integer, Long> emptySince = new HashMap<>();
    /** worldIndex -> 正在进行的加载任务（防止并发加载同一世界） */
    private final Map<Integer, CompletableFuture<World>> loading = new HashMap<>();

    private BukkitTask sweepTask;

    public HomeWorldManager(MistHomePlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.mistConfig();
    }

    public void start() {
        long period = config.sweepIntervalSeconds() * 20L;
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, period, period);
    }

    public String worldName(int worldIndex) {
        return config.worldPrefix() + worldIndex;
    }

    public boolean isHomeWorld(String worldName) {
        return worldName != null && worldName.startsWith(config.worldPrefix());
    }

    public boolean isLoaded(int worldIndex) {
        return loaded.containsKey(worldIndex);
    }

    /**
     * 确保指定索引的家园世界已加载（主线程安全调用）。
     * 若世界尚未加载则通过 WorldCreator 加载（文件夹存在则直接挂载，
     * 不存在则走 VoidGenerator 创建全新虚空世界）。
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

        CompletableFuture<World> future = new CompletableFuture<>();
        loading.put(worldIndex, future);

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                String name = worldName(worldIndex);
                World already = Bukkit.getWorld(name);
                World world = already != null ? already : new WorldCreator(name)
                        .generator(new VoidGenerator())
                        .environment(World.Environment.NORMAL)
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

    /**
     * 周期兜底扫描：记录各家园世界变空时间，超时后卸载。
     * 覆盖所有"玩家离开"途径（传送走、下线、跨世界等），无需逐个事件埋点。
     */
    private void sweep() {
        long now = System.currentTimeMillis();
        long delayMs = config.unloadDelaySeconds() * 1000L;

        for (Map.Entry<Integer, World> entry : loaded.entrySet()) {
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
        // 清理已卸载世界的计时记录
        emptySince.keySet().retainAll(loaded.keySet());
    }

    private void unloadWorld(int index, World world) {
        // 卸载前再确认无人（防御异步竞态）
        if (!world.getPlayers().isEmpty()) {
            emptySince.remove(index);
            return;
        }
        String name = world.getName();
        try {
            world.save();
            boolean ok = Bukkit.unloadWorld(world, true);
            if (ok) {
                loaded.remove(index);
                emptySince.remove(index);
                plugin.getLogger().info("家园世界已卸载: " + name);
            } else {
                // 其他插件取消 WorldUnloadEvent 或仍持有引用 -> 泄漏警告（参考 MythicDungeons）
                plugin.getLogger().warning("家园世界卸载失败（可能被其他插件阻止）: " + name
                        + "，将在下个扫描周期重试");
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "卸载家园世界异常: " + name, t);
        }
    }

    /**
     * 关服时优雅卸载所有家园世界：先把玩家送回主世界出生点，再逐一卸载。
     */
    public void shutdown() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        World fallback = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
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
        loaded.clear();
        emptySince.clear();
    }

    /** 世界文件夹是否存在于磁盘（用于判断是否为新建世界） */
    public boolean worldFolderExists(int worldIndex) {
        return new File(Bukkit.getWorldContainer(), worldName(worldIndex)).isDirectory();
    }
}
