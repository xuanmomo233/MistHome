package dev.mist.home.world;

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
}
