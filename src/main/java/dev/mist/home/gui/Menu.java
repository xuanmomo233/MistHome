package dev.mist.home.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 原版箱子 GUI 基类。
 * <p>
 * 通过 InventoryHolder 反向路由：MenuListener 收到 InventoryClickEvent 后
 * 从 inventory.getHolder() 取回本 Menu 分发到槽位动作。
 * 所有点击默认取消（GUI 只读展示+按钮）。
 */
public abstract class Menu implements InventoryHolder {

    protected Inventory inventory;
    /** 槽位 -> 点击动作 */
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();

    protected void init(int size, String title) {
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    /** 放置按钮：slot 的物品 + 点击动作 */
    protected void item(int slot, ItemStack item, Consumer<InventoryClickEvent> action) {
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    /** 放置纯展示物品（不可点击） */
    protected void deco(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    /** 由 MenuListener 分发 */
    public void handleClick(InventoryClickEvent e) {
        e.setCancelled(true);
        Consumer<InventoryClickEvent> action = actions.get(e.getRawSlot());
        if (action != null) {
            action.accept(e);
        }
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
