package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class FluidStorageTest {
    private static final FluidVariant WATER = FluidVariant.of("minecraft:water");
    private static final FluidVariant LAVA = FluidVariant.of("minecraft:lava");
    private static FluidTank tank(long capacity) { return new FluidTank(capacity, new TestContext()); }

    @Test void capacityAndIdentityPreventMixing() {
        var tank = tank(1000);
        assertEquals(1000, tank.fill(FluidStack.of(WATER, 2000), FluidAction.EXECUTE));
        assertEquals(0, tank.fill(FluidStack.of(LAVA, 10), FluidAction.EXECUTE));
        assertEquals(1000, tank.drain(WATER, 2000, FluidAction.EXECUTE).amount());
        assertEquals(10, tank.fill(FluidStack.of(LAVA, 10), FluidAction.EXECUTE));
    }
    @Test void partialTransferConservesFluidWithOutputOnlySource() {
        var source = tank(2000); var destination = tank(400);
        source.restore(FluidStack.of(WATER, 1500));
        var result = FluidTransfers.move(StorageViews.output(source), StorageViews.input(destination), WATER, 1000, FluidAction.EXECUTE);
        assertEquals(TransferResult.Status.SUCCESS, result.status()); assertEquals(400, result.amount());
        assertEquals(1100, source.content(0).amount()); assertEquals(400, destination.content(0).amount());
    }
    @Test void simulatedTransferDoesNotMutateOrConsumeRateBudget() {
        var source = tank(1000); var destination = tank(1000);
        source.restore(FluidStack.of(WATER, 1000));
        var limited = StorageViews.rateLimit(destination, 200, 100);
        assertEquals(200, FluidTransfers.move(source, limited, WATER, 1000, FluidAction.SIMULATE).amount());
        assertEquals(0, destination.content(0).amount()); assertEquals(1000, source.content(0).amount());
        assertEquals(200, FluidTransfers.move(source, limited, WATER, 1000, FluidAction.EXECUTE).amount());
    }
    @Test void readonlyAndFiltersKeepState() {
        var tank = tank(1000);
        assertEquals(0, StorageViews.readonly(tank).fill(FluidStack.of(WATER, 500), FluidAction.EXECUTE));
        assertEquals(0, StorageViews.filter(tank, LAVA::equals).fill(FluidStack.of(WATER, 500), FluidAction.EXECUTE));
        assertEquals(500, StorageViews.filter(tank, LAVA::equals).fill(FluidStack.of(LAVA, 500), FluidAction.EXECUTE));
        assertEquals(0, StorageViews.readonly(tank).drain(LAVA, 500, FluidAction.EXECUTE).amount());
    }
    @Test void rateLimitsResetAtNextTickAndRollbackRestoresBudget() {
        var context = new TestContext(); var tank = new FluidTank(1000, context);
        var limited = StorageViews.rateLimit(tank, 200, 100);
        assertEquals(200, limited.fill(FluidStack.of(WATER, 500), FluidAction.EXECUTE));
        assertEquals(0, limited.fill(FluidStack.of(WATER, 500), FluidAction.EXECUTE));
        context.tick++;
        assertEquals(200, limited.fill(FluidStack.of(WATER, 500), FluidAction.SIMULATE));
        assertEquals(200, limited.fill(FluidStack.of(WATER, 500), FluidAction.EXECUTE));
        assertEquals(100, limited.drain(WATER, 500, FluidAction.EXECUTE).amount());
        assertEquals(0, limited.drain(WATER, 500, FluidAction.EXECUTE).amount());
    }
    @Test void sidePermissionsDefaultToClosed() {
        var tank = tank(1000);
        var sides = StorageViews.sided(tank, Map.of(StorageViews.Side.UP, StorageViews.Access.INPUT, StorageViews.Side.DOWN, StorageViews.Access.OUTPUT));
        assertEquals(200, sides.get(StorageViews.Side.UP).fill(FluidStack.of(WATER, 200), FluidAction.EXECUTE));
        assertEquals(0, sides.get(StorageViews.Side.UP).drain(WATER, 100, FluidAction.EXECUTE).amount());
        assertEquals(0, sides.get(StorageViews.Side.NORTH).drain(WATER, 100, FluidAction.EXECUTE).amount());
        assertEquals(100, sides.get(StorageViews.Side.DOWN).drain(WATER, 100, FluidAction.EXECUTE).amount());
    }
    @Test void multiTankRespectsIndividualFiltersAndIndexes() {
        var a = new FluidTank(100, new TestContext(), WATER::equals, () -> {});
        var b = new FluidTank(200, new TestContext(), LAVA::equals, () -> {});
        var c = tank(300);
        var multi = new MultiTankStorage(a, b, c);
        assertEquals(3, multi.tanks());
        assertEquals(400, multi.fill(FluidStack.of(WATER, 500), FluidAction.EXECUTE));
        assertEquals(0, b.content(0).amount());
        assertEquals(200, multi.fill(FluidStack.of(LAVA, 500), FluidAction.EXECUTE));
        assertEquals(LAVA, multi.content(1).variant());
        assertThrows(IndexOutOfBoundsException.class, () -> multi.content(3));
        assertThrows(IndexOutOfBoundsException.class, () -> multi.content(-1));
    }
    @Test void overCapacityRestorePreservesAndAllowsDrainOnlyUntilBelowCapacity() {
        var tank = tank(100);
        tank.restore(FluidStack.of(WATER, 300));
        assertEquals(300, tank.content(0).amount());
        assertEquals(0, tank.fill(FluidStack.of(WATER, 10), FluidAction.EXECUTE));
        assertEquals(250, tank.drain(WATER, 250, FluidAction.EXECUTE).amount());
        assertEquals(50, tank.fill(FluidStack.of(WATER, 100), FluidAction.EXECUTE));
    }
    @Test void maximumLongDoesNotOverflow() {
        var tank = tank(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, tank.fill(FluidStack.of(WATER, Long.MAX_VALUE), FluidAction.EXECUTE));
        assertEquals(0, tank.fill(FluidStack.of(WATER, Long.MAX_VALUE), FluidAction.EXECUTE));
        assertEquals(Long.MAX_VALUE, tank.drain(WATER, Long.MAX_VALUE, FluidAction.EXECUTE).amount());
    }
    @Test void dynamicOwnershipRejectsEitherParticipantWithoutFixedRegionTokens() {
        var source = tank(1000); var destinationContext = new TestContext();
        var destination = new FluidTank(1000, destinationContext);
        source.restore(FluidStack.of(WATER, 500));
        destinationContext.retired = true;
        assertEquals(TransferResult.Status.WRONG_CONTEXT, FluidTransfers.move(source, destination, WATER, 500, FluidAction.EXECUTE).status());
        assertEquals(500, source.content(0).amount());
    }
    @Test void transferToSameObjectIsRejected() {
        var tank = tank(1000); tank.restore(FluidStack.of(WATER, 500));
        assertEquals(TransferResult.Status.REJECTED, FluidTransfers.move(tank, tank, WATER, 100, FluidAction.EXECUTE).status());
        assertEquals(500, tank.content(0).amount());
    }
    @Test void repeatedRandomTransfersConserveTotal() {
        var a = tank(10000); var b = tank(10000);
        a.restore(FluidStack.of(WATER, 7777)); b.restore(FluidStack.of(WATER, 2222));
        var random = new Random(2009);
        for (int operation = 0; operation < 3000; operation++) {
            var source = random.nextBoolean() ? a : b;
            var target = source == a ? b : a;
            var action = random.nextInt(4) == 0 ? FluidAction.SIMULATE : FluidAction.EXECUTE;
            FluidTransfers.move(source, target, WATER, random.nextInt(1500), action);
            assertEquals(9999, Math.addExact(a.content(0).amount(), b.content(0).amount()));
            assertTrue(a.content(0).amount() <= 10000 && b.content(0).amount() <= 10000);
        }
    }
    @Test void rejectedDestinationLeavesSourceUntouched() {
        var source = tank(1000); var destination = tank(1000);
        source.restore(FluidStack.of(WATER, 500)); destination.restore(FluidStack.of(LAVA, 100));
        assertEquals(TransferResult.Status.REJECTED, FluidTransfers.move(source, destination, WATER, 500, FluidAction.EXECUTE).status());
        assertEquals(500, source.content(0).amount()); assertEquals(100, destination.content(0).amount());
    }
    @Test void cachedSnapshotsStayImmutableAcrossMutationAndRollback() {
        var tank = tank(1000);
        tank.fill(FluidStack.of(WATER, 300), FluidAction.EXECUTE);
        FluidStack before = tank.content(0);
        assertSame(before, tank.content(0));
        try (var transaction = FluidTransaction.open()) {
            tank.insert(WATER, 200, transaction);
            FluidStack tentative = tank.content(0);
            assertEquals(500, tentative.amount());
            assertSame(tentative, tank.content(0));
            assertEquals(300, before.amount());
        }
        assertEquals(300, tank.content(0).amount());
        assertEquals(300, before.amount());
        tank.drain(WATER, 300, FluidAction.EXECUTE);
        assertSame(FluidStack.EMPTY, tank.content(0));
    }
    @Test void nonTransactionalProviderIsRejectedBeforeAnyMutation() {
        var source = tank(1000); source.restore(FluidStack.of(WATER, 500));
        FluidStorage unsupported = new FluidStorage() {
            @Override public int tanks() { throw new AssertionError("Must not inspect an unsupported provider"); }
            @Override public FluidStack content(int tank) { throw new AssertionError(); }
            @Override public long capacity(int tank) { throw new AssertionError(); }
            @Override public long insert(FluidVariant variant, long amount, FluidTransaction transaction) { throw new AssertionError(); }
            @Override public long extract(FluidVariant variant, long amount, FluidTransaction transaction) { throw new AssertionError(); }
            @Override public boolean supportsTransactions() { return false; }
        };
        assertEquals(TransferResult.Status.UNSUPPORTED, FluidTransfers.move(source, unsupported, WATER, 500, FluidAction.EXECUTE).status());
        assertEquals(500, source.content(0).amount());
    }
    @Test void destinationExceptionAfterTentativeMutationRollsBackBothEnds() {
        var source = tank(1000); var target = tank(1000); source.restore(FluidStack.of(WATER, 500));
        FluidStorage throwing = new FluidStorage() {
            @Override public int tanks() { return target.tanks(); }
            @Override public FluidStack content(int index) { return target.content(index); }
            @Override public long capacity(int index) { return target.capacity(index); }
            @Override public StorageContext context() { return target.context(); }
            @Override public long insert(FluidVariant variant, long amount, FluidTransaction transaction) {
                target.insert(variant, amount, transaction);
                throw new IllegalStateException("Failure after tentative insertion");
            }
            @Override public long extract(FluidVariant variant, long amount, FluidTransaction transaction) { return target.extract(variant, amount, transaction); }
        };
        assertThrows(IllegalStateException.class, () -> FluidTransfers.move(source, throwing, WATER, 500, FluidAction.EXECUTE));
        assertEquals(500, source.content(0).amount()); assertEquals(0, target.content(0).amount());
        assertEquals(1, source.version()); assertEquals(0, target.version());
    }
    @Test void guardedCapabilityProbeReturnsWrongContextInsteadOfEscapingException() {
        var source = tank(1000); source.restore(FluidStack.of(WATER, 500));
        FluidStorage inaccessible = new FluidStorage() {
            @Override public int tanks() { throw new AssertionError(); }
            @Override public FluidStack content(int index) { throw new AssertionError(); }
            @Override public long capacity(int index) { throw new AssertionError(); }
            @Override public long insert(FluidVariant variant, long amount, FluidTransaction transaction) { throw new AssertionError(); }
            @Override public long extract(FluidVariant variant, long amount, FluidTransaction transaction) { throw new AssertionError(); }
            @Override public boolean supportsTransactions() { throw new StorageAccessException("Guarded provider belongs to another owner"); }
        };
        assertEquals(TransferResult.Status.WRONG_CONTEXT, FluidTransfers.move(source, inaccessible, WATER, 500, FluidAction.EXECUTE).status());
        assertEquals(500, source.content(0).amount());
    }
}
