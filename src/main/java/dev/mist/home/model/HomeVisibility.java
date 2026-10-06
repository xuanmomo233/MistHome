package dev.mist.home.model;

/**
 * 家园可见性：PRIVATE 仅邀请可入；PUBLIC 出现在公共列表可自由参观。
 */
public enum HomeVisibility {
    PRIVATE,
    PUBLIC;

    public static HomeVisibility of(String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (Exception e) {
            return PRIVATE;
        }
    }
}
