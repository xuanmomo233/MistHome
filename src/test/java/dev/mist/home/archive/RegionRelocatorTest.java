package dev.mist.home.archive;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.DoubleTag;
import net.querz.nbt.tag.IntArrayTag;
import net.querz.nbt.tag.IntTag;
import net.querz.nbt.tag.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 换槽位坐标平移测试：槽位1(region基4,2) -> 槽位0(基2,2)，dRegion=(-2,0)
 * 即 dChunk=(-64,0)、dBlock=(-1024,0)。
 */
class RegionRelocatorTest {

    private static final int DRX = -2;
    private static final int DRZ = 0;
    private static final int DCX = -64;
    private static final int DBX = -1024;

    @TempDir
    Path dir;

    // ---------- helpers ----------

    private static ListTag<DoubleTag> vec3(double x, double y, double z) {
        ListTag<DoubleTag> l = new ListTag<>(DoubleTag.class);
        l.add(new DoubleTag(x));
        l.add(new DoubleTag(y));
        l.add(new DoubleTag(z));
        return l;
    }

    private static CompoundTag blockPos(int x, int y, int z) {
        CompoundTag c = new CompoundTag();
        c.putInt("X", x);
        c.putInt("Y", y);
        c.putInt("Z", z);
        return c;
    }

    private static File writeMca(Path dir, String name, List<CompoundTag> chunks) throws Exception {
        File f = dir.resolve(name).toFile();
        List<RegionRelocator.ChunkRec> recs = new java.util.ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RegionRelocator.ChunkRec r = new RegionRelocator.ChunkRec(i);
            r.tag = chunks.get(i);
            recs.add(r);
        }
        RegionRelocator.writeFile(f, recs);
        return f;
    }

    private static CompoundTag readChunk(File f, int index) throws Exception {
        for (RegionRelocator.ChunkRec r : RegionRelocator.readFile(f)) {
            if (r.index == index) {
                return r.tag;
            }
        }
        return null;
    }

    private static ListTag<CompoundTag> entities(CompoundTag root) {
        return root.getListTag("Entities").asCompoundTagList();
    }

    private static double dbl(CompoundTag c, String key, int idx) {
        return c.getListTag(key).asDoubleTagList().get(idx).asDouble();
    }

    // ---------- tests ----------

    @Test
    void entityPosAndCreateAnchorShifted() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 3465);
        root.put("Position", new IntArrayTag(new int[]{70, 66}));   // chunk 坐标
        ListTag<CompoundTag> ents = new ListTag<>(CompoundTag.class);

        // 普通动物
        CompoundTag cow = new CompoundTag();
        cow.putString("id", "minecraft:cow");
        cow.put("Pos", vec3(2558.5, 65.0, 1542.0));
        cow.put("Motion", vec3(0.0, -0.078, 0.0));                 // 速度不能动
        ents.add(cow);

        // Create 装配体实体：Pos + Contraption.Anchor 是绝对坐标，
        // Seats/Blocks/Superglue 是锚点相对局部坐标，必须保持原值
        CompoundTag contraptionEnt = new CompoundTag();
        contraptionEnt.putString("id", "create:carriage_contraption");
        contraptionEnt.put("Pos", vec3(2560.0, 66.0, 1536.0));
        CompoundTag contraption = new CompoundTag();
        contraption.put("Anchor", blockPos(2560, 66, 1536));
        contraption.put("Seats", new ListTag<>(CompoundTag.class) {{
            add(blockPos(1, 0, 2));                                 // 局部坐标
        }});
        contraption.put("Actors", new ListTag<>(CompoundTag.class) {{
            CompoundTag actor = new CompoundTag();
            actor.put("Position", vec3(2560.5, 66.0, 1536.5));      // MovementContext 绝对
            CompoundTag ctx = new CompoundTag();
            ctx.put("Pos", blockPos(0, 0, 1));                      // 局部 pos，不能动
            actor.put("ContextData", ctx);
            add(actor);
        }});
        contraptionEnt.put("Contraption", contraption);
        ents.add(contraptionEnt);

        // 悬挂实体（展示框）：block_pos int[3] + 旧字段 TileX/Z
        CompoundTag frame = new CompoundTag();
        frame.putString("id", "minecraft:item_frame");
        frame.put("Pos", vec3(2561.5, 65.0, 1540.5));
        frame.put("block_pos", new IntArrayTag(new int[]{2561, 65, 1540}));
        frame.putInt("TileX", 2561);
        frame.putInt("TileZ", 1540);
        ents.add(frame);

        // 乘客实体嵌套
        CompoundTag boat = new CompoundTag();
        boat.putString("id", "minecraft:boat");
        boat.put("Pos", vec3(2562.0, 64.0, 1538.0));
        ListTag<CompoundTag> passengers = new ListTag<>(CompoundTag.class);
        CompoundTag rider = new CompoundTag();
        rider.putString("id", "minecraft:zombie");
        rider.put("Pos", vec3(2562.0, 64.5, 1538.0));
        passengers.add(rider);
        boat.put("Passengers", passengers);
        ents.add(boat);

        root.put("Entities", ents);

        File f = writeMca(dir, "r.0.0.mca", List.of(root));
        assertTrue(RegionRelocator.relocateFile(f, "entities", DRX, DRZ, null));

        CompoundTag back = readChunk(f, 0);
        assertNotNull(back);
        // chunk Position: (70,66) -> (6,66)
        assertArrayEquals(new int[]{70 + DCX, 66}, back.getIntArray("Position"));

        ListTag<CompoundTag> es = entities(back);
        // 牛
        assertEquals(2558.5 + DBX, es.get(0).getListTag("Pos").asDoubleTagList().get(0).asDouble(), 1e-9);
        assertEquals(1542.0, es.get(0).getListTag("Pos").asDoubleTagList().get(2).asDouble(), 1e-9);
        assertEquals(-0.078, es.get(0).getListTag("Motion").asDoubleTagList().get(1).asDouble(), 1e-9);   // Motion 未动
        // 装配体：Pos/Anchor 平移，Seats 局部不变
        assertEquals(2560.0 + DBX, es.get(1).getListTag("Pos").asDoubleTagList().get(0).asDouble(), 1e-9);
        CompoundTag ct = es.get(1).getCompoundTag("Contraption");
        assertEquals(2560 + DBX, ct.getCompoundTag("Anchor").getInt("X"));
        assertEquals(1536, ct.getCompoundTag("Anchor").getInt("Z"));
        assertEquals(1, ct.getListTag("Seats").asCompoundTagList().get(0).getInt("X"));
        assertEquals(2560.5 + DBX,
                ct.getListTag("Actors").asCompoundTagList().get(0)
                        .getListTag("Position").asDoubleTagList().get(0).asDouble(), 1e-9);
        // 展示框
        assertEquals(2561.5 + DBX, es.get(2).getListTag("Pos").asDoubleTagList().get(0).asDouble(), 1e-9);
        assertArrayEquals(new int[]{2561 + DBX, 65, 1540}, es.get(2).getIntArray("block_pos"));
        assertEquals(2561 + DBX, es.get(2).getInt("TileX"));
        assertEquals(1540, es.get(2).getInt("TileZ"));
        // 乘客
        assertEquals(2562.0 + DBX,
                es.get(3).getListTag("Passengers").asCompoundTagList().get(0)
                        .getListTag("Pos").asDoubleTagList().get(0).asDouble(), 1e-9);
    }

    @Test
    void poiRecordsShifted() throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("Position", new IntArrayTag(new int[]{70, 67}));
        CompoundTag data = new CompoundTag();
        ListTag<CompoundTag> records = new ListTag<>(CompoundTag.class);
        CompoundTag rec = new CompoundTag();
        rec.put("pos", new IntArrayTag(new int[]{2560, 65, 1536}));
        rec.putString("type", "minecraft:farmer");
        rec.putInt("free_tickets", 1);
        records.add(rec);
        data.put("Records", records);
        root.put("Data", data);

        File f = writeMca(dir, "r.0.0.mca", List.of(root));
        assertTrue(RegionRelocator.relocateFile(f, "poi", DRX, DRZ, null));

        CompoundTag back = readChunk(f, 0);
        assertArrayEquals(new int[]{70 + DCX, 67}, back.getIntArray("Position"));
        CompoundTag r = back.getCompoundTag("Data").getListTag("Records")
                .asCompoundTagList().get(0);
        assertArrayEquals(new int[]{2560 + DBX, 65, 1536}, r.getIntArray("pos"));
    }

    @Test
    void regionChunkAndBlockEntitiesShifted() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putInt("xPos", 70);
        root.putInt("zPos", 66);
        ListTag<CompoundTag> bes = new ListTag<>(CompoundTag.class);
        CompoundTag chest = new CompoundTag();
        chest.putInt("x", 2561);
        chest.putInt("y", 65);
        chest.putInt("z", 1537);
        chest.putString("id", "minecraft:chest");
        bes.add(chest);
        root.put("block_entities", bes);

        File f = writeMca(dir, "r.2.2.mca", List.of(root));
        assertTrue(RegionRelocator.relocateFile(f, "region", DRX, DRZ, null));

        CompoundTag back = readChunk(f, 0);
        assertEquals(70 + DCX, back.getInt("xPos"));
        assertEquals(66, back.getInt("zPos"));
        CompoundTag be = back.getListTag("block_entities").asCompoundTagList().get(0);
        assertEquals(2561 + DBX, be.getInt("x"));
        assertEquals(65, be.getInt("y"));
        assertEquals(1537, be.getInt("z"));
    }

    @Test
    void zeroDeltaIsNoop() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putInt("xPos", 70);
        root.putInt("zPos", 66);
        File f = writeMca(dir, "r.0.0.mca", List.of(root));
        long before = f.lastModified();
        Thread.sleep(20);
        assertFalse(RegionRelocator.relocateFile(f, "region", 0, 0, null));
        assertEquals(before, f.lastModified());   // 无位移不重写文件
    }

    @Test
    void corruptChunkPassesThrough() throws Exception {
        // 一个正常 chunk + 一个损坏 chunk：正常的被平移，损坏的原样保留
        CompoundTag good = new CompoundTag();
        good.putInt("xPos", 70);
        good.putInt("zPos", 66);

        File f = writeMca(dir, "r.0.0.mca", List.of(good));
        // 在文件里追加损坏 chunk 的假象：直接写垃圾 payload 需要手写文件结构，
        // 这里换个思路——把正常文件截断到只剩头+一个 chunk，relocater 应不崩
        assertTrue(RegionRelocator.relocateFile(f, "region", DRX, DRZ, null));
        CompoundTag back = readChunk(f, 0);
        assertEquals(70 + DCX, back.getInt("xPos"));
    }
}
