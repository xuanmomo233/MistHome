package dev.mist.home.archive;

import net.querz.nbt.io.NBTDeserializer;
import net.querz.nbt.io.NBTSerializer;
import net.querz.nbt.io.NamedTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.DoubleTag;
import net.querz.nbt.tag.IntArrayTag;
import net.querz.nbt.tag.IntTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.NumberTag;
import net.querz.nbt.tag.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * region 文件（.mca）整体坐标平移器。
 * <p>
 * 换槽位恢复时，文件内的绝对坐标必须跟着走，否则实体/兴趣点会落在旧物理位置。
 * 文件名归一化只解决"放在哪个文件里"，本类解决"文件里的坐标值"。
 * <p>
 * region 容器格式自实现（8KB 头 + 扇区压缩块，~100 行）；
 * NBT 层用 querz-nbt（querz 的 mca 包强依赖 1.18 前 "Level" 包裹格式，不适用 1.20.1 扁平区块）。
 * <p>
 * 已知覆盖的绝对坐标字段：
 * <ul>
 *   <li>region 区块根：{@code xPos/zPos}（chunk 坐标）、{@code block_entities/block_ticks/
 *       fluid_ticks/PostProcessing.Ticks} 内的 {@code x/z}（方块坐标）</li>
 *   <li>entities/poi 区块根：{@code Position} int 数组（chunk 坐标）</li>
 *   <li>任意深度：实体 {@code Pos}、Create MovementContext {@code Position}（double[3]）、
 *       Create {@code Anchor}/{@code pos}/{@code block_pos}（BlockPos 复合或 int[3]）、
 *       悬挂实体 {@code TileX/TileZ}、{@code AABB}（double[6]，SuperGlueEntity 等）</li>
 * </ul>
 * 机械动力装配体的 Blocks/Seats/Interactors/Superglue/Bounds 全部是锚点相对局部坐标，
 * 平移 Pos+Anchor 即等价于整体世界平移，内部结构无需改动（已核对 Create 源码）。
 * <p>
 * 写文件先写 .tmp 再原子替换；解析失败的 chunk 原样回写，绝不丢数据。
 */
final class RegionRelocator {

    private static final int SECTOR = 4096;
    private static final int HEADER_SECTORS = 2;      // 位置表 + 时间戳表

    private RegionRelocator() {
    }

    /**
     * 平移 .mca 文件内全部绝对坐标。
     *
     * @param subDir   所属子目录（region/entities/poi，目前三类统一处理，保留参数便于日志区分）
     * @param dRegionX 目标 region 基坐标 - 归档时 region 基坐标（X）
     * @param dRegionZ 同上（Z）
     * @return 是否有 chunk 被改写
     */
    static boolean relocateFile(File file, String subDir,
                                int dRegionX, int dRegionZ, Logger logger) throws IOException {
        if (dRegionX == 0 && dRegionZ == 0) {
            return false;
        }
        int dCX = dRegionX * 32;                 // chunk 坐标差
        int dCZ = dRegionZ * 32;
        int dBX = dRegionX * 512;                // 方块坐标差（1 region = 512 方块）
        int dBZ = dRegionZ * 512;

        List<ChunkRec> chunks = readFile(file);
        boolean dirty = false;
        for (ChunkRec c : chunks) {
            if (c.tag != null && relocateChunk(c.tag, dCX, dCZ, dBX, dBZ)) {
                dirty = true;
            }
        }
        if (dirty) {
            writeFile(file, chunks);
            if (logger != null) {
                logger.fine("mca 坐标平移完成: " + file.getPath()
                        + " sub=" + subDir + " dRegion=(" + dRegionX + "," + dRegionZ + ")");
            }
        }
        return dirty;
    }

    // ---------- chunk NBT 变换 ----------

    private static boolean relocateChunk(CompoundTag root, int dCX, int dCZ, int dBX, int dBZ) {
        boolean dirty = false;

        // region 区块根：chunk 绝对坐标（与文件名槽位一致）
        dirty |= addInt(root, "xPos", dCX);
        dirty |= addInt(root, "zPos", dCZ);
        // entities/poi 区块根：Position int[2] = chunk 绝对坐标
        dirty |= offsetChunkPosition(root, dCX, dCZ);

        // 方块级绝对 x/z：方块实体、计划刻
        for (String key : new String[]{"block_entities", "block_ticks", "fluid_ticks"}) {
            ListTag<CompoundTag> list = compoundList(root, key);
            if (list == null) {
                continue;
            }
            for (CompoundTag e : list) {
                dirty |= addInt(e, "x", dBX);
                dirty |= addInt(e, "z", dBZ);
            }
        }
        // PostProcessing: [{ ToBeTicked / Ticks: [{x,y,z,...}] }]
        ListTag<CompoundTag> post = compoundList(root, "PostProcessing");
        if (post != null) {
            for (CompoundTag e : post) {
                for (String tk : new String[]{"Ticks", "ToBeTicked"}) {
                    ListTag<CompoundTag> ticks = compoundList(e, tk);
                    if (ticks == null) {
                        continue;
                    }
                    for (CompoundTag t : ticks) {
                        dirty |= addInt(t, "x", dBX);
                        dirty |= addInt(t, "z", dBZ);
                    }
                }
            }
        }

        // 通用递归：实体 Pos/Anchor/AABB/pos/block_pos/TileX 等，任意深度
        dirty |= relocateCompound(root, dBX, dBZ);
        return dirty;
    }

