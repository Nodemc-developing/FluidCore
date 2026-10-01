package com.ydxc20091.fluidcore.api;

/** Indicates an invalid owner thread, retired provider, or expired operation. */
public class StorageAccessException extends IllegalStateException {
    public StorageAccessException(String message) { super(message); }
    public StorageAccessException(String message, Throwable cause) { super(message, cause); }
}
