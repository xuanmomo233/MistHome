package dev.mist.home.actions;

import org.bukkit.entity.Player;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.model.HomeVisibility;

/**
 * 命令与 GUI 共享的家园动作。
 */
public final class HomeActions {

    private HomeActions() {
    }

    /**
     * 参观指定家园：校验角色/可见性后传送。
     * BANNED 永远拒绝；PRIVATE 仅成员及以上；PUBLIC 开放参观。
     *
     * @return 是否成功发起传送
     */
    public static boolean visit(MistHomePlugin plugin, Player player, Home home) {
        String prefix = plugin.mistConfig().prefix();
        if (!player.hasPermission("misthome.visit")) {
            player.sendMessage(prefix + "§c无权限");
            return false;
        }
        HomeRole role = plugin.homeService().roleOf(home, player.getUniqueId());
        boolean bypass = player.hasPermission("misthome.bypass")
                || player.hasPermission("misthome.admin");
        if (!bypass && !role.canVisit()) {
            player.sendMessage(prefix + "§c你已被该家园封禁");
            return false;
        }
        boolean allowed = bypass || home.visibility() == HomeVisibility.PUBLIC
                || role.canBuild()
                || home.owner().equals(player.getUniqueId());
        if (!allowed) {
            player.sendMessage(prefix + "§c该家园未公开，需要邀请才能进入");
            return false;
        }
        plugin.teleportService().teleportToHome(player, home);
        return true;
    }
}
