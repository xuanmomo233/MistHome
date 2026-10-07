package dev.mist.home.storage;

/**
 * 存储层运行时异常。Storage 接口方法不声明受检异常，
 * 实现方将 SQLException 统一包装为此类型抛出。
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
