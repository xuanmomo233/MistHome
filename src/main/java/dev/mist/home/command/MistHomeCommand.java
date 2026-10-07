package dev.mist.home.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.model.Home;
import dev.mist.home.model.HomeRole;
import dev.mist.home.model.HomeVisibility;
import dev.mist.home.world.HomeRegion;
import dev.mist.home.world.SlotAllocator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * /misthome 命令分发器。
 * <p>
 * 子命令实现分里程碑：M2 create/home/visit/setspawn；
 * M4 invite/accept/deny/members/ban/unban；M7 list/public/private/upgrade；
 * M8 admin。
 */
public class MistHomeCommand implements CommandExecutor, TabCompleter {

    private final MistHomePlugin plugin;
    /** 子命令名 -> 用法说明（help 用） */
    private final Map<String, String> subCommands = new LinkedHashMap<>();
    /** 主线程执行器（CompletableFuture 回调回主线程用） */
    private final Executor mainExecutor;

    public MistHomeCommand(MistHomePlugin plugin) {
        this.plugin = plugin;
        this.mainExecutor = r -> Bukkit.getScheduler().runTask(plugin, r);
        subCommands.put("create", "创建家园");
        subCommands.put("home", "回到自己的家园");
        subCommands.put("visit", "参观玩家家园");
        subCommands.put("invite", "邀请玩家参观/共建");
        subCommands.put("accept", "接受邀请");
        subCommands.put("deny", "拒绝邀请");
        subCommands.put("setspawn", "设置家园出生点");
        subCommands.put("public", "设为公开家园");
        subCommands.put("private", "设为私密家园");
        subCommands.put("members", "成员管理");
        subCommands.put("ban", "封禁玩家");
        subCommands.put("unban", "解除封禁");
        subCommands.put("upgrade", "升级家园档位");
        subCommands.put("list", "公共家园列表");
        subCommands.put("gui", "打开家园菜单");
        subCommands.put("admin", "管理员工具");
    }

