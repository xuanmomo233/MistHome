package dev.mist.home.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeTier;
import dev.mist.home.model.HomeVisibility;
import dev.mist.home.world.HomeRegion;

/**
 * 家园主菜单（/mh gui）。
 * 按钮统一走 performCommand 复用命令逻辑，避免双份实现。
 */
public class MainMenu extends Menu {

    public MainMenu(MistHomePlugin plugin, Player player, Home home) {
        init(54, "§bMistHome 家园菜单");
        var cfg = plugin.mistConfig();

        // 边框装饰
        ItemStackDeco(this);
        HomeTier tier = cfg.tier(home.tierLevel());
        HomeRegion region = plugin.homeService().regionOf(home).orElse(null);

        item(20, Items.of(Material.ENDER_PEARL, "§a回家",
                "§7传送到你的家园"), e ->
                ((Player) e.getWhoClicked()).performCommand("misthome home"));

        item(22, Items.of(Material.BEACON, "§b家园信息",
                "§7名称：§f" + home.name(),
                "§7档位：§f" + tier.name() + "（半径 " + tier.radius() + "）",
                "§7槽位：§f#" + home.slotIndex(),
                "§7可见性：§f" + (home.visibility() == HomeVisibility.PUBLIC ? "公开" : "私密")),
                null);

        item(24, Items.of(Material.PLAYER_HEAD, "§e成员管理",
                "§7查看/管理家园成员"), e ->
                ((Player) e.getWhoClicked()).performCommand("misthome members"));

        boolean isPublic = home.visibility() == HomeVisibility.PUBLIC;
        item(30, Items.of(isPublic ? Material.LIME_DYE : Material.GRAY_DYE,
                isPublic ? "§a当前公开 - 点击设为私密" : "§7当前私密 - 点击设为公开",
                "§7公开家园可被任何人参观"), e ->
                ((Player) e.getWhoClicked()).performCommand(
                        "misthome " + (isPublic ? "private" : "public")));

        item(32, Items.of(Material.ANVIL, "§6升级家园",
                "§7扩大家园可用范围"), e ->
                ((Player) e.getWhoClicked()).performCommand("misthome upgrade"));

        item(34, Items.of(Material.COMPASS, "§d公共家园列表",
                "§7参观其他玩家的家园"), e ->
                ((Player) e.getWhoClicked()).performCommand("misthome list"));

        item(40, Items.of(Material.RED_BED, "§c设置出生点",
                "§7在当前位置设置家园出生点"), e ->
                ((Player) e.getWhoClicked()).performCommand("misthome setspawn"));

        // 中心到中心的装饰信息（未停放时显示待机状态）
        deco(4, Items.of(Material.PAPER, region == null
                ? "§8家园待机中（回车后加载运行）"
                : "§8家园中心 (" + region.centerX() + ", " + region.centerZ() + ")"));
    }

    private static void ItemStackDeco(Menu menu) {
        ItemStack pane = Items.of(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) {
            menu.deco(i, pane);
            menu.deco(i + 45, pane);
        }
        for (int i = 9; i < 45; i += 9) {
            menu.deco(i, pane);
            menu.deco(i + 8, pane);
        }
    }
}