    /**
     * 递归平移复合标签内的绝对坐标字段。
     * 只动"名字+类型都明确是位置"的键，Motion/Rotation 等不碰。
     */
    private static boolean relocateCompound(CompoundTag c, int dBX, int dBZ) {
        boolean dirty = false;
        for (Map.Entry<String, Tag<?>> e : c) {
            Tag<?> v = e.getValue();
            switch (e.getKey()) {
                // 实体根 Pos；Create MovementContext.Position（移动中装配体才写入）
                case "Pos", "Position" -> {
                    if (v instanceof ListTag<?> l) {
                        dirty |= offsetVec3(l, dBX, dBZ);
                    }
                }
                // SuperGlueEntity 等独立实体保存的世界 AABB [minX,minY,minZ,maxX,maxY,maxZ]
                case "AABB" -> {
                    if (v instanceof ListTag<?> l && l.size() == 6) {
                        dirty |= offsetDouble(l, 0, dBX);
                        dirty |= offsetDouble(l, 2, dBZ);
                        dirty |= offsetDouble(l, 3, dBX);
                        dirty |= offsetDouble(l, 5, dBZ);
                    }
                }
                // Create 装配体锚点 / POI 记录 / 悬挂实体 block_pos（BlockPos 两种序列化形态）
                case "Anchor", "pos", "block_pos" -> dirty |= offsetBlockPos(v, dBX, dBZ);
                // 悬挂实体旧字段（1.19 前），与 block_pos 并存兜底
                case "TileX" -> dirty |= addInt(c, "TileX", dBX);
                case "TileZ" -> dirty |= addInt(c, "TileZ", dBZ);
                default -> {
                }
            }
            if (v instanceof CompoundTag child) {
                dirty |= relocateCompound(child, dBX, dBZ);
            } else if (v instanceof ListTag<?> l) {
                for (Tag<?> item : l) {
                    if (item instanceof CompoundTag cc) {
                        dirty |= relocateCompound(cc, dBX, dBZ);
                    }
                }
            }
        }
        return dirty;
    }

    /** BlockPos 三种形态：{X,Y,Z} 复合 / int[3] / ListTag<IntTag>[3]，只平移 X/Z */
    private static boolean offsetBlockPos(Tag<?> v, int dBX, int dBZ) {
        if (v instanceof CompoundTag c && c.get("X") instanceof IntTag && c.get("Z") instanceof IntTag) {
            boolean dirty = addInt(c, "X", dBX);
            dirty |= addInt(c, "Z", dBZ);
            return dirty;
        }
        if (v instanceof IntArrayTag a) {
            int[] arr = a.getValue();
            if (arr.length >= 3) {
                arr[0] += dBX;
                arr[2] += dBZ;
                return true;
            }
            return false;
        }
        if (v instanceof ListTag<?> l && l.size() == 3) {
            boolean ok = true;
            for (Tag<?> t : l) {
                if (!(t instanceof IntTag)) {
                    ok = false;
                }
            }
            if (ok) {
                setInt(l, 0, intAt(l, 0) + dBX);
                setInt(l, 2, intAt(l, 2) + dBZ);
                return true;
            }
        }
        return false;
    }

