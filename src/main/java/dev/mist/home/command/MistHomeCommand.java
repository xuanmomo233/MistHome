package dev.mist.home.command;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
            case "invite", "accept", "deny", "members", "ban", "unban",
                    "public", "private", "upgrade", "list", "gui", "admin" ->
                    msg(player, "§7子命令 " + sub + " 将在后续里程碑实现");
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
            // 默认模板：优先 default，其次第一个，否则走降级平台
            template = available.contains("default") ? "default"
                    : (available.isEmpty() ? null : available.get(0));
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
                    Home home = opt.get();
                    HomeRole role = plugin.homeService().roleOf(home, player.getUniqueId());
                    if (!role.canVisit()) {
                        msg(player, "§c你已被该家园封禁");
                        return;
                    }
                    boolean allowed = home.visibility() == HomeVisibility.PUBLIC
                            || role.canBuild()   // MEMBER+
                            || home.owner().equals(player.getUniqueId());
                    if (!allowed) {
                        msg(player, "§c该家园未公开，需要邀请才能进入");
                        return;
                    }
                    plugin.teleportService().teleportToHome(player, home);
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
