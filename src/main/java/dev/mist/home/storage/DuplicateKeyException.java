package dev.mist.home.storage;

/**
 * 唯一键冲突（homes.owner_uuid / homes.slot_index 等 UNIQUE 约束）。
 * 调用方可依据 {@link #constraint()} 判断冲突字段，
 * 例如 slot_index 冲突时换下一个空闲槽位重试。
 */
public class DuplicateKeyException extends StorageException {

    /** 被违反的约束/列名（尽力解析，可能为 null） */
    private final String constraint;

    public DuplicateKeyException(String constraint, String message, Throwable cause) {
        super(message, cause);
        this.constraint = constraint;
    }

    public String constraint() {
        return constraint;
    }
}
