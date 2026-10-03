package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.api.StorageContext;

final class TestContext implements StorageContext {
    private final Thread owner = Thread.currentThread();
    long tick;
    boolean retired;
    @Override public void checkAccess() {
        if (Thread.currentThread() != owner || retired) throw new StorageAccessException("Invalid storage owner");
    }
    @Override public long tick() { return tick; }
}
