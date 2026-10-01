package com.ydxc20091.fluidcore.api;

/** Dynamically verifies ownership; implementations must not cache a Folia region token. */
public interface StorageContext {
    void checkAccess();
    long tick();

    default void checkSameContext(StorageContext other) {
        checkAccess();
        other.checkAccess();
    }

    /** Suitable for standalone data only; world and player storage needs a live owner check. */
    static StorageContext confinedToCurrentThread() {
        Thread owner = Thread.currentThread();
        return new StorageContext() {
            @Override public void checkAccess() {
                if (Thread.currentThread() != owner) throw new StorageAccessException("Wrong storage thread");
            }
            @Override public long tick() { return 0; }
        };
    }
}
