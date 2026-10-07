package dev.mist.home.boundary;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.home.HomeService;
import dev.mist.home.model.Home;
import dev.mist.home.world.HomeRegion;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 家园边界可视化。
 * <p>
 * 优先 ProtocolLib 发送世界边界包（经 ProtocolLibBorder 委托类
 * 隔离软依赖），未安装时降级为沿可用边界周期刷粒子线。
 * 玩家进入家园可用范围显示边界，离开还原。
 */
public class BorderService implements Listener {

    private final MistHomePlugin plugin;
    private boolean protocolLibAvailable;
    private ProtocolLibBorder protocol;

    /** player -> 当前已显示边界的家园（null 表示未显示） */
    private final Map<UUID, Home> shown = new ConcurrentHashMap<>();
    /** 粒子降级定时任务 */
    private BukkitTask particleTask;

    public BorderService(MistHomePlugin plugin) {
        this.plugin = plugin;
    }

    public void hook() {
        protocolLibAvailable = plugin.mistConfig().preferProtocolLib()
                && Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;
        Bukkit.getPluginManager().registerEvents(this, plugin);

        if (protocolLibAvailable) {
            protocol = new ProtocolLibBorder();
            plugin.getLogger().info("已检测到 ProtocolLib，使用世界边界包显示家园范围");
        } else {
            plugin.getLogger().info("未检测到 ProtocolLib，边界降级为粒子显示");
        }
        // 粒子兜底：PL 包可能因客户端时序丢帧看不到，强制粒子模式或
        // 无 PL 时都启用粒子任务（PL 模式下粒子默认关闭，可强制叠加显示）
        if (!protocolLibAvailable || plugin.mistConfig().forceParticles()) {
            startParticleTask();
        }
    }

    public void shutdown() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
        // 还原所有显示中的边界
        if (protocolLibAvailable) {
            for (UUID uuid : shown.keySet()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    try {
                        protocol.reset(p, p.getWorld());
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        shown.clear();
    }

    // ---------- 边界切换 ----------

    /**
     * 玩家位置变化时调用：进入家园可用区显示边界，离开还原。
     * 注意：必须传入事件中的目标 Location，不能用 player.getLocation()——
     * MONITOR 阶段传送尚未生效，getLocation 仍返回旧位置。
     */
    private void update(Player player, Location loc) {
        HomeService hs = plugin.homeService();
        Optional<Home> opt = hs.homeAt(loc.getWorld(), loc.getBlockX(), loc.getBlockZ());
        HomeRegion region = opt.flatMap(hs::regionOf).orElse(null);
        // 进入可用区即显示；在槽位缓冲带内接近可用边缘（approach 距离）也提前显示，
        // 避免玩家从虚空走近时踩线才弹出边界的"延迟感"
        double dist = region == null ? Double.MAX_VALUE
                : distToUsable(region, loc.getX(), loc.getZ());
        boolean inside = dist <= plugin.mistConfig().approachDistance();

        Home current = shown.get(player.getUniqueId());
        if (plugin.mistConfig().borderDebug()) {
            plugin.getLogger().info("[边界调试] " + player.getName()
                    + " @(" + loc.getBlockX() + "," + loc.getBlockZ() + ")"
                    + " world=" + loc.getWorld().getName()
                    + " home=" + opt.map(h -> String.valueOf(h.id())).orElse("无")
                    + " dist=" + (dist == Double.MAX_VALUE ? "∞" : (int) dist)
                    + " inside=" + inside + " 已显示=" + (current != null));
        }
        if (inside) {
            Home home = opt.get();
            if (current == null || current.id() != home.id()) {
                showBorder(player, home, region);
                shown.put(player.getUniqueId(), home);
            }
        } else if (current != null) {
            clearBorder(player);
            shown.remove(player.getUniqueId());
        }
    }

    /** 到可用区的切比雪夫距离（在区内返回 0） */
    private static double distToUsable(HomeRegion r, double x, double z) {
        double dx = Math.max(0, Math.max(r.usableMinX() - x, x - r.usableMaxX()));
        double dz = Math.max(0, Math.max(r.usableMinZ() - z, z - r.usableMaxZ()));
        return Math.max(dx, dz);
    }

    private void showBorder(Player player, Home home, HomeRegion region) {
        if (protocolLibAvailable && protocol != null) {
            try {
                protocol.show(player, region);
            } catch (Throwable t) {
                plugin.getLogger().warning("ProtocolLib 边界包发送失败: " + t.getMessage());
            }
        }
        // 粒子降级模式下不需要主动显示，particleTask 每周期检查 shown
    }

    private void clearBorder(Player player) {
        if (protocolLibAvailable && protocol != null) {
            try {
                protocol.reset(player, player.getWorld());
            } catch (Throwable t) {
                plugin.getLogger().warning("ProtocolLib 边界还原失败: " + t.getMessage());
            }
        }
    }

    // ---------- 粒子降级 ----------

    private void startParticleTask() {
        long interval = Math.max(5, plugin.mistConfig().particleIntervalTicks());
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map.Entry<UUID, Home> entry : shown.entrySet()) {
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p == null || !p.isOnline()) {
                    shown.remove(entry.getKey());
                    continue;
                }
                HomeRegion region = plugin.homeService()
                        .regionOf(entry.getValue()).orElse(null);
                if (region == null) {
                    continue;
                }
                spawnBorderParticles(p, region);
            }
        }, interval, interval);
    }

    /** 在玩家视高位置沿可用边界四边刷粒子 */
    private void spawnBorderParticles(Player player, HomeRegion region) {
        double minX = region.usableMinX() + 0.5;
        double maxX = region.usableMaxX() - 0.5;
        double minZ = region.usableMinZ() + 0.5;
        double maxZ = region.usableMaxZ() - 0.5;
        double y = player.getLocation().getY() + 1.0;

        // 四边，间隔 2 格一个粒子
        for (double x = minX; x <= maxX; x += 2.0) {
            player.spawnParticle(Particle.END_ROD, x, y, minZ, 1, 0, 0, 0, 0);
            player.spawnParticle(Particle.END_ROD, x, y, maxZ, 1, 0, 0, 0, 0);
        }
        for (double z = minZ; z <= maxZ; z += 2.0) {
            player.spawnParticle(Particle.END_ROD, minX, y, z, 1, 0, 0, 0, 0);
            player.spawnParticle(Particle.END_ROD, maxX, y, z, 1, 0, 0, 0, 0);
        }
    }

    // ---------- 事件 ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        // 仅方块坐标变化才重新判定
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockZ() == to.getBlockZ()
                && from.getWorld().equals(to.getWorld())) {
            return;
        }
        update(e.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        // 跨世界传送后刷新（含进入/离开家园世界）
        update(e.getPlayer(), to);
        // 跨维度时客户端可能尚未完成世界切换，边界包落在旧维度被丢弃
        // —— 延迟强制重发（必须先清 shown 缓存，否则同家园判定会跳过发包）。
        // 5t 覆盖正常切换，15t 兜底 Mohist 上较慢的区块流/维度同步
        Player p = e.getPlayer();
        for (long delay : new long[]{5L, 15L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) {
                    shown.remove(p.getUniqueId());
                    update(p, p.getLocation());
                }
            }, delay);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        shown.remove(e.getPlayer().getUniqueId());
        // PL 边界是客户端显示，退出自动消失无需还原
    }
}
