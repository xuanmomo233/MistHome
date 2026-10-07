package dev.mist.home.gui;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import dev.mist.home.MistHomePlugin;

import java.util.List;

/**
 * 模板选择菜单（/mh create 无参数且有可用模板时打开）。
 * 点击模板 → performCommand "misthome create <模板名>"。
 */
public class TemplateMenu extends Menu {

    private static final Material[] ICONS = {
            Material.GRASS_BLOCK, Material.OAK_SAPLING, Material.SANDSTONE,
            Material.SNOW_BLOCK, Material.NETHERRACK, Material.END_STONE,
            Material.PRISMARINE, Material.HONEY_BLOCK, Material.AMETHYST_BLOCK
    };

    public TemplateMenu(MistHomePlugin plugin, List<String> templates) {
        int rows = Math.min(6, (templates.size() + 8) / 9 + 1);
        init(rows * 9, "§b选择家园模板");
        ItemStack pane = Items.of(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = rows * 9 - 9; i < rows * 9; i++) {
            deco(i, pane);
        }
        for (int i = 0; i < templates.size() && i < 45; i++) {
            String name = templates.get(i);
            Material icon = ICONS[i % ICONS.length];
            item(i, Items.of(icon, "§a" + name,
                    "§7点击使用该模板创建家园"), e ->
                    ((Player) e.getWhoClicked()).performCommand("misthome create " + name));
        }
    }
}
