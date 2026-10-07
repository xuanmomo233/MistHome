package dev.mist.home.world;

/**
 * 家园区域几何信息（不可变值对象）。
 * <p>
 * 槽位（slot）为物理预留空间，边长 = slot-size；
 * 可用范围为以槽位中心为圆心、当前档 radius 为半径的正方形。
 * 相邻槽位之间另有 gap 间隔，保证区域互不接触。
 */
public class HomeRegion {

    private final int worldIndex;
    private final int gridX;
    private final int gridZ;
    private final int centerX;
    private final int centerZ;
    /** 槽位半径 = slot-size / 2 */
    private final int slotRadius;
    /** 当前档可用半径 */
    private final int usableRadius;

    public HomeRegion(int worldIndex, int gridX, int gridZ,
                      int centerX, int centerZ, int slotRadius, int usableRadius) {
        this.worldIndex = worldIndex;
        this.gridX = gridX;
        this.gridZ = gridZ;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.slotRadius = slotRadius;
        this.usableRadius = usableRadius;
    }

    public int worldIndex() { return worldIndex; }
    public int gridX() { return gridX; }
    public int gridZ() { return gridZ; }
    public int centerX() { return centerX; }
    public int centerZ() { return centerZ; }
    public int slotRadius() { return slotRadius; }
    public int usableRadius() { return usableRadius; }

    public int usableMinX() { return centerX - usableRadius; }
    public int usableMaxX() { return centerX + usableRadius; }
    public int usableMinZ() { return centerZ - usableRadius; }
    public int usableMaxZ() { return centerZ + usableRadius; }

    /**
     * 该坐标是否落在本家园的可用范围内。
     * 使用半开区间 [min, max)，保证相邻家园区域互不重叠。
     */
    public boolean containsUsable(double x, double z) {
        return x >= usableMinX() && x < usableMaxX()
                && z >= usableMinZ() && z < usableMaxZ();
    }

    /**
     * 该坐标是否落在本家园的预留槽位内（含未解锁区域）。
     * 半开区间，相邻槽位之间保持 gap 隔离。
     */
    public boolean containsSlot(double x, double z) {
        return x >= centerX - slotRadius && x < centerX + slotRadius
                && z >= centerZ - slotRadius && z < centerZ + slotRadius;
    }
}
