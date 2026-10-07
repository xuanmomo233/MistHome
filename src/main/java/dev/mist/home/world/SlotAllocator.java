package dev.mist.home.world;

import java.util.OptionalInt;

/**
 * 槽位分配几何计算。
 * <p>
 * 全局槽位索引 slotIndex 递增分配：
 * <pre>
 *   worldIndex = slotIndex / homesPerWorld
 *   innerIndex = slotIndex % homesPerWorld
 *   cols       = floor(sqrt(homesPerWorld))
 *   gridX      = innerIndex % cols
 *   gridZ      = innerIndex / cols
 *   pitch      = slotSize + gap
 *   centerX    = (gridX - (cols - 1) / 2.0) * pitch   （网格整体以原点为中心对称铺开）
 * </pre>
 */
public final class SlotAllocator {

    private SlotAllocator() {
    }

    public static int worldIndexOf(int slotIndex, int homesPerWorld) {
        return slotIndex / homesPerWorld;
    }

    /**
     * 由槽位索引计算家园区域。
     *
     * @param usableRadius 当前档可用半径
     */
    public static HomeRegion regionOf(int slotIndex, int homesPerWorld,
                                      int slotSize, int gap, int usableRadius) {
        int worldIndex = worldIndexOf(slotIndex, homesPerWorld);
        int inner = slotIndex % homesPerWorld;
        int cols = (int) Math.floor(Math.sqrt(homesPerWorld));
        int gridX = inner % cols;
        int gridZ = inner / cols;
        int pitch = slotSize + gap;
        int centerX = (int) Math.round((gridX - (cols - 1) / 2.0) * pitch);
        int centerZ = (int) Math.round((gridZ - (cols - 1) / 2.0) * pitch);
        return new HomeRegion(worldIndex, gridX, gridZ, centerX, centerZ, slotSize / 2, usableRadius);
    }

    /**
     * 坐标反查：给定世界内坐标，计算其落在哪个槽位。
     * 落在槽位之间的 gap 隔离带上时返回 empty（无主之地）。
     *
     * @param worldIndex 世界索引（misthome_N 中的 N）
     */
    public static OptionalInt slotIndexAt(int worldIndex, int homesPerWorld,
                                          int slotSize, int gap, double x, double z) {
        int cols = (int) Math.floor(Math.sqrt(homesPerWorld));
        double pitch = slotSize + gap;
        // 与 regionOf 相反方向推回网格坐标
        int gridX = (int) Math.round(x / pitch + (cols - 1) / 2.0);
        int gridZ = (int) Math.round(z / pitch + (cols - 1) / 2.0);
        if (gridX < 0 || gridX >= cols || gridZ < 0 || gridZ >= cols) {
            return OptionalInt.empty();
        }
        int inner = gridZ * cols + gridX;
        if (inner >= homesPerWorld) {
            return OptionalInt.empty();
        }
        int slotIndex = worldIndex * homesPerWorld + inner;
        // 边界校验：坐标必须落在槽位半开区间 [min, max) 内，gap 带不算槽位
        HomeRegion probe = regionOf(slotIndex, homesPerWorld, slotSize, gap, 0);
        if (!probe.containsSlot(x, z)) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(slotIndex);
    }
}
