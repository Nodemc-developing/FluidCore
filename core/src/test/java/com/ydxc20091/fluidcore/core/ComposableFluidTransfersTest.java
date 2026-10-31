package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ComposableFluidTransfersTest {
    private static final FluidVariant WATER = FluidVariant.of("minecraft:water");
    private static final FluidVariant LAVA = FluidVariant.of("minecraft:lava");

    @Test void composedTransferDoesNotCommitOrNotifyParent() {
        var notifications = new AtomicInteger(); var context = new TestContext();
        var source = new FluidTank(1000, context); source.restore(FluidStack.of(WATER, 900));
        var target = new SharedCapacityFluidStorage(1000, 2, context, variant -> true, notifications::incrementAndGet);
        try (var parent = FluidTransaction.open()) {
            var result = FluidTransfers.move(source, target, WATER, 600, FluidAction.EXECUTE, parent);
            assertTrue(result.successful()); assertEquals(600, result.amount());
            assertFalse(parent.isCommitted()); assertEquals(0, notifications.get());
            assertSame(parent, FluidTransaction.current().orElseThrow());
        }
        assertEquals(900, source.amount()); assertEquals(0, target.amount()); assertEquals(0, notifications.get());
    }

    @Test void failedOrSimulatedChildRetainsEarlierParentChanges() {
        var context = new TestContext(); var source = new FluidTank(1000, context);
        source.restore(FluidStack.of(WATER, 900));
        var target = new SharedCapacityFluidStorage(500, 2, context);
        var rejecting = new FluidTank(1000, context, LAVA::equals, () -> { });
        try (var parent = FluidTransaction.open()) {
            target.insert(LAVA, 100, parent);
            assertEquals(TransferResult.Status.REJECTED, FluidTransfers.move(source, rejecting, WATER, 500, FluidAction.EXECUTE, parent).status());
            assertEquals(400, FluidTransfers.move(source, target, WATER, 500, FluidAction.SIMULATE, parent).amount());
            assertEquals(100, target.amount()); assertEquals(900, source.amount());
            assertEquals(400, FluidTransfers.move(source, target, 500, FluidAction.EXECUTE, parent).amount());
            parent.commit();
        }
        assertEquals(500, target.amount()); assertEquals(500, source.amount());
    }

    @Test void parentRollbackRestoresRateLimitBudgetAndFluidTogether() {
        var context = new TestContext(); var source = new FluidTank(1000, context);
        source.restore(FluidStack.of(WATER, 1000));
        var target = new SharedCapacityFluidStorage(1000, 2, context);
        var limited = StorageViews.rateLimit(target, 300, 100);
        try (var parent = FluidTransaction.open()) {
            assertEquals(300, FluidTransfers.move(StorageViews.output(source), limited, WATER, 700, FluidAction.EXECUTE, parent).amount());
        }
        assertEquals(1000, source.amount()); assertEquals(0, target.amount());
        assertEquals(300, FluidTransfers.move(source, limited, WATER, 700, FluidAction.EXECUTE).amount());
    }

    @Test void parentReceivesItsOwnCommitNotificationFailure() {
        var source = new FluidTank(1000, new TestContext()); source.restore(FluidStack.of(WATER, 1000));
        var target = new FluidTank(1000, new TestContext(), variant -> true, () -> { throw new IllegalStateException("notification"); });
        try (var parent = FluidTransaction.open()) {
            assertEquals(300, FluidTransfers.move(source, target, WATER, 300, FluidAction.EXECUTE, parent).amount());
            assertThrows(FluidTransaction.CommitNotificationException.class, parent::commit);
            assertTrue(parent.isCommitted());
        }
        assertEquals(700, source.amount()); assertEquals(300, target.amount());
    }

    @Test void legacyOverloadStillRequiresExplicitParentAndCurrentTracksNestedScope() {
        var source = new FluidTank(1000, new TestContext()); source.restore(FluidStack.of(WATER, 1000));
        var target = new FluidTank(1000, new TestContext());
        assertTrue(FluidTransaction.current().isEmpty());
        try (var parent = FluidTransaction.open()) {
            assertThrows(IllegalStateException.class, () -> FluidTransfers.move(source, target, WATER, 300, FluidAction.EXECUTE));
            try (var child = parent.openNested()) { assertSame(child, FluidTransaction.current().orElseThrow()); }
            assertSame(parent, FluidTransaction.current().orElseThrow());
        }
        assertTrue(FluidTransaction.current().isEmpty());
        assertEquals(1000, source.amount()); assertEquals(0, target.amount());
    }
}
