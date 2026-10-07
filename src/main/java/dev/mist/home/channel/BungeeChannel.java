package dev.mist.home.channel;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import dev.mist.home.MistHomePlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.logging.Level;

/**
 * BungeeCord 插件消息通道封装。
 * <p>
 * 两个用途：
 * <ul>
 *   <li>Connect：把玩家送到家园归属的子服（跳服访问语义）</li>
 *   <li>misthome 自定义子频道（Forward ALL 广播）：家园/角色数据变更后
 *       通知所有子服失效本地缓存，避免共享 MySQL 下的脏读</li>
 * </ul>
 * 未启用 cross-server 时所有发送均为 no-op。
 */
public final class BungeeChannel implements PluginMessageListener {

    private static final String BUNGEE = "BungeeCord";
    private static final String SUB = "misthome";
    private static final String ACT_INVALIDATE_HOME = "invalidate-home";

    private final MistHomePlugin plugin;

    public BungeeChannel(MistHomePlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, BUNGEE);
        messenger.registerIncomingPluginChannel(plugin, BUNGEE, this);
    }

    public void unregister() {
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterOutgoingPluginChannel(plugin, BUNGEE);
        messenger.unregisterIncomingPluginChannel(plugin, BUNGEE, this);
    }

    /** 把玩家 Connect 到指定子服（必须在主线程或至少玩家在线时调用） */
    public void connect(Player player, String server) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(server);
        player.sendPluginMessage(plugin, BUNGEE, out.toByteArray());
    }

    /**
     * 向所有子服广播家园缓存失效。
     * Forward 消息必须借助一个在线玩家发送；本服无人在线时跳过
     * （各服缓存本就为空或随后因未命中自动回填，可接受）。
     */
    public void broadcastInvalidate(long homeId) {
        if (!plugin.mistConfig().crossServerEnabled()) {
            return;
        }
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (carrier == null) {
            return;
        }
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(payloadBytes);
            payload.writeUTF(ACT_INVALIDATE_HOME);
            payload.writeLong(homeId);
            payload.flush();

            ByteArrayDataOutput msg = ByteStreams.newDataOutput();
            msg.writeUTF("Forward");
            msg.writeUTF("ALL");
            msg.writeUTF(SUB);
            msg.writeShort(payloadBytes.size());
            msg.write(payloadBytes.toByteArray());
            carrier.sendPluginMessage(plugin, BUNGEE, msg.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "跨服缓存失效广播失败 home=" + homeId, e);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!BUNGEE.equals(channel)) {
            return;
        }
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String sub = in.readUTF();
            if (!SUB.equals(sub)) {
                return;
            }
            // Forward 投递格式：UTF(自定义通道名) + short(长度) + payload bytes
            short len = in.readShort();
            byte[] data = new byte[len];
            in.readFully(data);
            try (DataInputStream din = new DataInputStream(new ByteArrayInputStream(data))) {
                String action = din.readUTF();
                if (ACT_INVALIDATE_HOME.equals(action)) {
                    plugin.homeService().evictById(din.readLong());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "解析跨服消息失败", e);
        }
    }
}
