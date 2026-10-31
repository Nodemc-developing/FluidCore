package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FluidRegistrySubscriptionTest {
    @Test void successfulBatchNotifiesOnceOutsideMonitorWithCompleteImmutableSnapshots() {
        var registry = new FluidRegistry(); var changes = new ArrayList<FluidRegistry.Change>();
        try (var registration = registry.subscribe("consumer", change -> {
            assertFalse(Thread.holdsLock(registry)); changes.add(change);
        })) {
            registry.replaceOwned("example", List.of(FluidDefinition.builder("example:juice").displayName("Juice").build()),
                    Map.of(FluidKey.of("example:drink"), List.of(FluidKey.of("example:juice"))));
        }
        assertEquals(1, changes.size()); var change = changes.getFirst();
        assertFalse(change.before().fluids().containsKey(FluidKey.of("example:juice")));
        assertTrue(change.after().hasTag(FluidKey.of("example:juice"), FluidKey.of("example:drink")));
        assertEquals(change.before().generation() + 1, change.after().generation());
        assertThrows(UnsupportedOperationException.class, () -> change.after().fluids().clear());
    }

    @Test void failedCandidateDoesNotPublishOrNotify() {
        var registry = new FluidRegistry(); var calls = new AtomicInteger(); var before = registry.snapshot();
        try (var registration = registry.subscribe("consumer", change -> calls.incrementAndGet())) {
            assertThrows(IllegalArgumentException.class, () -> registry.register("foreign", FluidDefinition.builder("minecraft:water").build()));
        }
        assertSame(before, registry.snapshot()); assertEquals(0, calls.get());
    }

    @Test void reentrantUpdatesPreserveNotificationOrderAndFailuresDoNotBlockOthers() {
        var registry = new FluidRegistry(); var seen = new ArrayList<Long>(); var created = new AtomicInteger();
        registry.subscribe("first", change -> {
            if (created.getAndIncrement() == 0) registry.register("nested", FluidDefinition.builder("example:nested").build());
        });
        registry.subscribe("broken", change -> { throw new IllegalStateException("observer failure"); });
        registry.subscribe("last", change -> seen.add(change.after().generation()));
        long initial = registry.snapshot().generation();
        registry.register("first", FluidDefinition.builder("example:first").build());
        assertEquals(List.of(initial + 1, initial + 2), seen);
        assertTrue(registry.contains("example:nested"));
    }

    @Test void closingHandleAndUnregisteringOwnerRemoveOnlyTheirSubscriptions() {
        var registry = new FluidRegistry(); var a = new AtomicInteger(); var b = new AtomicInteger();
        var closed = registry.subscribe("a", change -> a.incrementAndGet());
        registry.subscribe("a", change -> a.incrementAndGet());
        registry.subscribe("b", change -> b.incrementAndGet());
        closed.close(); closed.close();
        registry.registerTag("tags", FluidKey.of("example:first"), List.of(FluidKey.of("minecraft:water")));
        assertEquals(1, a.get()); assertEquals(1, b.get());
        registry.unregisterListeners("a");
        registry.registerComponentCodec("codec", FluidKey.of("example:quality"), ComponentCodecs.INTEGER);
        assertEquals(1, a.get()); assertEquals(2, b.get());
    }

    @Test void apiLevelAndCapabilitiesDoNotChangeArtifactVersionOrPretendServerCapabilities() {
        assertEquals(1, FluidCoreApi.apiLevel());
        FluidCoreApi.requireApi(1);
        FluidCoreApi.requireCapabilities(FluidCoreApi.Capability.SHARED_CAPACITY_STORAGE, FluidCoreApi.Capability.REGISTRY_SUBSCRIPTIONS);
        assertThrows(IllegalStateException.class, () -> FluidCoreApi.requireApi(2));
        assertThrows(IllegalArgumentException.class, () -> FluidCoreApi.requireApi(-1));
        assertThrows(UnsupportedOperationException.class, () -> FluidCoreApi.capabilities().clear());
    }

    @Test void blockedObserverDoesNotHoldPublicationLockOrRunCallbacksConcurrently() throws Exception {
        var registry = new FluidRegistry();
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var active = new AtomicInteger(); var maximum = new AtomicInteger(); var seen = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        registry.subscribe("observer", change -> {
            int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max);
            try {
                if (seen.isEmpty()) {
                    entered.countDown();
                    try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                }
                seen.add(change.after().generation());
            } finally { active.decrementAndGet(); }
        });
        long initial = registry.snapshot().generation();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> registry.register("first", FluidDefinition.builder("example:first").build()));
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                var second = executor.submit(() -> registry.register("second", FluidDefinition.builder("example:second").build()));
                second.get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(registry.contains("example:second"));
            } finally { release.countDown(); }
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1, maximum.get()); assertEquals(List.of(initial + 1, initial + 2), seen);
    }
}
