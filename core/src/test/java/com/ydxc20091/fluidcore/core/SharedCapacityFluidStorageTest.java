package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SharedCapacityFluidStorageTest {
    private static final FluidVariant WATER = FluidVariant.of("minecraft:water");
    private static final FluidVariant LAVA = FluidVariant.of("minecraft:lava");

    @Test void distinctFluidsShareOneBudgetThroughAllViews() {
        var pool = new SharedCapacityFluidStorage(1000, 3, new TestContext());
        assertEquals(600, pool.fill(FluidStack.of(WATER, 600), FluidAction.EXECUTE));
        var repeatedView = new MultiTankStorage(pool, StorageViews.input(pool));
        assertEquals(400, repeatedView.fill(FluidStack.of(LAVA, 700), FluidAction.EXECUTE));
        assertEquals(1000, pool.amount()); assertEquals(0, pool.space());
        assertEquals(0, pool.fill(FluidStack.of(WATER, 1), FluidAction.EXECUTE));
        assertEquals(600, pool.content(0).amount()); assertEquals(400, pool.content(1).amount());
    }

    @Test void identicalIdsWithDifferentComponentsKeepSeparateSlots() {
        var quality = new ComponentType<Integer>(FluidKey.of("example:quality"), ComponentCodecs.INTEGER);
        var high = WATER.with(quality, 90); var low = WATER.with(quality, 30);
        var pool = new SharedCapacityFluidStorage(1000, 2, new TestContext());
        assertEquals(500, pool.fill(FluidStack.of(high, 500), FluidAction.EXECUTE));
        assertEquals(300, pool.fill(FluidStack.of(low, 300), FluidAction.EXECUTE));
        assertEquals(0, pool.fill(FluidStack.of(LAVA, 100), FluidAction.EXECUTE));
        assertEquals(200, pool.fill(FluidStack.of(high, 400), FluidAction.EXECUTE));
        assertEquals(high, pool.content(0).variant()); assertEquals(low, pool.content(1).variant());
        assertEquals(0, pool.drain(WATER, 1000, FluidAction.EXECUTE).amount());
    }

    @Test void nestedCommitIsStillRolledBackByParent() {
        var notifications = new AtomicInteger();
        var pool = new SharedCapacityFluidStorage(1000, 2, new TestContext(), variant -> true, notifications::incrementAndGet);
        try (var parent = FluidTransaction.open()) {
            pool.insert(WATER, 600, parent);
            try (var child = parent.openNested()) { pool.insert(LAVA, 400, child); child.commit(); }
            assertEquals(1000, pool.amount()); assertEquals(0, notifications.get());
        }
        assertEquals(0, pool.amount()); assertEquals(0, pool.version()); assertEquals(0, notifications.get());
    }

    @Test void simulationDoesNotConsumeSpaceAndRootCommitNotifiesOnce() {
        var notifications = new AtomicInteger();
        var pool = new SharedCapacityFluidStorage(1000, 2, new TestContext(), variant -> true, notifications::incrementAndGet);
        assertEquals(800, pool.fill(FluidStack.of(WATER, 800), FluidAction.SIMULATE));
        assertEquals(0, pool.amount());
        try (var parent = FluidTransaction.open()) {
            pool.insert(WATER, 600, parent);
            try (var child = parent.openNested()) { pool.insert(LAVA, 400, child); }
            assertEquals(600, pool.amount());
            pool.insert(LAVA, 300, parent); parent.commit();
        }
        assertEquals(900, pool.amount()); assertEquals(1, notifications.get()); assertEquals(1, pool.version());
    }

    @Test void shrinkingAndLoadingOverfullRecordsNeverTruncatesContents() {
        var pool = new SharedCapacityFluidStorage(1000, 2, new TestContext());
        pool.restore(List.of(FluidStack.of(WATER, 600), FluidStack.of(LAVA, 800)));
        var before = pool.contents(); pool.resize(500);
        assertEquals(before, pool.contents()); assertEquals(1400, pool.amount());
        assertEquals(0, pool.fill(FluidStack.of(WATER, 1), FluidAction.EXECUTE));
        assertEquals(600, pool.drain(WATER, 600, FluidAction.EXECUTE).amount());
        assertEquals(400, pool.drain(LAVA, 400, FluidAction.EXECUTE).amount());
        assertEquals(100, pool.fill(FluidStack.of(WATER, 1000), FluidAction.EXECUTE));
        assertEquals(500, pool.amount());
    }

    @Test void overflowingOrMismatchedRestoreRejectsWholeRecord() {
        var pool = new SharedCapacityFluidStorage(Long.MAX_VALUE, 2, new TestContext());
        pool.fill(FluidStack.of(WATER, 123), FluidAction.EXECUTE);
        var before = pool.contents(); long version = pool.version();
        assertThrows(ArithmeticException.class, () -> pool.restore(List.of(FluidStack.of(WATER, Long.MAX_VALUE), FluidStack.of(LAVA, 1))));
        assertThrows(IllegalArgumentException.class, () -> pool.restore(List.of(FluidStack.EMPTY)));
        assertEquals(before, pool.contents()); assertEquals(version, pool.version());
        assertEquals(Long.MAX_VALUE - 123, pool.fill(FluidStack.of(LAVA, Long.MAX_VALUE), FluidAction.EXECUTE));
        assertEquals(Long.MAX_VALUE, pool.amount());
    }

    @Test void restoredDuplicateSlotsDrainWithoutOverflowOrIdentityLoss() {
        var pool = new SharedCapacityFluidStorage(1000, 3, new TestContext());
        pool.restore(List.of(FluidStack.of(WATER, 200), FluidStack.of(WATER, 300), FluidStack.of(LAVA, 100)));
        assertEquals(450, pool.drain(WATER, 450, FluidAction.EXECUTE).amount());
        assertEquals(150, pool.amount()); assertEquals(50, pool.content(1).amount());
        assertEquals(100, pool.content(2).amount());
    }

    @Test void tickBoundaryRejectsCommitAndRestoresSharedBudget() {
        var context = new TestContext(); var pool = new SharedCapacityFluidStorage(1000, 2, context);
        try (var tx = FluidTransaction.open()) {
            pool.insert(WATER, 700, tx); pool.insert(LAVA, 300, tx); context.tick++;
            assertThrows(StorageAccessException.class, tx::commit);
        }
        assertEquals(0, pool.amount()); assertEquals(1000, pool.space());
    }

    @Test void resizeAndPersistenceLoadCannotBypassAnOpenTransaction() {
        var pool = new SharedCapacityFluidStorage(1000, 2, new TestContext());
        try (var tx = FluidTransaction.open()) {
            pool.insert(WATER, 700, tx);
            assertThrows(IllegalStateException.class, () -> pool.resize(2000));
            assertThrows(IllegalStateException.class, () -> pool.restore(List.of(FluidStack.EMPTY, FluidStack.EMPTY)));
        }
        assertEquals(0, pool.amount()); assertEquals(1000, pool.capacity());
    }

    @Test void filterAndReadOnlyViewsDoNotModifyStorage() {
        var pool = new SharedCapacityFluidStorage(1000, 2, new TestContext(), WATER::equals, () -> { });
        assertFalse(pool.isFluidValid(0, LAVA));
        assertEquals(0, pool.fill(FluidStack.of(LAVA, 300), FluidAction.EXECUTE));
        assertEquals(0, StorageViews.readonly(pool).fill(FluidStack.of(WATER, 300), FluidAction.EXECUTE));
        assertThrows(UnsupportedOperationException.class, () -> pool.contents().set(0, FluidStack.of(WATER, 1)));
        assertEquals(0, pool.amount());
    }

    @Test void committedNotificationFailureDoesNotUndoFluid() {
        var pool = new SharedCapacityFluidStorage(1000, 2, new TestContext(), variant -> true, () -> { throw new IllegalStateException("notification"); });
        assertThrows(FluidTransaction.CommitNotificationException.class, () -> pool.fill(FluidStack.of(WATER, 100), FluidAction.EXECUTE));
        assertEquals(100, pool.amount()); assertEquals(1, pool.version());
    }

    @Test void filterCannotHideCrossingATickBeforeTransactionCapture() {
        var context = new TestContext();
        var pool = new SharedCapacityFluidStorage(1000, 2, context, variant -> { context.tick++; return true; }, () -> { });
        assertThrows(StorageAccessException.class, () -> pool.fill(FluidStack.of(WATER, 300), FluidAction.EXECUTE));
        assertEquals(0, pool.amount()); assertEquals(1000, pool.space());
    }

    @Test void randomMixedIdentityTransfersAndNestedRollbacksConserveEachFluid() {
        var context = new TestContext(); var a = new SharedCapacityFluidStorage(10000, 3, context);
        var b = new SharedCapacityFluidStorage(10000, 3, context);
        a.restore(List.of(FluidStack.of(WATER, 7000), FluidStack.of(LAVA, 2000), FluidStack.EMPTY));
        var random = new java.util.Random(34449);
        for (int operation = 0; operation < 1000; operation++) {
            var source = random.nextBoolean() ? a : b; var destination = source == a ? b : a;
            var identity = random.nextBoolean() ? WATER : LAVA;
            try (var parent = FluidTransaction.open()) {
                FluidTransfers.move(source, destination, identity, random.nextInt(2000),
                        random.nextInt(4) == 0 ? FluidAction.SIMULATE : FluidAction.EXECUTE, parent);
                if (random.nextBoolean()) parent.commit();
            }
            assertEquals(7000, quantity(a, WATER) + quantity(b, WATER));
            assertEquals(2000, quantity(a, LAVA) + quantity(b, LAVA));
            assertTrue(a.amount() <= 10000 && b.amount() <= 10000);
            assertEquals(10000 - a.amount(), a.space()); assertEquals(10000 - b.amount(), b.space());
        }
    }
    private static long quantity(SharedCapacityFluidStorage storage, FluidVariant identity) {
        return storage.contents().stream().filter(stack -> !stack.isEmpty() && stack.variant().equals(identity))
                .mapToLong(FluidStack::amount).sum();
    }
}
