package dev.mist.home.world;

import java.util.OptionalInt;

/**
 * 槽位分配几何计算（region 文件对齐版）。
 * <p>
 * 槽位边长 slotSize 必须是 512 的倍数（对齐 .mca region 文件边界），
 * 槽位之间紧密排列（无 gap，隔离靠槽内留白 margin 实现）。
 * <pre>
 *   worldIndex = slotIndex / homesPerWorld
 *   innerIndex = slotIndex % homesPerWorld
 *   cols       = floor(sqrt(homesPerWorld))
 *   gridX      = innerIndex % cols
 *   gridZ      = innerIndex / cols
 *   gridOrigin = slotSize                        （网格整体离开世界出生点区域）
 *   slotMinX   = gridOrigin + gridX * slotSize
 *   centerX    = slotMinX + slotSize/2
 *   regionBase = slotMin / 512                    （槽位占 (slotSize/512)^2 个 region）
 * </pre>
 * 每个槽位独占整数个 region 文件：region/entities/poi 下
 * r.rx.rz.mca（rx ∈ [regionBase, regionBase+span)）。
 */
public final class SlotAllocator {

    /** 一个 region 文件的边长（格） */
    public static final int REGION_SIZE = 512;

    private SlotAllocator() {
    }

    public static int worldIndexOf(int slotIndex, int homesPerWorld) {
        return slotIndex / homesPerWorld;
    }

    /** 每世界网格列数（homesPerWorld 需为完全平方数） */
    public static int cols(int homesPerWorld) {
        return (int) Math.floor(Math.sqrt(homesPerWorld));
    }

    /** 槽位在网格内的位置 */
    public static int gridX(int slotIndex, int homesPerWorld) {
        int inner = slotIndex % homesPerWorld;
        return inner % cols(homesPerWorld);
    }

    public static int gridZ(int slotIndex, int homesPerWorld) {
        int inner = slotIndex % homesPerWorld;
        return inner / cols(homesPerWorld);
    }

    /**
     * 网格原点：从 +slotSize 起向正坐标铺开。
     * 世界出生点 (0,0) 的预生成 region 文件必须落在所有槽位之外——
     * 若网格包含原点，出生点区块会写进多个槽位的 region，
     * slotFilesExist 磁盘检查会把这些槽位误判为"已被写过"。
     */
    public static int gridOrigin(int homesPerWorld, int slotSize) {
        return slotSize;
    }

    /** 槽位最小方块角 */
    public static int slotMinX(int slotIndex, int homesPerWorld, int slotSize) {
        return gridOrigin(homesPerWorld, slotSize) + gridX(slotIndex, homesPerWorld) * slotSize;
    }

    public static int slotMinZ(int slotIndex, int homesPerWorld, int slotSize) {
        return gridOrigin(homesPerWorld, slotSize) + gridZ(slotIndex, homesPerWorld) * slotSize;
    }

    /**
     * 由槽位索引计算家园区域。
     *
     * @param usableRadius 当前档可用半径
     */
    public static HomeRegion regionOf(int slotIndex, int homesPerWorld,
                                      int slotSize, int usableRadius) {
        int worldIndex = worldIndexOf(slotIndex, homesPerWorld);
        int centerX = slotMinX(slotIndex, homesPerWorld, slotSize) + slotSize / 2;
        int centerZ = slotMinZ(slotIndex, homesPerWorld, slotSize) + slotSize / 2;
        return new HomeRegion(worldIndex, gridX(slotIndex, homesPerWorld),
                gridZ(slotIndex, homesPerWorld), centerX, centerZ,
                slotSize / 2, usableRadius);
    }

    /**
     * 槽位占用的 region 文件基坐标（含 span 个 region）。
     * region/entities/poi 三个目录下同名文件均为本家园数据。
     */
    public static int regionBaseX(int slotIndex, int homesPerWorld, int slotSize) {
        return slotMinX(slotIndex, homesPerWorld, slotSize) / REGION_SIZE;
    }

    public static int regionBaseZ(int slotIndex, int homesPerWorld, int slotSize) {
        return slotMinZ(slotIndex, homesPerWorld, slotSize) / REGION_SIZE;
    }

    /** 槽位跨度（region 数/轴），slotSize=1024 -> 2 */
    public static int regionSpan(int slotSize) {
        return slotSize / REGION_SIZE;
    }

    /**
     * 坐标反查：给定世界内坐标，计算其落在哪个槽位。
     * 无 gap 概念，落在网格内必属于某槽位；超出网格返回 empty。
     *
     * @param worldIndex 世界索引（misthome_N 中的 N）
     */
    public static OptionalInt slotIndexAt(int worldIndex, int homesPerWorld,
                                          int slotSize, double x, double z) {
        int cols = cols(homesPerWorld);
        int origin = gridOrigin(homesPerWorld, slotSize);
        int gx = (int) Math.floor((x - origin) / slotSize);
        int gz = (int) Math.floor((z - origin) / slotSize);
        if (gx < 0 || gx >= cols || gz < 0 || gz >= cols) {
            return OptionalInt.empty();
        }
        int inner = gz * cols + gx;
        if (inner >= homesPerWorld) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(worldIndex * homesPerWorld + inner);
    }
}