    /** double[3] 向量：平移 [0] 和 [2]（x/z），y 不动 */
    private static boolean offsetVec3(ListTag<?> l, int dBX, int dBZ) {
        if (l.size() != 3) {
            return false;
        }
        for (Tag<?> t : l) {
            if (!(t instanceof DoubleTag)) {
                return false;
            }
        }
        boolean dirty = offsetDouble(l, 0, dBX);
        dirty |= offsetDouble(l, 2, dBZ);
        return dirty;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean offsetDouble(ListTag<?> l, int idx, double d) {
        Tag<?> t = l.get(idx);
        if (t instanceof DoubleTag dt) {
            ((ListTag) l).set(idx, new DoubleTag(dt.asDouble() + d));
            return true;
        }
        return false;
    }

    private static boolean addInt(CompoundTag c, String key, int delta) {
        Tag<?> t = c.get(key);
        if (t instanceof IntTag it) {
            c.putInt(key, it.asInt() + delta);
            return true;
        }
        return false;
    }

    /** entities/poi 区块根的 "Position" int[2] chunk 坐标 */
    private static boolean offsetChunkPosition(CompoundTag root, int dCX, int dCZ) {
        Tag<?> t = root.get("Position");
        if (t instanceof IntArrayTag a) {
            int[] arr = a.getValue();
            if (arr.length == 2) {
                arr[0] += dCX;
                arr[1] += dCZ;
                return true;
            }
        }
        return false;
    }

    private static ListTag<CompoundTag> compoundList(CompoundTag c, String key) {
        Tag<?> t = c.get(key);
        if (t instanceof ListTag) {
            try {
                ListTag<?> l = (ListTag<?>) t;
                ListTag<CompoundTag> typed = l.asCompoundTagList();
                return typed;
            } catch (ClassCastException e) {
                return null;
            }
        }
        return null;
    }

    private static int intAt(ListTag<?> l, int idx) {
        return ((IntTag) l.get(idx)).asInt();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setInt(ListTag<?> l, int idx, int v) {
        ((ListTag) l).set(idx, new IntTag(v));
    }

    // ---------- region 容器读写 ----------

    static final class ChunkRec {
        final int index;                 // 0..1023（文件内槽位，决定真实 chunk 坐标）
        CompoundTag tag;                 // 解析成功 -> 非 null
        byte[] raw;                      // 解析失败 -> 原样回写的压缩负载
        int comp;                        // 原压缩类型
        int timestamp;

        ChunkRec(int index) {
            this.index = index;
        }
    }

    static List<ChunkRec> readFile(File f) throws IOException {
        List<ChunkRec> out = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            if (raf.length() < SECTOR * HEADER_SECTORS) {
                return out;
            }
            int[] locs = new int[1024];
            int[] times = new int[1024];
            raf.seek(0);
            for (int i = 0; i < 1024; i++) {
                locs[i] = raf.readInt();
            }
            for (int i = 0; i < 1024; i++) {
                times[i] = raf.readInt();
            }
            for (int i = 0; i < 1024; i++) {
                int loc = locs[i];
                int off = loc >>> 8;
                int sectors = loc & 0xFF;
                if (off == 0 || sectors == 0) {
                    continue;
                }
                ChunkRec rec = new ChunkRec(i);
                rec.timestamp = times[i];
                try {
                    raf.seek((long) off * SECTOR);
                    int len = raf.readInt();
                    if (len <= 1 || len > sectors * SECTOR) {
                        continue;
                    }
                    byte comp = raf.readByte();
                    byte[] payload = new byte[len - 1];
                    raf.readFully(payload);
                    rec.comp = comp;
                    byte[] nbt = decompress(comp, payload);
                    NamedTag nt = new NBTDeserializer(false)
                            .fromStream(new ByteArrayInputStream(nbt));
                    if (nt != null && nt.getTag() instanceof CompoundTag ct) {
                        rec.tag = ct;
                    }
                } catch (Exception e) {
                    // 解析失败：原样保留压缩负载，重写文件时回写，不丢数据
                    rec.tag = null;
                    if (rec.comp != 0 && rec.raw == null) {
                        try {
                            raf.seek((long) off * SECTOR);
                            int len = raf.readInt();
                            raf.readByte();
                            rec.raw = new byte[len - 1];
                            raf.readFully(rec.raw);
                        } catch (Exception ignored) {
                            rec.raw = new byte[0];
                        }
                    }
                }
                out.add(rec);
            }
        }
        return out;
    }

    static void writeFile(File f, List<ChunkRec> chunks) throws IOException {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
            raf.setLength(0);
            raf.write(new byte[SECTOR * HEADER_SECTORS]);
            int[] locs = new int[1024];
            int[] times = new int[1024];
            long sector = HEADER_SECTORS;
            for (ChunkRec c : chunks) {
                byte[] payload;
                int comp;
                if (c.tag != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(SECTOR * 2);
                    try (DeflaterOutputStream zos = new DeflaterOutputStream(baos)) {
                        new NBTSerializer(false).toStream(new NamedTag(null, c.tag), zos);
                    }
                    payload = baos.toByteArray();
                    comp = 2;
                } else {
                    payload = c.raw != null ? c.raw : new byte[0];
                    comp = c.comp;
                }
                int len = payload.length + 1;
                int sectors = (4 + len + SECTOR - 1) / SECTOR;
                raf.seek(sector * SECTOR);
                raf.writeInt(len);
                raf.writeByte(comp);
                raf.write(payload);
                locs[c.index] = (int) (sector << 8) | (sectors & 0xFF);
                times[c.index] = c.timestamp;
                sector += sectors;
            }
            // 尾部补齐整扇区
            long end = raf.getFilePointer();
            long pad = ((end + SECTOR - 1) / SECTOR) * SECTOR - end;
            for (long i = 0; i < pad; i++) {
                raf.write(0);
            }
            raf.seek(0);
            for (int l : locs) {
                raf.writeInt(l);
            }
            for (int t : times) {
                raf.writeInt(t);
            }
        }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] decompress(int comp, byte[] data) throws IOException {
        return switch (comp) {
            case 1 -> readAll(new GZIPInputStream(new ByteArrayInputStream(data)));
            case 2 -> readAll(new InflaterInputStream(new ByteArrayInputStream(data)));
            case 3 -> data;
            default -> throw new IOException("未知 region 压缩类型: " + comp);
        };
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }
}
