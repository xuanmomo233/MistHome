package dev.mist.home.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.home.HomeService;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.storage.Storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 成员管理菜单：列出成员与角色。
 * 左键循环 MEMBER <-> OPERATOR；右键移出成员。
 * 仅 OPERATOR+ 可操作，普通成员进入为只读。
 */
public class MembersMenu extends Menu {

    private static final int MAX_MEMBER_SLOTS = 45;

    private final MistHomePlugin plugin;
    private final Home home;
    private final boolean canManage;

    private MembersMenu(MistHomePlugin plugin, Home home, boolean canManage,
                        Map<UUID, HomeRole> members) {
        this.plugin = plugin;
        this.home = home;
        this.canManage = canManage;
        init(54, "§b成员管理 - " + home.name());
        render(members);
    }

    /** 异步加载成员后打开 */
    public static void open(MistHomePlugin plugin, Player viewer, Home home, boolean canManage) {
        HomeService hs = plugin.homeService();
        Storage storage = hs.storage();
        Executor main = r -> Bukkit.getScheduler().runTask(plugin, r);
        CompletableFuture.supplyAsync(() -> {
            List<UUID> uuids = storage.listMembers(home.id());
            Map<UUID, HomeRole> roles = new LinkedHashMap<>();
            for (UUID u : uuids) {
                roles.put(u, storage.roleOf(home.id(), u));
            }
            return roles;
        }).thenAcceptAsync(members ->
                new MembersMenu(plugin, home, canManage, members).open(viewer), main);
    }

    private void render(Map<UUID, HomeRole> members) {
        int slot = 0;
        for (Map.Entry<UUID, HomeRole> entry : members.entrySet()) {
            if (slot >= MAX_MEMBER_SLOTS) {
                break;
            }
            UUID memberId = entry.getKey();
            HomeRole role = entry.getValue();
            OfflinePlayer member = Bukkit.getOfflinePlayer(memberId);
            String name = member.getName() != null ? member.getName() : memberId.toString().substring(0, 8);

            List<String> lore = new ArrayList<>();
            lore.add("§7角色：§f" + role.name());
            if (canManage) {
                lore.add("");
                lore.add("§e左键 §7切换 MEMBER/OPERATOR");
                lore.add("§c右键 §7移出家园");
            }
            item(slot, Items.skull(member, "§f" + name, lore), e -> {
                if (!canManage) {
                    return;
                }
                Player viewer = (Player) e.getWhoClicked();
                if (e.getClick() == ClickType.RIGHT) {
                    removeMember(memberId).thenRun(() -> refresh(viewer));
                } else {
                    cycleRole(memberId, role).thenRun(() -> refresh(viewer));
                }
            });
            slot++;
        }
        item(49, Items.of(Material.ARROW, "§7返回主菜单"), e ->
                ((Player) e.getWhoClicked()).performCommand("misthome gui"));
    }

    /** 异步写完成后重开菜单刷新成员列表 */
    private void refresh(Player viewer) {
        Bukkit.getScheduler().runTask(plugin,
                () -> MembersMenu.open(plugin, viewer, home, canManage));
    }

    private CompletableFuture<Void> cycleRole(UUID memberId, HomeRole current) {
        HomeRole next = current == HomeRole.MEMBER ? HomeRole.OPERATOR : HomeRole.MEMBER;
        return CompletableFuture.runAsync(() -> {
            plugin.homeService().storage().setRole(home.id(), memberId, next);
            plugin.homeService().cacheRole(home.id(), memberId, next);
        });
    }

    private CompletableFuture<Void> removeMember(UUID memberId) {
        return CompletableFuture.runAsync(() -> {
            plugin.homeService().storage().removeMember(home.id(), memberId);
            plugin.homeService().evictRole(home.id(), memberId);
        });
    }
}
