package dev.mist.home.model;

/**
 * 家园成员角色，数值越大权限越高。
 * 五档体系参考 charming-realm-system。
 */
public enum HomeRole {

    OWNER(4),
    OPERATOR(3),
    MEMBER(2),
    VISITOR(1),
    BANNED(0);

    private final int level;

    HomeRole(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    /** 是否有建造权限（放置/破坏/交互） */
    public boolean canBuild() {
        return this.level >= MEMBER.level;
    }

    /** 是否可邀请/移除成员 */
    public boolean canManageMembers() {
        return this.level >= OPERATOR.level;
    }

    /** 是否可封禁玩家 */
    public boolean canBan() {
        return this.level >= OPERATOR.level;
    }

    /** 是否可修改家园设置（可见性、出生点等） */
    public boolean canManageSettings() {
        return this.level >= OPERATOR.level;
    }

    /** 是否可访问该家园（未被禁） */
    public boolean canVisit() {
        return this != BANNED;
    }

    public static HomeRole byLevel(int level) {
        for (HomeRole role : values()) {
            if (role.level == level) {
                return role;
            }
        }
        return VISITOR;
    }
}
