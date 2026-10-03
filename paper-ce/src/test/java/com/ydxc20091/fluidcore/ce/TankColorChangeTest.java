package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TankColorChangeTest {
    private int color = 0xffffff;
    private int saved = color;
    private int notifications;
    private long tick, generation;
    private final StorageContext context = new StorageContext() {
        public void checkAccess() { }
        public long tick() { return tick; }
    };
    private final TankColorChange operation = new TankColorChange(context, () -> color, value -> color = value,
            () -> generation, () -> { saved = color; notifications++; assertFalse(FluidTransaction.hasOpenTransaction()); });

    @Test void firstRealChangeValidatesBeforeWritingAndPublishesOnlyAfterCommit() {
        try (var transaction = FluidTransaction.open()) {
            operation.set(0xff0000, transaction);
            assertEquals(0xff0000, color);
            assertEquals(0xffffff, saved);
            assertEquals(0, notifications);
            transaction.commit();
        }
        assertEquals(0xff0000, saved);
        assertEquals(1, notifications);
    }

    @Test void failedItemReservationRollsBackColorWithoutSavingOrRenderingIt() {
        try (var transaction = FluidTransaction.open()) { operation.set(0xff0000, transaction); }
        assertEquals(0xffffff, color);
        assertEquals(0xffffff, saved);
        assertEquals(0, notifications);
    }

    @Test void anotherParticipantFailureRollsColorBackAndDoesNotPublishIt() {
        try (var transaction = FluidTransaction.open()) {
            operation.set(0xff0000, transaction);
            transaction.enlist(new TransactionParticipant() {
                public Object snapshot() { return null; }
                public void restore(Object snapshot) { }
                public void validate() { }
                public void validateSnapshot(Object snapshot) { throw new StorageAccessException("Item reservation changed"); }
                public void afterCommit() { fail("Failed validation cannot notify"); }
            });
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(0xffffff, color);
        assertEquals(0xffffff, saved);
        assertEquals(0, notifications);
    }

    @Test void unrelatedExternalColorChangeIsRejectedWithoutOverwritingItOnRollback() {
        try (var transaction = FluidTransaction.open()) {
            operation.set(0xff0000, transaction);
            color = 0x0000ff;
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(0x0000ff, color);
        assertEquals(0xffffff, saved);
        assertEquals(0, notifications);
    }

    @Test void tickOrReloadChangesRejectBeforePublishing() {
        try (var transaction = FluidTransaction.open()) {
            operation.set(0xff0000, transaction);
            tick++;
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        try (var transaction = FluidTransaction.open()) {
            operation.set(0xff0000, transaction);
            generation++;
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(0xffffff, color);
        assertEquals(0xffffff, saved);
        assertEquals(0, notifications);
    }

    @Test void aRendererNotificationFailureCannotUndoAlreadyCommittedColor() {
        var failedNotification = new TankColorChange(context, () -> color, value -> color = value,
                () -> generation, () -> { saved = color; throw new IllegalStateException("Native renderer failed"); });
        try (var transaction = FluidTransaction.open()) {
            failedNotification.set(0xff0000, transaction);
            assertThrows(FluidTransaction.CommitNotificationException.class, transaction::commit);
            assertTrue(transaction.isCommitted());
        }
        assertEquals(0xff0000, color);
        assertEquals(0xff0000, saved);
    }
}