    private void msg(Player player, String text) {
        player.sendMessage(plugin.mistConfig().prefix() + text);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("仅玩家可使用");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "create" -> cmdCreate(player, args);
            case "home" -> cmdHome(player);
            case "visit" -> cmdVisit(player, args);
            case "setspawn" -> cmdSetspawn(player);
            case "invite" -> cmdInvite(player, args);
            case "accept" -> cmdAccept(player);
            case "deny" -> cmdDeny(player);
            case "members" -> cmdMembers(player);
            case "ban" -> cmdBan(player, args);
            case "unban" -> cmdUnban(player, args);
            case "gui" -> cmdGui(player);
            case "public" -> cmdVisibility(player, HomeVisibility.PUBLIC);
            case "private" -> cmdVisibility(player, HomeVisibility.PRIVATE);
            case "upgrade" -> cmdUpgrade(player);
            case "list" -> cmdList(player);
            case "admin" -> cmdAdmin(player, args);
            default -> {
                msg(player, "§c未知子命令，输入 /mh 查看帮助");
            }
        }
        return true;
    }

    // ---------- M2 ----------

    /** /mh create [模板] —— 扣费 -> 分配槽位建家 -> 加载世界 -> 粘模板 -> 传送 */
    private void cmdCreate(Player player, String[] args) {
        if (!player.hasPermission("misthome.use")) {
            msg(player, "§c无权限");
            return;
        }
        String template = args.length > 1 ? args[1] : null;
        List<String> available = plugin.templates().listTemplates();
        if (template != null && !available.contains(template)) {
            msg(player, "§c模板不存在，可用模板：" + String.join("、", available));
            return;
        }
        if (template == null) {
            // 有可用模板时打开选择 GUI；否则直接用降级平台
            if (!available.isEmpty()) {
                new dev.mist.home.gui.TemplateMenu(plugin, available).open(player);
                return;
            }
        }
        String finalTemplate = template;

        plugin.homeService().homeOfAsync(player.getUniqueId())
                .thenAcceptAsync(existing -> {
                    if (existing.isPresent()) {
                        msg(player, "§c你已有家园，输入 /mh home 传送");
                        return;
                    }
                    double price = plugin.mistConfig().createPrice();
                    if (price > 0 && !plugin.economy().withdraw(player, price)) {
                        msg(player, "§c余额不足，创建家园需要 " + price);
                        return;
                    }
                    String name = player.getName() + "的家园";
                    plugin.homeService().createHomeAsync(player.getUniqueId(), name, finalTemplate)
                            .thenAcceptAsync(home -> finishCreate(player, home, finalTemplate, price),
                                    mainExecutor)
                            .exceptionally(t -> {
                                // 创建失败退款
                                if (price > 0) {
                                    plugin.economy().deposit(player, price);
                                }
                                Bukkit.getScheduler().runTask(plugin, () ->
                                        msg(player, "§c家园创建失败：" + t.getMessage()));
                                return null;
                            });
                }, mainExecutor);
    }

    /** 创建流程后半段：加载世界 -> 粘模板 -> 传送（均回调主线程） */
    private void finishCreate(Player player, Home home, String template, double price) {
        int worldIndex = SlotAllocator.worldIndexOf(home.slotIndex(),
                plugin.mistConfig().homesPerWorld());
        plugin.worldManager().ensureLoaded(worldIndex)
                .thenAccept(world -> {
                    HomeRegion region = plugin.homeService().regionOf(home);
                    plugin.templates().paste(template, world, region)
                            .thenRun(() -> {
                                plugin.teleportService().teleportToHome(player, home);
                                msg(player, "§a家园创建成功！"
                                        + (price > 0 ? "（花费 " + price + "）" : ""));
                            })
                            .exceptionally(t -> {
                                // 模板粘贴失败不阻塞传送，平台由下次 create 兜底
                                plugin.getLogger().warning("模板粘贴失败: " + t.getMessage());
                                plugin.teleportService().teleportToHome(player, home);
                                return null;
                            });
                })
                .exceptionally(t -> {
                    msg(player, "§c家园世界加载失败：" + t.getMessage());
                    return null;
                });
    }

    /** /mh home —— 吟唱后传送回自己家园 */
    private void cmdHome(Player player) {
        plugin.homeService().homeOfAsync(player.getUniqueId())
                .thenAcceptAsync(opt -> {
                    if (opt.isEmpty()) {
                        msg(player, "§c你还没有家园，输入 /mh create 创建");
                        return;
                    }
                    plugin.teleportService().teleportToHome(player, opt.get());
                }, mainExecutor);
    }

    /** /mh visit <玩家> —— 参观公开家园或受邀/成员家园 */
    private void cmdVisit(Player player, String[] args) {
        if (!player.hasPermission("misthome.visit")) {
            msg(player, "§c无权限");
            return;
        }
        if (args.length < 2) {
            msg(player, "§c用法：/mh visit <玩家>");
            return;
        }
        // 在线精确匹配优先；离线回退 getOfflinePlayer（Spigot 无 getOfflinePlayerIfCached）
        Player online = Bukkit.getPlayerExact(args[1]);
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[1]);
        plugin.homeService().homeOfAsync(target.getUniqueId())
                .thenAcceptAsync(opt -> {
                    if (opt.isEmpty()) {
                        msg(player, "§c对方没有家园");
                        return;
                    }
                    dev.mist.home.actions.HomeActions.visit(plugin, player, opt.get());
                }, mainExecutor);
    }

    /** /mh setspawn —— 在自己家园可用范围内设置出生点 */
    private void cmdSetspawn(Player player) {
        plugin.homeService().homeOfAsync(player.getUniqueId())
                .thenAcceptAsync(opt -> {
                    if (opt.isEmpty()) {
                        msg(player, "§c你还没有家园");
                        return;
                    }
                    Home home = opt.get();
                    HomeRegion region = plugin.homeService().regionOf(home);
                    var loc = player.getLocation();
                    if (!plugin.worldManager().isHomeWorld(loc.getWorld().getName())
                            || !region.containsUsable(loc.getX(), loc.getZ())) {
                        msg(player, "§c必须站在自己家园的可用范围内设置出生点");
                        return;
                    }
                    home.setSpawn(loc.getX(), loc.getY(), loc.getZ(),
                            loc.getYaw(), loc.getPitch());
                    plugin.homeService().storage().updateHome(home);
                    msg(player, "§a出生点已更新");
                }, mainExecutor);
    }

    // ---------- M4 成员/邀请/封禁 ----------

    /** /mh invite <玩家> —— OPERATOR+ 邀请玩家加入为 MEMBER */
    private void cmdInvite(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "§c用法：/mh invite <玩家>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg(player, "§c对方不在线");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            msg(player, "§c不能邀请自己");
            return;
        }
        plugin.homeService().homeOfAsync(player.getUniqueId())
                .thenAcceptAsync(opt -> {
                    if (opt.isEmpty()) {
                        msg(player, "§c你还没有家园");
                        return;
                    }
                    Home home = opt.get();
                    HomeRole role = plugin.homeService().roleOf(home, player.getUniqueId());
                    if (!role.canManageMembers()) {
                        msg(player, "§c需要家园管理员权限才能邀请");
                        return;
                    }
                    HomeRole targetRole = plugin.homeService()
                            .roleOf(home, target.getUniqueId());
                    if (targetRole.canBuild()) {
                        msg(player, "§c对方已是家园成员");
                        return;
                    }
                    if (targetRole == HomeRole.BANNED) {
                        msg(player, "§c对方已被封禁，请先 /mh unban");
                        return;
                    }
                    plugin.invites().invite(target.getUniqueId(), home.id(), player.getUniqueId());
                    msg(player, "§a已邀请 " + target.getName() + "，有效期 "
                            + plugin.mistConfig().inviteExpireSeconds() + " 秒");
                    target.sendMessage(plugin.mistConfig().prefix()
                            + "§b" + player.getName() + " 邀请你加入家园「" + home.name() + "」"
                            + "，输入 §f/mh accept §b接受，/mh deny §b拒绝");
                }, mainExecutor);
    }

    /** /mh accept —— 接受邀请成为 MEMBER */
    private void cmdAccept(Player player) {
        var homeId = plugin.invites().accept(player.getUniqueId());
        if (homeId.isEmpty()) {
            msg(player, "§c没有有效邀请");
            return;
        }
        CompletableFuture.supplyAsync(() -> plugin.homeService().storage()
                        .findHomeById(homeId.get()))
                .thenAcceptAsync(opt -> {
                    if (opt.isEmpty()) {
                        msg(player, "§c该家园已被删除");
                        return;
                    }
                    Home home = opt.get();
                    plugin.homeService().cache(home);
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                            plugin.homeService().storage()
                                    .setRole(home.id(), player.getUniqueId(), HomeRole.MEMBER));
                    plugin.homeService().cacheRole(home.id(), player.getUniqueId(), HomeRole.MEMBER);
                    msg(player, "§a已加入「" + home.name() + "」，输入 /mh visit "
                            + Bukkit.getOfflinePlayer(home.owner()).getName() + " 参观");
                }, mainExecutor);
    }

    /** /mh deny —— 拒绝邀请 */
    private void cmdDeny(Player player) {
        plugin.invites().deny(player.getUniqueId());
        msg(player, "§7已拒绝邀请");
    }

    /** /mh members —— 打开成员管理菜单 */
    private void cmdMembers(Player player) {
        plugin.homeService().homeOfAsync(player.getUniqueId())
                .thenAcceptAsync(opt -> {
                    if (opt.isEmpty()) {
                        msg(player, "§c你还没有家园");
                        return;
                    }
                    Home home = opt.get();
                    HomeRole role = plugin.homeService().roleOf(home, player.getUniqueId());
                    dev.mist.home.gui.MembersMenu.open(plugin, player, home,
                            role.canManageMembers());
                }, mainExecutor);
    }

    /** /mh ban <玩家> —— OPERATOR+ 封禁玩家 */
    private void cmdBan(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "§c用法：/mh ban <玩家>");
            return;
        }
        banAction(player, args[1], true);
    }

    /** /mh unban <玩家> —— 解除封禁 */
    private void cmdUnban(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "§c用法：/mh unban <玩家>");
            return;
        }
        banAction(player, args[1], false);
    }

    private void banAction(Player player, String targetName, boolean ban) {
        plugin.homeService().homeOfAsync(player.getUniqueId())
                .thenAcceptAsync(opt -> {
                    if (opt.isEmpty()) {
                        msg(player, "§c你还没有家园");
                        return;
                    }
                    Home home = opt.get();
                    HomeRole role = plugin.homeService().roleOf(home, player.getUniqueId());
                    if (!role.canBan()) {
                        msg(player, "§c需要家园管理员权限");
                        return;
                    }
                    Player online = Bukkit.getPlayerExact(targetName);
                    OfflinePlayer target = online != null ? online
                            : Bukkit.getOfflinePlayer(targetName);
                    if (home.owner().equals(target.getUniqueId())) {
                        msg(player, "§c不能对家园主操作");
                        return;
                    }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        if (ban) {
                            plugin.homeService().storage().ban(home.id(), target.getUniqueId());
                        } else {
                            plugin.homeService().storage().unban(home.id(), target.getUniqueId());
                        }
                        plugin.homeService().evictRole(home.id(), target.getUniqueId());
                    });
                    msg(player, ban ? "§a已封禁 " + targetName : "§a已解封 " + targetName);
                    if (ban && online != null) {
                        kickFromHome(online, home);
                    }
                }, mainExecutor);
    }

    /** 把被封禁玩家从家园区域送回主世界 */
    private void kickFromHome(Player target, Home home) {
        World world = target.getWorld();
        if (!plugin.worldManager().isHomeWorld(world.getName())) {
            return;
        }
        Optional<Home> at = plugin.homeService().homeAt(world,
                target.getLocation().getBlockX(), target.getLocation().getBlockZ());
        if (at.isPresent() && at.get().id() == home.id()) {
            World fallback = Bukkit.getWorlds().stream()
                    .filter(w -> !plugin.worldManager().isHomeWorld(w.getName()))
                    .findFirst().orElse(null);
            if (fallback != null) {
                target.teleport(fallback.getSpawnLocation());
            }
        }
    }

    /** /mh gui —— 打开家园主菜单 */
    private void cmdGui(Player player) {
        plugin.homeService().homeOfAsync(player.getUniqueId())
                .thenAcceptAsync(opt -> {
                    if (opt.isEmpty()) {
                        msg(player, "§c你还没有家园，输入 /mh create 创建");
                        return;
                    }
                    new dev.mist.home.gui.MainMenu(plugin, player, opt.get()).open(player);
                }, mainExecutor);
    }

    // ---------- M7 公开/升级/列表 ----------

    /** /mh public|private —— OPERATOR+ 切换可见性 */
    private void cmdVisibility(Player player, HomeVisibility target) {
        plugin.homeService().homeOfAsync(player.getUniqueId())
                .thenAcceptAsync(opt -> {
                    if (opt.isEmpty()) {
                        msg(player, "§c你还没有家园");
                        return;
                    }
                    Home home = opt.get();
                    HomeRole role = plugin.homeService().roleOf(home, player.getUniqueId());
                    if (!role.canManageSettings()) {
                        msg(player, "§c需要家园管理员权限");
                        return;
                    }
                    if (home.visibility() == target) {
                        msg(player, "§7家园已是" + (target == HomeVisibility.PUBLIC
                                ? "公开" : "私密") + "状态");
                        return;
                    }
                    home.setVisibility(target);
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                            plugin.homeService().storage().updateHome(home));
                    msg(player, "§a家园已设为"
                            + (target == HomeVisibility.PUBLIC ? "公开，任何人可参观" : "私密"));
                }, mainExecutor);
    }

    /** /mh upgrade —— OWNER 升级档位（扣 Vault） */
    private void cmdUpgrade(Player player) {
        plugin.homeService().homeOfAsync(player.getUniqueId())
                .thenAcceptAsync(opt -> {
                    if (opt.isEmpty()) {
                        msg(player, "§c你还没有家园");
                        return;
                    }
                    Home home = opt.get();
                    HomeRole role = plugin.homeService().roleOf(home, player.getUniqueId());
                    if (role != HomeRole.OWNER) {
                        msg(player, "§c只有家园主可以升级");
                        return;
                    }
                    var next = plugin.mistConfig().nextTier(home.tierLevel());
                    if (next == null) {
                        msg(player, "§7已是最高档位");
                        return;
                    }
                    double price = next.upgradePrice();
                    if (price > 0 && !plugin.economy().withdraw(player, price)) {
                        msg(player, "§c余额不足，升级到 " + next.name()
                                + " 需要 " + price);
                        return;
                    }
                    home.setTierLevel(next.level());
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () ->
                            plugin.homeService().storage().updateHome(home));
                    msg(player, "§a升级成功！" + next.name()
                            + "（半径 " + next.radius() + "）"
                            + (price > 0 ? "，花费 " + price : ""));
                }, mainExecutor);
    }

    /** /mh list —— 公共家园列表 */
    private void cmdList(Player player) {
        if (!player.hasPermission("misthome.visit")) {
            msg(player, "§c无权限");
            return;
        }
        dev.mist.home.gui.PublicHomesMenu.open(plugin, player, 0);
    }

    // ---------- M8 管理员工具 ----------

    /** /mh admin <reload|visit|unload|info> */
    private void cmdAdmin(Player player, String[] args) {
        if (!player.hasPermission("misthome.admin")) {
            msg(player, "§c无权限");
            return;
        }
        if (args.length < 2) {
            msg(player, "§c用法：/mh admin <reload|visit|unload|info>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "reload" -> adminReload(player);
            case "visit" -> adminVisit(player, args);
            case "unload" -> adminUnload(player, args);
            case "info" -> adminInfo(player);
            default -> msg(player, "§c未知管理子命令");
        }
    }

    private void adminReload(Player player) {
        try {
            plugin.reloadMistConfig();
            msg(player, "§a配置已重载并校验通过");
        } catch (IllegalArgumentException e) {
            msg(player, "§c配置校验失败：" + e.getMessage());
        }
    }

    private void adminVisit(Player player, String[] args) {
        if (args.length < 3) {
            msg(player, "§c用法：/mh admin visit <玩家>");
            return;
        }
        Player online = Bukkit.getPlayerExact(args[2]);
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(args[2]);
        plugin.homeService().homeOfAsync(target.getUniqueId())
                .thenAcceptAsync(opt -> {
                    if (opt.isEmpty()) {
                        msg(player, "§c对方没有家园");
                        return;
                    }
                    plugin.teleportService().teleportToHome(player, opt.get(), true);
                }, mainExecutor);
    }

    private void adminUnload(Player player, String[] args) {
        if (args.length < 3) {
            msg(player, "§c用法：/mh admin unload <worldIndex>");
            return;
        }
        int index;
        try {
            index = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            msg(player, "§cworldIndex 必须是数字");
            return;
        }
        if (plugin.worldManager().forceUnload(index)) {
            msg(player, "§a已触发卸载 misthome_" + index);
        } else {
            msg(player, "§c世界 misthome_" + index + " 未加载");
        }
    }

    private void adminInfo(Player player) {
        msg(player, "§7已加载家园世界：§f" + plugin.worldManager().loadedCount()
                + " §7| 存储：§f" + plugin.mistConfig().storageType()
                + " §7| 每世界槽位：§f" + plugin.mistConfig().homesPerWorld()
                + " §7| 槽位大小：§f" + plugin.mistConfig().slotSize());
    }

    private void sendHelp(Player player) {
        player.sendMessage("§b====== MistHome 家园 ======");
        for (Map.Entry<String, String> entry : subCommands.entrySet()) {
            player.sendMessage("§7/mh " + entry.getKey() + " §8- §f" + entry.getValue());
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String label,
                                                @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> result = new ArrayList<>();
            for (String name : subCommands.keySet()) {
                if (name.startsWith(prefix)) {
                    result.add(name.split(" ")[0]);
                }
            }
            return result;
        }
        return List.of();
    }
}
