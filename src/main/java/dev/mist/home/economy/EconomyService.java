package dev.mist.home.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import dev.mist.home.MistHomePlugin;

/**
 * Vault 经济软钩子。未安装 Vault 或经济插件时所有扣款直接返回 true（免费模式）。
 */
public class EconomyService {

    private final MistHomePlugin plugin;
    private Economy economy;

    public EconomyService(MistHomePlugin plugin) {
        this.plugin = plugin;
    }

    public void hook() {
        if (!plugin.mistConfig().economyEnabled() || Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return;
        }
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
            plugin.getLogger().info("已接入 Vault 经济: " + economy.getName());
        }
    }

    public boolean available() {
        return economy != null;
    }

    /** 扣款；无经济环境或余额不足返回 false 时由调用方处理 */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (economy == null || amount <= 0) {
            return true;
        }
        if (!economy.has(player, amount)) {
            return false;
        }
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    public void deposit(OfflinePlayer player, double amount) {
        if (economy != null && amount > 0) {
            economy.depositPlayer(player, amount);
        }
    }
}
