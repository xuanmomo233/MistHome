package dev.mist.home.protect;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.home.HomeService;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.model.HomeVisibility;
import dev.mist.home.world.HomeRegion;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 家园区域保护（PlotSquared 式事件拦截）。
 * <p>
 * 判定链：坐标 → 家园（homeAt）→ 可用范围（containsUsable）→ 角色权限。
 * <ul>
 *   <li>家园世界内不在任何槽位上（gap/未分配/网格外）→ 全部拒绝</li>
 *   <li>槽位内但超出当前档可用范围（预留区）→ 全部拒绝（含 Owner）</li>
 *   <li>可用范围内 → 按角色：BUILD 需 MEMBER+，VISIT 需非 BANNED 且（公开或成员）</li>
 * </ul>
 */
public class ProtectionListener implements Listener {

    private enum Perm { BUILD, VISIT }

    private final MistHomePlugin plugin;
    private final HomeService homeService;
    /** 拒绝提示节流（每人 1s），防止刷消息 */
    private final Map<UUID, Long> denyMsgAt = new ConcurrentHashMap<>();

    public ProtectionListener(MistHomePlugin plugin, HomeService homeService) {
        this.plugin = plugin;
        this.homeService = homeService;
    }

    // ========== 判定核心 ==========

    /**
     * 玩家是否有权在该位置操作。
     * 非家园世界一律放行；家园世界内无主之地/预留区一律拒绝。
     */
    private boolean allow(Player player, Location loc, Perm perm) {
        World w = loc.getWorld();
        if (w == null || !plugin.worldManager().isHomeWorld(w.getName())) {
            return true;
        }
        Optional<Home> opt = homeService.homeAt(w, loc.getBlockX(), loc.getBlockZ());
        if (opt.isEmpty()) {
            return false;   // gap 隔离带 / 未分配槽位 / 超出网格
        }
        Home home = opt.get();
        HomeRegion region = homeService.regionOf(home);
        if (!region.containsUsable(loc.getX(), loc.getZ())) {
            return false;   // 预留区（未解锁范围）
        }
        HomeRole role = homeService.roleOf(home, player.getUniqueId());
        return switch (perm) {
            case BUILD -> role.canBuild();
            case VISIT -> role.canVisit()
                    && (home.visibility() == HomeVisibility.PUBLIC || role.canBuild());
        };
    }

    /**
     * 该位置是否处于某家园的可用范围内（用于无玩家的保护：爆炸、活塞、流体）。
     */
    private boolean insideUsable(Location loc) {
        World w = loc.getWorld();
        if (w == null || !plugin.worldManager().isHomeWorld(w.getName())) {
            return false;
        }
        Optional<Home> opt = homeService.homeAt(w, loc.getBlockX(), loc.getBlockZ());
        if (opt.isEmpty()) {
            return false;
        }
        HomeRegion region = homeService.regionOf(opt.get());
        return region.containsUsable(loc.getX(), loc.getZ());
    }

    /**
     * 判定一组位置是否全部落在同一家园可用范围内，或全部在范围外。
     * 跨界（部分在内部分在外）返回 false —— 用于活塞跨界防护。
     */
    private boolean allInSameUsableOrAllOutside(java.util.List<Location> locs) {
        Home involved = null;
        boolean hasOutside = false;
        for (Location loc : locs) {
            World w = loc.getWorld();
            if (w == null || !plugin.worldManager().isHomeWorld(w.getName())) {
                continue;   // 非家园世界的点忽略
            }
            Optional<Home> opt = homeService.homeAt(w, loc.getBlockX(), loc.getBlockZ());
            HomeRegion region = opt.map(homeService::regionOf).orElse(null);
            boolean inside = region != null
                    && region.containsUsable(loc.getX(), loc.getZ());
            if (inside) {
                if (involved == null) {
                    involved = opt.get();
                } else if (involved != opt.get()) {
                    return false;   // 跨两个家园
                }
            } else {
                hasOutside = true;
            }
        }
        // 有内点且有外点 → 跨界；全外 → 放行；全在同一家园内 → 放行
        return !(involved != null && hasOutside);
    }

    private void deny(Player player, org.bukkit.event.Cancellable e, String reason) {
        e.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = denyMsgAt.get(player.getUniqueId());
        if (last == null || now - last > 1000) {
            denyMsgAt.put(player.getUniqueId(), now);
            player.sendMessage(plugin.mistConfig().prefix() + "§c" + reason);
        }
    }

    private boolean shouldSkip() {
        return plugin.templates().isPasting();
    }

    // ========== 方块建造 ==========

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (shouldSkip()) return;
        if (!allow(e.getPlayer(), e.getBlockPlaced().getLocation(), Perm.BUILD)) {
            deny(e.getPlayer(), e, "你无法在此建造");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (shouldSkip()) return;
        if (!allow(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD)) {
            deny(e.getPlayer(), e, "你无法在此破坏");
        }
    }

