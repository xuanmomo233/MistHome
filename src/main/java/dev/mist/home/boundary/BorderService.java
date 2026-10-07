package dev.mist.home.boundary;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
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
 * 优先 ProtocolLib 发送世界边界包（ClientboundInitializeBorder），
 * 未安装时降级为沿可用边界周期刷粒子线。
 * 玩家进入家园可用范围显示边界，离开还原。
 */
public class BorderService implements Listener {

    private final MistHomePlugin plugin;
    private boolean protocolLibAvailable;
    private ProtocolManager protocol;

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
            protocol = ProtocolLibrary.getProtocolManager();
            plugin.getLogger().info("已检测到 ProtocolLib，使用世界边界包显示家园范围");
        } else {
            plugin.getLogger().info("未检测到 ProtocolLib，边界降级为粒子显示");
            startParticleTask();
        }
    }

    public void shutdown() {
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
        // 还原所有显示中的边界
        for (UUID uuid : shown.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                sendResetPacket(p);
            }
        }
        shown.clear();
    }

    // ---------- 边界切换 ----------

    /**
     * 玩家位置变化时调用：进入家园可用区显示边界，离开还原。
     */
    private void update(Player player) {
        HomeService hs = plugin.homeService();
        Location loc = player.getLocation();
        Optional<Home> opt = hs.homeAt(loc.getWorld(), loc.getBlockX(), loc.getBlockZ());
        HomeRegion region = opt.map(hs::regionOf).orElse(null);
        boolean inside = region != null && region.containsUsable(loc.getX(), loc.getZ());

        Home current = shown.get(player.getUniqueId());
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

    private void showBorder(Player player, Home home, HomeRegion region) {
        if (protocolLibAvailable) {
            sendWorldBorderPacket(player, region);
        }
        // 粒子降级模式下不需要主动显示，particleTask 每周期检查 shown
    }

    private void clearBorder(Player player) {
        if (protocolLibAvailable) {
            sendResetPacket(player);
        }
    }

    // ---------- ProtocolLib 发包 ----------

    private void sendWorldBorderPacket(Player player, HomeRegion region) {
        try {
            PacketContainer pkt = protocol.createPacket(PacketType.Play.Server.INITIALIZE_BORDER);
            double diameter = Math.max(region.usableRadius() * 2.0, 1.0);
            // 字段序：newX/newZ/oldDiameter/newDiameter (doubles 0-3)
            pkt.getDoubles().write(0, (double) region.centerX());
            pkt.getDoubles().write(1, (double) region.centerZ());
            pkt.getDoubles().write(2, diameter);
            pkt.getDoubles().write(3, diameter);
            // speed (longs 0)：0 = 立即生效
            pkt.getLongs().write(0, 0L);
            // newAbsoluteMaxSize / warningTime / warningBlocks (ints 0-2)
            pkt.getIntegers().write(0, 29999984);
            pkt.getIntegers().write(1, 0);
            pkt.getIntegers().write(2, 0);
            protocol.sendServerPacket(player, pkt);
        } catch (Throwable t) {
            plugin.getLogger().warning("ProtocolLib 边界包发送失败: " + t.getMessage());
        }
    }

    /** 还原为世界真实边界 */
    private void sendResetPacket(Player player) {
        try {
            var wb = player.getWorld().getWorldBorder();
            PacketContainer pkt = protocol.createPacket(PacketType.Play.Server.INITIALIZE_BORDER);
            pkt.getDoubles().write(0, wb.getCenter().getX());
            pkt.getDoubles().write(1, wb.getCenter().getZ());
            double size = wb.getSize();
            pkt.getDoubles().write(2, size);
            pkt.getDoubles().write(3, size);
            pkt.getLongs().write(0, 0L);
            pkt.getIntegers().write(0, wb.getWarningDistance());
            pkt.getIntegers().write(1, wb.getWarningTime());
            pkt.getIntegers().write(2, wb.getWarningDistance());
            protocol.sendServerPacket(player, pkt);
        } catch (Throwable t) {
            plugin.getLogger().warning("ProtocolLib 边界还原失败: " + t.getMessage());
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
                HomeRegion region = plugin.homeService().regionOf(entry.getValue());
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
        update(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Location to = e.getTo();
        if (to == null) {
            return;
        }
        // 跨世界传送后刷新（含进入/离开家园世界）
        update(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        shown.remove(e.getPlayer().getUniqueId());
        // PL 边界是客户端显示，退出自动消失无需还原
    }
}
