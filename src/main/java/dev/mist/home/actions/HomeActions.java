package dev.mist.home.actions;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.model.HomeVisibility;

import java.util.concurrent.CompletableFuture;

/**
 * 命令与 GUI 共享的家园动作。
 */
public final class HomeActions {

    private HomeActions() {
    }

    /** 家是否归属其他子服（跨服模式下用于判断是否需要 Connect 跳服） */
    public static boolean isRemoteHome(MistHomePlugin plugin, Home home) {
        var cfg = plugin.mistConfig();
        return cfg.crossServerEnabled()
                && !home.server().isEmpty()
                && !home.server().equalsIgnoreCase(cfg.serverName());
    }

    /**
     * 家未登记归属且本机没有其存档——跨服模式下属于"别服还没启动新版认领"的家。
     * 绝不能本地停放（归档不存在会生成空地污染槽位）。
     */
    public static boolean isUnclaimedElsewhere(MistHomePlugin plugin, Home home) {
        var cfg = plugin.mistConfig();
        return cfg.crossServerEnabled()
                && home.server().isEmpty()
                && !plugin.worldManager().archive().hasArchive(home);
    }

    /**
     * 统一进家路由：远程家跳服 / 未登记家告警 / 本地家传送。
     */
    public static void goHome(MistHomePlugin plugin, Player player, Home home, boolean bypass) {
        String prefix = plugin.mistConfig().prefix();
        if (isRemoteHome(plugin, home)) {
            jumpToHomeServer(plugin, player, home);
            return;
        }
        if (isUnclaimedElsewhere(plugin, home)) {
            player.sendMessage(prefix + "§c家园归属服务器尚未登记（数据不在本机），请稍后再试");
            return;
        }
        plugin.teleportService().teleportToHome(player, home, bypass);
    }

    /**
     * 跨服回家：先异步写入 pending_actions（目标服 join 时消费），
     * 写入成功后主线程 Connect 跳服。家不加载到本服，不占本地槽位。
     */
    public static void jumpToHomeServer(MistHomePlugin plugin, Player player, Home home) {
        String prefix = plugin.mistConfig().prefix();
        player.sendMessage(prefix + "§7家园位于服务器 §b" + home.server() + "§7，正在跳转…");
        CompletableFuture.runAsync(() ->
                plugin.storage().setPendingAction(player.getUniqueId(), home.id()))
            .thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    plugin.bungee().connect(player, home.server());
                }
            }))
            .exceptionally(t -> {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(prefix + "§c跨服跳转失败：" + t.getMessage()));
                return null;
            });
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
        goHome(plugin, player, home, false);
        return true;
    }
}
