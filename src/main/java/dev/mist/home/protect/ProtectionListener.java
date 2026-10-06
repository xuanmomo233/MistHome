package dev.mist.home.protect;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.HomeRole;

/**
 * 家园区域保护（PlotSquared 式事件拦截）。
 * 判定链：坐标反查家园 -> 查玩家角色 -> 按角色权限放行/取消。
 * 可用范围外的槽位预留区同样禁止操作（防止侵占未解锁空间）。
 */
public class ProtectionListener implements Listener {

    private final MistHomePlugin plugin;

    public ProtectionListener(MistHomePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        // TODO: homeService.homeAt(...) + roleOf 判定，无权则 e.setCancelled(true) + 提示
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        // TODO: 同上
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        // TODO: 容器/门/按钮等交互按角色限制（VISITOR 默认禁交互，可配白名单）
    }
}
