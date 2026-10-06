package dev.mist.home.boundary;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import dev.mist.home.MistHomePlugin;
import dev.mist.home.world.HomeRegion;

/**
 * 家园边界可视化。
 * <p>
 * 优先 ProtocolLib 发送世界边界包（ClientboundInitializeBorder 等），
 * 未安装时降级为沿可用边界周期刷粒子线。
 */
public class BorderService {

    private final MistHomePlugin plugin;
    private boolean protocolLibAvailable;

    public BorderService(MistHomePlugin plugin) {
        this.plugin = plugin;
    }

    public void hook() {
        protocolLibAvailable = plugin.mistConfig().preferProtocolLib()
                && Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;
        if (protocolLibAvailable) {
            plugin.getLogger().info("已检测到 ProtocolLib，使用世界边界包显示家园范围");
        } else {
            plugin.getLogger().info("未检测到 ProtocolLib，边界降级为粒子显示");
        }
    }

    /**
     * 向玩家显示家园可用范围边界。
     * TODO: ProtocolLib 发包 / 粒子任务两种实现。
     */
    public void showBorder(Player player, HomeRegion region) {
        if (protocolLibAvailable) {
            sendWorldBorderPacket(player, region);
        } else {
            // TODO: 粒子边界任务（按 particle-interval-ticks 刷边界线）
        }
    }

    /** 清除玩家当前的边界显示 */
    public void clearBorder(Player player) {
        if (protocolLibAvailable) {
            // TODO: 发送 ClientboundInitializeBorder 重置为世界默认值
        }
    }

    private void sendWorldBorderPacket(Player player, HomeRegion region) {
        // TODO: ProtocolLibrary.getProtocolManager().sendServerPacket(...)
        //   中心 = region.centerX/Z，直径 = usableRadius*2
    }
}
