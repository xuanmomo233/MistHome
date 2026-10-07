package dev.mist.home.protect;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.home.HomeService;
import dev.mist.home.model.HomeRole;

/**
 * 家园区域保护（PlotSquared 式事件拦截）。
 * 判定链：坐标反查家园 -> 查玩家角色 -> 按角色权限放行/取消。
 * 可用范围外的槽位预留区同样禁止操作（防止侵占未解锁空间）。
 */
public class ProtectionListener implements Listener {

    private final MistHomePlugin plugin;
    private final HomeService homeService;

    public ProtectionListener(MistHomePlugin plugin, HomeService homeService) {
        this.plugin = plugin;
        this.homeService = homeService;
    }

    private boolean shouldSkip() {
        // 模板粘贴期间不拦截 WorldEdit/插件自己产生的事件
        if (plugin.templates().isPasting()) {
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (shouldSkip()) return;
        // TODO: homeService.homeAt(...) + roleOf 判定，无权则 e.setCancelled(true) + 提示
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (shouldSkip()) return;
        // TODO: 同上
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (shouldSkip()) return;
        // TODO: 容器/门/按钮等交互按角色限制（VISITOR 默认禁交互，可配白名单）
    }
}
