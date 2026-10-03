/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.api.StorageAccessException;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import static com.ydxc20091.fluidcore.bukkit.ParticipantTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryParticipantTest {
    @Test void completeBatchMergesAndSplitsWithoutChangingMetadataOrCallerStack() {
        var fixture = new InventoryFixture();
        fixture.contents[0] = stack(60);
        fixture.contents[1] = stack(63);
        var original = stack(130);
        assertTrue(fixture.access.canPut(original));
        assertEquals(0, fixture.writes);
        try (var transaction = FluidTransaction.open()) {
            assertTrue(fixture.access.put(original, transaction));
            transaction.commit();
        }
        assertEquals(130, original.getAmount());
        assertEquals(64, fixture.contents[0].getAmount());
        assertEquals(64, fixture.contents[1].getAmount());
        assertEquals(64, fixture.contents[2].getAmount());
        assertEquals(61, fixture.contents[3].getAmount());
        for (int slot = 0; slot < 4; slot++) {
            assertTrue(fixture.contents[slot].isSimilar(original));
            assertArrayEquals(new byte[]{1, 7, -2}, fixture.contents[slot].getItemMeta()
                    .getPersistentDataContainer().get(FOREIGN, PersistentDataType.BYTE_ARRAY));
        }
        original.getItemMeta().getPersistentDataContainer().set(FOREIGN, PersistentDataType.BYTE_ARRAY, new byte[]{9});
        assertArrayEquals(new byte[]{1, 7, -2}, fixture.contents[2].getItemMeta()
                .getPersistentDataContainer().get(FOREIGN, PersistentDataType.BYTE_ARRAY));
    }

    @Test void insufficientTotalCapacityRejectsBeforeAnyPartialWrite() {
        var fixture = new InventoryFixture();
        fixture.fillStorage();
        fixture.contents[0] = stack(63);
        fixture.contents[1] = stack(62);
        assertFalse(fixture.access.canPut(stack(4)));
        try (var transaction = FluidTransaction.open()) {
            assertFalse(fixture.access.put(stack(4), transaction));
            transaction.commit();
        }
        assertEquals(0, fixture.writes);
        assertEquals(63, fixture.contents[0].getAmount());
        assertEquals(62, fixture.contents[1].getAmount());
        assertNull(fixture.contents[36]);
    }

    @Test void batchRollbackRestoresEveryEditedSlotAndOriginalMetadata() {
        var fixture = new InventoryFixture();
        fixture.contents[0] = stack(63);
        try (var transaction = FluidTransaction.open()) { assertTrue(fixture.access.put(stack(70), transaction)); }
        assertEquals(stack(63), fixture.contents[0]);
        assertNull(fixture.contents[1]);
        assertNull(fixture.contents[2]);
    }

    @Test void countedTakePreservesTheRemainderAndRejectsChangedSourceOrExcessAmount() {
        var fixture = new InventoryFixture();
        fixture.contents[0] = stack(7);
        var original = fixture.contents[0].clone();
        var wrong = original.clone();
        wrong.getItemMeta().getPersistentDataContainer().set(IDENTIFIER, PersistentDataType.STRING, "other:item");
        try (var transaction = FluidTransaction.open()) {
            assertFalse(fixture.access.take(0, wrong, 3, transaction));
            assertFalse(fixture.access.take(0, original, 8, transaction));
            assertFalse(fixture.access.take(0, original, 0, transaction));
            assertTrue(fixture.access.take(0, original, 5, transaction));
            transaction.commit();
        }
        assertEquals(7, original.getAmount());
        assertEquals(stack(2), fixture.contents[0]);
        assertEquals(1, fixture.writes);
    }

    @Test void putAtHonorsExactSlotStackLimitAndMetadataCompatibility() {
        var fixture = new InventoryFixture();
        fixture.maximum = 32;
        fixture.contents[4] = stack(30);
        var wrong = stack(1);
        wrong.getItemMeta().setDisplayName("different");
        try (var transaction = FluidTransaction.open()) {
            assertFalse(fixture.access.putAt(4, wrong, transaction));
            assertFalse(fixture.access.putAt(4, stack(3), transaction));
            assertTrue(fixture.access.putAt(4, stack(2), transaction));
            transaction.commit();
            assertThrows(IllegalStateException.class, transaction::commit);
        }
        assertEquals(stack(32), fixture.contents[4]);
        assertNull(fixture.contents[0]);
        assertEquals(1, fixture.writes);
    }

    @Test void ordinaryInsertionAndTargetedInsertionCannotUseEquipmentAsOverflow() {
        var fixture = new InventoryFixture();
        fixture.fillStorage();
        fixture.contents[40] = stack(3);
        try (var transaction = FluidTransaction.open()) {
            assertFalse(fixture.access.put(stack(1), transaction));
            assertFalse(fixture.access.putAt(36, stack(1), transaction));
            assertFalse(fixture.access.putAt(40, stack(1), transaction));
            assertTrue(fixture.access.take(40, stack(3), 2, transaction));
            transaction.commit();
        }
        assertNull(fixture.contents[36]);
        assertEquals(stack(1), fixture.contents[40]);
    }

    @Test void oneItemApisKeepFirstAvailableSlotAndRejectMultipleItems() {
        var fixture = new InventoryFixture();
        fixture.contents[1] = stack(63);
        assertFalse(fixture.access.canPutOne(stack(2)));
        try (var transaction = FluidTransaction.open()) {
            assertFalse(fixture.access.putOne(stack(2), transaction));
            assertTrue(fixture.access.putOne(stack(1), transaction));
            transaction.commit();
        }
        assertEquals(stack(1), fixture.contents[0]);
        assertEquals(stack(63), fixture.contents[1]);
    }

    @Test void conflictingExternalWriteSurvivesWhileOtherOwnedWritesRollBack() {
        var fixture = new InventoryFixture();
        fixture.contents[0] = stack(63);
        var external = stack(12);
        external.getItemMeta().setDisplayName("external plugin");
        try (var transaction = FluidTransaction.open()) {
            assertTrue(fixture.access.put(stack(3), transaction));
            fixture.contents[1] = external;
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(stack(63), fixture.contents[0]);
        assertSame(external, fixture.contents[1]);
    }

    @Test void partlyFailingBukkitWriteStillRestoresTheWrittenSlot() {
        var fixture = new InventoryFixture();
        fixture.contents[0] = stack(63);
        fixture.failAfterWriting = 0;
        try (var transaction = FluidTransaction.open()) {
            assertThrows(IllegalStateException.class, () -> fixture.access.put(stack(3), transaction));
        }
        assertEquals(stack(63), fixture.contents[0]);
        assertNull(fixture.contents[1]);
    }

    @Test void crossingTickFailsCommitAndRestoresAllSlots() {
        var fixture = new InventoryFixture();
        fixture.contents[0] = stack(7);
        try (var transaction = FluidTransaction.open()) {
            assertTrue(fixture.access.take(0, stack(7), 3, transaction));
            fixture.owner.tick++;
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(stack(7), fixture.contents[0]);
    }

    @Test void ownerChecksRejectEvenPreviewAndDoNotMutate() {
        var fixture = new InventoryFixture();
        fixture.owner.available = false;
        assertThrows(StorageAccessException.class, () -> fixture.access.canPut(stack(1)));
        try (var transaction = FluidTransaction.open()) {
            assertThrows(StorageAccessException.class, () -> fixture.access.put(stack(1), transaction));
        }
        assertEquals(0, fixture.writes);
    }

    @Test void nestedConflictCannotBeAdoptedAndThenOverwrittenByOuterRollback() {
        var fixture = new InventoryFixture();
        fixture.contents[0] = stack(7);
        var external = stack(12);
        external.getItemMeta().setDisplayName("external after nested edit");
        try (var outer = FluidTransaction.open()) {
            assertTrue(fixture.access.take(0, stack(7), 2, outer));
            try (var nested = outer.openNested()) {
                assertTrue(fixture.access.putAt(0, stack(3), nested));
                fixture.contents[0] = external;
                assertThrows(StorageAccessException.class, nested::commit);
            }
            assertThrows(StorageAccessException.class, outer::commit);
        }
        assertSame(external, fixture.contents[0]);
    }

    @Test void nestedRollbackPreservesParentAndRootRollbackAllowsLaterReuse() {
        var fixture = new InventoryFixture();
        fixture.contents[0] = stack(7);
        try (var outer = FluidTransaction.open()) {
            assertTrue(fixture.access.take(0, stack(7), 2, outer));
            try (var nested = outer.openNested()) { assertTrue(fixture.access.putAt(0, stack(3), nested)); }
            assertEquals(stack(5), fixture.contents[0]);
            outer.commit();
        }
        try (var transaction = FluidTransaction.open()) { assertTrue(fixture.access.takeOne(0, stack(5), transaction)); }
        fixture.contents[0] = stack(12);
        try (var transaction = FluidTransaction.open()) {
            assertTrue(fixture.access.takeOne(0, stack(12), transaction));
            transaction.commit();
        }
        assertEquals(stack(11), fixture.contents[0]);
    }
}
