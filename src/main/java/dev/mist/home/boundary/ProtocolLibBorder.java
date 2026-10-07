package dev.mist.home.boundary;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import dev.mist.home.world.HomeRegion;

/**
 * ProtocolLib 委托类：全部 PL API 引用隔离在本类中。
 * 仅在确认 ProtocolLib 已安装后由 BorderService 实例化使用，
 * 避免主类加载时触发 CNFE。
 */
final class ProtocolLibBorder {

    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();

    /** 下发客户端世界边界：中心=家园中心，直径=可用半径*2 */
    void show(Player player, HomeRegion region) {
        PacketContainer pkt = protocol.createPacket(PacketType.Play.Server.INITIALIZE_BORDER);
        double diameter = Math.max(region.usableRadius() * 2.0, 1.0);
        // 字段序：newX/newZ/oldDiameter/newDiameter (doubles 0-3)
        pkt.getDoubles().write(0, (double) region.centerX());
        pkt.getDoubles().write(1, (double) region.centerZ());
        pkt.getDoubles().write(2, diameter);
        pkt.getDoubles().write(3, diameter);
        // speed (longs 0)：0 = 立即生效
        pkt.getLongs().write(0, 0L);
        // newAbsoluteMaxSize / warningTime / warningBlocks (ints 0-2)
        pkt.getIntegers().write(0, 29999984);
        pkt.getIntegers().write(1, 0);
        pkt.getIntegers().write(2, 0);
        protocol.sendServerPacket(player, pkt);
    }

    /** 还原为世界真实边界 */
    void reset(Player player, World world) {
        var wb = world.getWorldBorder();
        PacketContainer pkt = protocol.createPacket(PacketType.Play.Server.INITIALIZE_BORDER);
        pkt.getDoubles().write(0, wb.getCenter().getX());
        pkt.getDoubles().write(1, wb.getCenter().getZ());
        double size = wb.getSize();
        pkt.getDoubles().write(2, size);
        pkt.getDoubles().write(3, size);
        pkt.getLongs().write(0, 0L);
        // ints[0] = newAbsoluteMaxSize，必须给原版上限而非 warningDistance
        pkt.getIntegers().write(0, 29999984);
        pkt.getIntegers().write(1, wb.getWarningTime());
        pkt.getIntegers().write(2, wb.getWarningDistance());
        protocol.sendServerPacket(player, pkt);
    }
}
