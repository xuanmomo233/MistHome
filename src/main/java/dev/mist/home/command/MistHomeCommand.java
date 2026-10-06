package dev.mist.home.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import dev.mist.home.MistHomePlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /misthome 命令分发器。
 * <p>
 * 子命令规划（详见 DESIGN.md）：
 * create / home / visit / invite / accept / deny / setspawn /
 * public / private / members / ban / unban / upgrade / list / gui / admin
 */
public class MistHomeCommand implements CommandExecutor, TabCompleter {

    private final MistHomePlugin plugin;
    /** 子命令名 -> 用法说明（help 用） */
    private final Map<String, String> subCommands = new LinkedHashMap<>();

    public MistHomeCommand(MistHomePlugin plugin) {
        this.plugin = plugin;
        subCommands.put("create", "创建家园");
        subCommands.put("home", "回到自己的家园");
        subCommands.put("visit <玩家>", "参观玩家家园");
        subCommands.put("invite <玩家>", "邀请玩家参观/共建");
        subCommands.put("accept", "接受邀请");
        subCommands.put("deny", "拒绝邀请");
        subCommands.put("setspawn", "设置家园出生点");
        subCommands.put("public", "设为公开家园");
        subCommands.put("private", "设为私密家园");
        subCommands.put("members", "成员管理");
        subCommands.put("ban <玩家>", "封禁玩家");
        subCommands.put("unban <玩家>", "解除封禁");
        subCommands.put("upgrade", "升级家园档位");
        subCommands.put("list", "公共家园列表");
        subCommands.put("gui", "打开家园菜单");
        subCommands.put("admin", "管理员工具");
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
        // TODO: 各子命令实现（参考 DESIGN.md 命令表）
        player.sendMessage(plugin.mistConfig().prefix() + "§7子命令 "
                + args[0] + " 尚未实现（项目骨架阶段）");
        return true;
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
