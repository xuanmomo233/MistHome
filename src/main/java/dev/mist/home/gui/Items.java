package dev.mist.home.gui;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Arrays;
import java.util.List;

/**
 * GUI 物品构建小工具。
 */
public final class Items {

    private Items() {
    }

    public static ItemStack of(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack of(Material material, String name, List<String> lore) {
        return of(material, name, lore.toArray(new String[0]));
    }

    /** 玩家头颅（离线可用，Mohist 上皮肤可能无法渲染，仅作占位） */
    public static ItemStack skull(OfflinePlayer player, String name, String... lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(player);
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack skull(OfflinePlayer player, String name, List<String> lore) {
        return skull(player, name, lore.toArray(new String[0]));
    }
}
