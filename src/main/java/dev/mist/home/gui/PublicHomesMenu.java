package dev.mist.home.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.actions.HomeActions;
import dev.mist.home.model.Home;
import dev.mist.home.storage.Storage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 公共家园列表（/mh list）：分页展示公开家园，点击参观。
 */
public class PublicHomesMenu extends Menu {

    private static final int PAGE_SIZE = 45;

    private final MistHomePlugin plugin;
    private final int page;
    private final int totalPages;

    private PublicHomesMenu(MistHomePlugin plugin, int page, int totalPages, List<Home> homes) {
        this.plugin = plugin;
        this.page = page;
        this.totalPages = totalPages;
        init(54, "§b公共家园 - 第 " + (page + 1) + "/" + Math.max(1, totalPages) + " 页");
        render(homes);
    }

    public static void open(MistHomePlugin plugin, Player viewer, int page) {
        Storage storage = plugin.homeService().storage();
        int pageSize = plugin.mistConfig().publicPageSize();
        Executor main = r -> Bukkit.getScheduler().runTask(plugin, r);
        CompletableFuture.supplyAsync(() -> {
            int total = storage.countPublicHomes();
            int totalPages = (int) Math.ceil((double) total / pageSize);
            int safePage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
            List<Home> homes = storage.listPublicHomes(safePage * pageSize, pageSize);
            return new PageData(safePage, totalPages, homes);
        }).thenAcceptAsync(data ->
                new PublicHomesMenu(plugin, data.page, data.totalPages, data.homes)
                        .open(viewer), main);
    }

    private record PageData(int page, int totalPages, List<Home> homes) {
    }

    private void render(List<Home> homes) {
        for (int i = 0; i < homes.size() && i < PAGE_SIZE; i++) {
            Home home = homes.get(i);
            OfflinePlayer owner = Bukkit.getOfflinePlayer(home.owner());
            String ownerName = owner.getName() != null ? owner.getName() : "未知";
            var tier = plugin.mistConfig().tier(home.tierLevel());
            item(i, Items.skull(owner, "§f" + home.name(),
                    "§7主人：§f" + ownerName,
                    "§7档位：§f" + tier.name(),
                    "§e点击进入参观"), e ->
                    HomeActions.visit(plugin, (Player) e.getWhoClicked(), home));
        }

        // 底部导航
        if (page > 0) {
            item(45, Items.of(Material.ARROW, "§a上一页"), e ->
                    PublicHomesMenu.open(plugin, (Player) e.getWhoClicked(), page - 1));
        }
        deco(49, Items.of(Material.PAPER, "§7共 " + totalPages + " 页"));
        if (page < totalPages - 1) {
            item(53, Items.of(Material.ARROW, "§a下一页"), e ->
                    PublicHomesMenu.open(plugin, (Player) e.getWhoClicked(), page + 1));
        }
        item(48, Items.of(Material.OAK_DOOR, "§7返回主菜单"), e ->
                ((Player) e.getWhoClicked()).performCommand("misthome gui"));
    }
}
