package dev.mist.home.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.Home;
import dev.mist.home.world.SlotAllocator;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 传送服务：吟唱（warmup）+ 冷却（cooldown）+ 移动/受伤打断。
 * <p>
 * 流程：cooldown 检查 → 吟唱倒计时（可选移动打断）→ 异步加载世界 → 主线程传送。
 * 所有 Bukkit 调用均在主线程完成。
 */
public class TeleportService implements Listener {

    private final MistHomePlugin plugin;
    /** playerUuid -> 冷却结束时间戳（毫秒） */
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    /** playerUuid -> 进行中的吟唱任务 */
    private final Map<UUID, BukkitTask> warmups = new ConcurrentHashMap<>();

    public TeleportService(MistHomePlugin plugin) {
        this.plugin = plugin;
    }

    /** 剩余冷却秒数（0 = 无冷却） */
    public long cooldownRemaining(UUID player) {
        Long until = cooldowns.get(player);
        if (until == null) {
            return 0;
        }
        long remain = (until - System.currentTimeMillis() + 999) / 1000;
        return Math.max(0, remain);
    }

    /**
     * 传送到家园出生点。
     *
     * @return future 传送是否最终完成（false = 冷却中/被打断/世界加载失败/玩家离线）
     */
    public CompletableFuture<Boolean> teleportToHome(Player player, Home home) {
        int worldIndex = SlotAllocator.worldIndexOf(home.slotIndex(),
                plugin.mistConfig().homesPerWorld());
        return teleport(player, () -> plugin.worldManager().ensureLoaded(worldIndex)
                .thenApply(world -> new Location(world, home.spawnX(), home.spawnY(),
                        home.spawnZ(), home.spawnYaw(), home.spawnPitch())));
    }

    /**
     * 通用传送：吟唱后执行目标解析（含世界加载）并传送。
     * 目标解析返回的 Location 必须带 World。
     */
    public CompletableFuture<Boolean> teleport(Player player,
                                               Supplier<CompletableFuture<Location>> target) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        long remaining = cooldownRemaining(player.getUniqueId());
        if (remaining > 0) {
            player.sendMessage(plugin.mistConfig().prefix()
                    + "§c传送冷却中，还需等待 " + remaining + " 秒");
            result.complete(false);
            return result;
        }

        cancelWarmup(player.getUniqueId());
        int warmup = plugin.mistConfig().warmupSeconds();

        Runnable proceed = () -> {
            try {
                target.get().whenComplete((loc, err) -> {
                    // ensureLoaded 在主线程完成 future，此处安全调用 Bukkit API
                    if (err != null || loc == null || loc.getWorld() == null) {
                        player.sendMessage(plugin.mistConfig().prefix()
                                + "§c传送失败：目标世界加载异常");
                        result.complete(false);
                        return;
                    }
                    if (!player.isOnline()) {
                        result.complete(false);
                        return;
                    }
                    player.teleport(loc);
                    recordCooldown(player.getUniqueId());
                    result.complete(true);
                });
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        };

        if (warmup <= 0) {
            proceed.run();
        } else {
            player.sendMessage(plugin.mistConfig().prefix()
                    + "§7传送吟唱中，" + warmup + " 秒后传送，请保持不动");
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                warmups.remove(player.getUniqueId());
                proceed.run();
            }, warmup * 20L);
            warmups.put(player.getUniqueId(), task);
        }
        return result;
    }

    private void recordCooldown(UUID player) {
        long cd = plugin.mistConfig().cooldownSeconds();
        if (cd > 0) {
            cooldowns.put(player, System.currentTimeMillis() + cd * 1000L);
        }
    }

    private void cancelWarmup(UUID player) {
        BukkitTask task = warmups.remove(player);
        if (task != null) {
            task.cancel();
        }
    }

    private void interrupt(UUID player, String reason) {
        if (warmups.remove(player) != null) {
            Player p = Bukkit.getPlayer(player);
            if (p != null) {
                p.sendMessage(plugin.mistConfig().prefix() + "§c传送已打断：" + reason);
            }
            cancelWarmup(player);
        }
    }

    // ---------- 打断监听 ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!plugin.mistConfig().interruptOnMove()) {
            return;
        }
        if (!warmups.containsKey(e.getPlayer().getUniqueId())) {
            return;
        }
        // 仅方块坐标变化才算移动，转头不打断
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        interrupt(e.getPlayer().getUniqueId(), "移动");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!plugin.mistConfig().interruptOnDamage()) {
            return;
        }
        if (e.getEntity() instanceof Player p) {
            interrupt(p.getUniqueId(), "受伤");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancelWarmup(e.getPlayer().getUniqueId());
    }
}
