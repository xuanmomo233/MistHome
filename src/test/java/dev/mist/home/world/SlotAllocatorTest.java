package dev.mist.home.world;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SlotAllocator region 对齐几何测试。
 * 参数：homesPerWorld=16（4x4）、slotSize=1024（2x2 region）。
 */
class SlotAllocatorTest {

    private static final int HPW = 16;
    private static final int SLOT = 1024;

    @Test
    void worldIndexAndInner() {
        assertEquals(0, SlotAllocator.worldIndexOf(0, HPW));
        assertEquals(0, SlotAllocator.worldIndexOf(15, HPW));
        assertEquals(1, SlotAllocator.worldIndexOf(16, HPW));
        assertEquals(2, SlotAllocator.worldIndexOf(47, HPW));
    }

    @Test
    void gridOffsetFromWorldSpawn() {
        // 网格从 +slotSize 起铺：origin = 1024，世界出生点 (0,0) 在所有槽位之外
        assertEquals(1024, SlotAllocator.gridOrigin(HPW, SLOT));
        // slot 0 = (gx0, gz0) 最小角 (1024, 1024)，中心 (1536, 1536)
        assertEquals(1024, SlotAllocator.slotMinX(0, HPW, SLOT));
        assertEquals(1024, SlotAllocator.slotMinZ(0, HPW, SLOT));
        HomeRegion r0 = SlotAllocator.regionOf(0, HPW, SLOT, 100);
        assertEquals(1536, r0.centerX());
        assertEquals(1536, r0.centerZ());
        // slot 15 = (gx3, gz3)，中心 (4608, 4608)
        HomeRegion r15 = SlotAllocator.regionOf(15, HPW, SLOT, 100);
        assertEquals(4608, r15.centerX());
        assertEquals(4608, r15.centerZ());
    }

    @Test
    void regionAlignment() {
        // 槽位最小角必是 512 的倍数，且独占 span^2 个 region
        for (int s = 0; s < HPW; s++) {
            assertEquals(0, SlotAllocator.slotMinX(s, HPW, SLOT) % 512);
            assertEquals(0, SlotAllocator.slotMinZ(s, HPW, SLOT) % 512);
        }
        assertEquals(2, SlotAllocator.regionSpan(SLOT));
        // slot0 基 region (2,2)，覆盖 r(2..3, 2..3)
        assertEquals(2, SlotAllocator.regionBaseX(0, HPW, SLOT));
        assertEquals(2, SlotAllocator.regionBaseZ(0, HPW, SLOT));
        // slot15 基 region (8,8)，覆盖 r(8..9, 8..9)
        assertEquals(8, SlotAllocator.regionBaseX(15, HPW, SLOT));
        assertEquals(8, SlotAllocator.regionBaseZ(15, HPW, SLOT));
    }

    @Test
    void slotIndexAtRoundTrip() {
        for (int s = 0; s < HPW; s++) {
            HomeRegion r = SlotAllocator.regionOf(s, HPW, SLOT, 0);
            OptionalInt found = SlotAllocator.slotIndexAt(
                    0, HPW, SLOT, r.centerX(), r.centerZ());
            assertTrue(found.isPresent());
            assertEquals(s, found.getAsInt());
        }
        // 跨世界
        assertEquals(16 + 5, SlotAllocator.slotIndexAt(
                1, HPW, SLOT, 1536 + 1024, 1536 + 1024).orElseThrow());
    }

    @Test
    void slotIndexAtBoundaryAndOutside() {
        // 槽位边界半开区间：min 属于本槽位，max 属于邻居
        assertEquals(1, SlotAllocator.slotIndexAt(0, HPW, SLOT, 2048, 1024)
                .orElseThrow());
        // 世界出生点不属于任何槽位
        assertTrue(SlotAllocator.slotIndexAt(0, HPW, SLOT, 0, 0).isEmpty());
        assertTrue(SlotAllocator.slotIndexAt(0, HPW, SLOT, 1023, 1024).isEmpty());
        assertTrue(SlotAllocator.slotIndexAt(0, HPW, SLOT, 5120, 1024).isEmpty());
    }

    @Test
    void neighborEdgeDistance() {
        // 相邻家园可用边缘距离 = slotSize - 2*radius；radius=336 -> 352
        HomeRegion a = SlotAllocator.regionOf(0, HPW, SLOT, 336);
        HomeRegion b = SlotAllocator.regionOf(1, HPW, SLOT, 336);
        assertEquals(1024 - 672, b.usableMinX() - a.usableMaxX());
    }
}
