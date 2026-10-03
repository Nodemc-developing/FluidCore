package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class FluidTransactionTest {
    private static final FluidVariant WATER = FluidVariant.of("minecraft:water");

    @Test void closeWithoutCommitRollsBackAllParticipants() {
        var a = new FluidTank(1000, new TestContext());
        var b = new FluidTank(1000, new TestContext());
        try (var transaction = FluidTransaction.open()) {
            a.insert(WATER, 700, transaction); b.insert(WATER, 500, transaction);
        }
        assertEquals(FluidStack.EMPTY, a.content(0));
        assertEquals(FluidStack.EMPTY, b.content(0));
        assertEquals(0, a.version());
    }
    @Test void nestedRollbackPreservesOuterChanges() {
        var tank = new FluidTank(1000, new TestContext());
        try (var outer = FluidTransaction.open()) {
            tank.insert(WATER, 100, outer);
            try (var inner = outer.openNested()) { tank.insert(WATER, 300, inner); }
            assertEquals(100, tank.content(0).amount());
            outer.commit();
        }
        assertEquals(100, tank.content(0).amount());
    }
    @Test void nestedCommitStillRollsBackWithOuter() {
        var tank = new FluidTank(1000, new TestContext());
        try (var outer = FluidTransaction.open()) {
            try (var inner = outer.openNested()) { tank.insert(WATER, 300, inner); inner.commit(); }
            assertEquals(300, tank.content(0).amount());
        }
        assertEquals(0, tank.content(0).amount());
    }
    @Test void nestedCommitNotifiesOnlyAfterRootCommit() {
        AtomicInteger notifications = new AtomicInteger();
        var tank = new FluidTank(1000, new TestContext(), variant -> true, notifications::incrementAndGet);
        try (var outer = FluidTransaction.open()) {
            try (var inner = outer.openNested()) { tank.insert(WATER, 300, inner); inner.commit(); }
            assertEquals(0, notifications.get());
            outer.commit();
        }
        assertEquals(1, notifications.get());
        assertEquals(1, tank.version());
    }
    @Test void simulationAndNetZeroCommitDoNotNotify() {
        AtomicInteger notifications = new AtomicInteger();
        var tank = new FluidTank(1000, new TestContext(), variant -> true, notifications::incrementAndGet);
        assertEquals(1000, tank.fill(FluidStack.of(WATER, 1000), FluidAction.SIMULATE));
        try (var transaction = FluidTransaction.open()) {
            tank.insert(WATER, 100, transaction); tank.extract(WATER, 100, transaction); transaction.commit();
        }
        assertEquals(0, notifications.get());
        assertEquals(0, tank.version());
    }
    @Test void exceptionRollsBackAndDoesNotPoisonNextTransaction() {
        var tank = new FluidTank(1000, new TestContext());
        assertThrows(IllegalArgumentException.class, () -> {
            try (var transaction = FluidTransaction.open()) {
                tank.insert(WATER, 300, transaction);
                throw new IllegalArgumentException("Deliberate failure");
            }
        });
        assertEquals(0, tank.content(0).amount());
        assertEquals(200, tank.fill(FluidStack.of(WATER, 200), FluidAction.EXECUTE));
    }
    @Test void crossingTickRejectsCommitAndRollsBack() {
        var context = new TestContext();
        var tank = new FluidTank(1000, context);
        try (var transaction = FluidTransaction.open()) {
            tank.insert(WATER, 300, transaction);
            context.tick++;
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(0, tank.content(0).amount());
    }
    @Test void wrongThreadIsRejectedWithoutChangingStorage() throws InterruptedException {
        var tank = new FluidTank(1000, new TestContext());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { tank.fill(FluidStack.of(WATER, 100), FluidAction.EXECUTE); }
            catch (Throwable exception) { failure.set(exception); }
        });
        worker.start(); worker.join();
        assertInstanceOf(StorageAccessException.class, failure.get());
        assertEquals(0, tank.content(0).amount());
    }
    @Test void parentCannotMutateWhileChildIsOpen() {
        var tank = new FluidTank(1000, new TestContext());
        try (var outer = FluidTransaction.open(); var inner = outer.openNested()) {
            assertThrows(IllegalStateException.class, () -> tank.insert(WATER, 100, outer));
        }
    }
    @Test void allNotificationsRunEvenIfOneFailsAndStateStaysCommitted() {
        AtomicInteger notifications = new AtomicInteger();
        var a = new FluidTank(1000, new TestContext(), variant -> true, () -> { throw new IllegalStateException("Notification failed"); });
        var b = new FluidTank(1000, new TestContext(), variant -> true, notifications::incrementAndGet);
        try (var transaction = FluidTransaction.open()) {
            a.insert(WATER, 100, transaction); b.insert(WATER, 200, transaction);
            assertThrows(FluidTransaction.CommitNotificationException.class, transaction::commit);
            assertTrue(transaction.isCommitted());
        }
        assertEquals(100, a.content(0).amount()); assertEquals(200, b.content(0).amount());
        assertEquals(1, notifications.get());
    }
    @Test void directSingleTankNotificationFailureStillMeansCommitted() {
        var tank = new FluidTank(1000, new TestContext(), variant -> true, () -> { throw new IllegalStateException("Notification failed"); });
        assertThrows(FluidTransaction.CommitNotificationException.class, () -> tank.fill(FluidStack.of(WATER, 300), FluidAction.EXECUTE));
        assertEquals(300, tank.content(0).amount()); assertEquals(1, tank.version());
        assertThrows(FluidTransaction.CommitNotificationException.class, () -> tank.drain(WATER, 100, FluidAction.EXECUTE));
        assertEquals(200, tank.content(0).amount()); assertEquals(2, tank.version());
    }
    @Test void directSimulationStillChecksOwnershipAfterFilter() {
        var context = new TestContext();
        var tank = new FluidTank(1000, context, variant -> { context.tick++; return true; }, () -> {});
        assertThrows(StorageAccessException.class, () -> tank.fill(FluidStack.of(WATER, 300), FluidAction.SIMULATE));
        assertEquals(0, tank.content(0).amount());
    }
}
