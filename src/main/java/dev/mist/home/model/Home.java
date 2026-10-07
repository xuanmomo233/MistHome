package dev.mist.home.model;

import java.util.UUID;

/**
 * 家园实体。slotIndex 仅作全局唯一序号（DB 唯一约束），
 * 物理位置由世界池停放台账动态决定。
 * spawnX/Z 为相对槽位中心的偏移量（家园可停在任意槽位），
 * spawnY 为绝对高度。
 */
public class Home {

    private final long id;
    private final UUID owner;
    private String name;
    private final int slotIndex;
    private int tierLevel;
    private String template;
    private HomeVisibility visibility;
    // 家园出生点：spawnX/Z 相对槽位中心的偏移，spawnY 绝对高度
    private double spawnX;
    private double spawnY;
    private double spawnZ;
    private float spawnYaw;
    private float spawnPitch;
    private final long createdAt;

    public Home(long id, UUID owner, String name, int slotIndex, int tierLevel,
                String template, HomeVisibility visibility,
                double spawnX, double spawnY, double spawnZ,
                float spawnYaw, float spawnPitch, long createdAt) {
        this.id = id;
        this.owner = owner;
        this.name = name;
        this.slotIndex = slotIndex;
        this.tierLevel = tierLevel;
        this.template = template;
        this.visibility = visibility;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.spawnZ = spawnZ;
        this.spawnYaw = spawnYaw;
        this.spawnPitch = spawnPitch;
        this.createdAt = createdAt;
    }

    public long id() { return id; }
    public UUID owner() { return owner; }
    public String name() { return name; }
    public int slotIndex() { return slotIndex; }
    public int tierLevel() { return tierLevel; }
    public String template() { return template; }
    public HomeVisibility visibility() { return visibility; }
    public double spawnX() { return spawnX; }
    public double spawnY() { return spawnY; }
    public double spawnZ() { return spawnZ; }
    public float spawnYaw() { return spawnYaw; }
    public float spawnPitch() { return spawnPitch; }
    public long createdAt() { return createdAt; }

    public void setName(String name) { this.name = name; }
    public void setTierLevel(int tierLevel) { this.tierLevel = tierLevel; }
    public void setVisibility(HomeVisibility visibility) { this.visibility = visibility; }
    public void setSpawn(double x, double y, double z, float yaw, float pitch) {
        this.spawnX = x;
        this.spawnY = y;
        this.spawnZ = z;
        this.spawnYaw = yaw;
        this.spawnPitch = pitch;
    }
}
