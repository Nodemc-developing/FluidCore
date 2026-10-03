package com.ydxc20091.fluidcore.ui;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TankMenuRenderCacheTest {
    @Test void unchangedSlotsDoNotCreateCopiesAndOneChangeDoesNotRefreshOtherSlots() {
        var copies = new AtomicInteger(); var cache = new TankMenuRenderCache<String>(63, value -> { copies.incrementAndGet(); return value; });
        for (int i = 0; i < 63; i++) assertTrue(cache.update(i, "original"));
        for (int round = 0; round < 100; round++) for (int i = 0; i < 63; i++) assertFalse(cache.update(i, "original"));
        assertEquals(63, copies.get()); assertTrue(cache.update(37, "changed"));
        assertEquals(64, copies.get()); assertEquals(64, cache.updates()); assertEquals("original", cache.value(36));
    }
    @Test void displayCopiesDetectLaterMutationOfTheLiveValue() {
        var cache = new TankMenuRenderCache<java.util.List<String>>(1, java.util.ArrayList::new);
        var live = new java.util.ArrayList<>(java.util.List.of("first")); cache.update(0, live);
        live.add("second"); assertEquals(java.util.List.of("first"), cache.value(0)); assertTrue(cache.update(0, live));
        assertFalse(cache.update(0, live));
    }
}
