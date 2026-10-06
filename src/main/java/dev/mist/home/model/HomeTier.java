package dev.mist.home.model;

/**
 * 家园档位。家园物理槽位始终按最大档预留（slot-size），
 * radius 为当前档位实际可用范围的半径（格），升级仅扩大半径不发生迁移。
 *
 * @param level         档位等级，从 0 连续递增
 * @param name          档位显示名
 * @param radius        当前档可用半径（格）
 * @param upgradePrice  升到本档所需价格（Vault）
 */
public record HomeTier(int level, String name, int radius, double upgradePrice) {
}