    // ========== 交互 ==========

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (shouldSkip()) return;
        Action action = e.getAction();
        if (action == Action.RIGHT_CLICK_BLOCK || action == Action.PHYSICAL) {
            Block clicked = e.getClickedBlock();
            if (clicked != null && !allow(e.getPlayer(), clicked.getLocation(), Perm.BUILD)) {
                deny(e.getPlayer(), e, "你无法在此交互");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (shouldSkip()) return;
        if (!allow(e.getPlayer(), e.getRightClicked().getLocation(), Perm.BUILD)) {
            deny(e.getPlayer(), e, "你无法操作该实体");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (shouldSkip()) return;
        if (!allow(e.getPlayer(), e.getRightClicked().getLocation(), Perm.BUILD)) {
            deny(e.getPlayer(), e, "你无法操作盔甲架");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent e) {
        if (shouldSkip()) return;
        if (!allow(e.getPlayer(), e.getEntity().getLocation(), Perm.BUILD)) {
            deny(e.getPlayer(), e, "你无法操作该实体");
        }
    }

    // ========== 桶类（液体放置/舀取） ==========

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (shouldSkip()) return;
        if (!allow(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD)) {
            deny(e.getPlayer(), e, "你无法在此放置液体");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (shouldSkip()) return;
        if (!allow(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD)) {
            deny(e.getPlayer(), e, "你无法在此舀取液体");
        }
    }

    // ========== 悬挂实体（画/展示框/荧光展示框） ==========

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (shouldSkip()) return;
        if (e.getPlayer() != null
                && !allow(e.getPlayer(), e.getBlock().getLocation(), Perm.BUILD)) {
            deny(e.getPlayer(), e, "你无法在此放置");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (shouldSkip()) return;
        Entity remover = e.getRemover();
        if (remover instanceof Player p) {
            if (!allow(p, e.getEntity().getLocation(), Perm.BUILD)) {
                deny(p, e, "你无法破坏该物品");
            }
        } else if (insideUsable(e.getEntity().getLocation())) {
            // 非玩家破坏（爆炸等）在可用范围内一律拦截
            e.setCancelled(true);
        }
    }

    // ========== 实体伤害（PVP 禁止 + 实体保护） ==========

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (shouldSkip()) return;
        Player attacker = attackerOf(e.getDamager());
        if (e.getEntity() instanceof Player victim && attacker != null
                && !attacker.getUniqueId().equals(victim.getUniqueId())) {
            // 家园世界内全面禁 PVP
            World w = victim.getWorld();
            if (w != null && plugin.worldManager().isHomeWorld(w.getName())) {
                deny(attacker, e, "家园内禁止 PVP");
            }
            return;
        }
        if (attacker != null) {
            // 玩家伤害非玩家实体（动物/盔甲架/展示框等）→ 需建造权
            if (!allow(attacker, e.getEntity().getLocation(), Perm.BUILD)) {
                deny(attacker, e, "你无法伤害该实体");
            }
        }
    }

    private static Player attackerOf(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile proj) {
            ProjectileSource shooter = proj.getShooter();
            if (shooter instanceof Player p) {
                return p;
            }
        }
        return null;
    }

    // ========== 爆炸：过滤可用范围内的方块 ==========

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> insideUsable(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> insideUsable(b.getLocation()));
    }

    // ========== 活塞：跨界拦截 ==========

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        java.util.List<Location> locs = new java.util.ArrayList<>();
        locs.add(e.getBlock().getLocation());
        for (Block b : e.getBlocks()) {
            locs.add(b.getLocation());
            locs.add(b.getRelative(e.getDirection()).getLocation());
        }
        if (!allInSameUsableOrAllOutside(locs)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        java.util.List<Location> locs = new java.util.ArrayList<>();
        locs.add(e.getBlock().getLocation());
        for (Block b : e.getBlocks()) {
            locs.add(b.getLocation());
            locs.add(b.getRelative(e.getDirection()).getLocation());
        }
        if (!allInSameUsableOrAllOutside(locs)) {
            e.setCancelled(true);
        }
    }

    // ========== 流体：跨界拦截 ==========

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent e) {
        boolean fromInside = insideUsable(e.getBlock().getLocation());
        boolean toInside = insideUsable(e.getToBlock().getLocation());
        if (fromInside != toInside) {
            e.setCancelled(true);   // 流向跨界（进/出可用区）一律拦截
        }
    }

    // ========== 载具进入（船/矿车/马） ==========

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleEnter(org.bukkit.event.vehicle.VehicleEnterEvent e) {
        if (shouldSkip()) return;
        if (e.getEntered() instanceof Player p
                && !allow(p, e.getVehicle().getLocation(), Perm.BUILD)) {
            deny(p, e, "你无法驾驶该载具");
        }
    }

    // ========== 清理 ----------

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        denyMsgAt.remove(e.getPlayer().getUniqueId());
    }

    // ========== 末影珍珠/紫颂果：阻止进入无权区域 ==========

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (shouldSkip()) return;
        PlayerTeleportEvent.TeleportCause cause = e.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && cause != PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
            return;
        }
        Location to = e.getTo();
        if (to != null && !allow(e.getPlayer(), to, Perm.VISIT)) {
            deny(e.getPlayer(), e, "你无法进入该家园");
        }
    }
}
