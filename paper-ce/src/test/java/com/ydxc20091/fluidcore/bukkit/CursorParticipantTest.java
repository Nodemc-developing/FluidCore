/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.api.StorageAccessException;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import static com.ydxc20091.fluidcore.bukkit.ParticipantTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class CursorParticipantTest {
    @Test void currentReturnsDetachedNamesAndPersistentByteArrays() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(5);
        var detached = fixture.access.current();
        detached.setAmount(1);
        detached.getItemMeta().setDisplayName("detached only");
        detached.getItemMeta().getPersistentDataContainer().set(FOREIGN, PersistentDataType.BYTE_ARRAY, new byte[]{9});
        assertEquals(stack(5), fixture.cursor);
        assertEquals(0, fixture.writes);
    }

    @Test void wholeStackInsertionCommitsOnceAndPreservesMetadata() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(61);
        var original = stack(3);
        assertTrue(fixture.access.canPut(original));
        try (var transaction = FluidTransaction.open()) {
            assertTrue(fixture.access.put(original, transaction));
            transaction.commit();
            assertThrows(IllegalStateException.class, transaction::commit);
        }
        assertEquals(stack(64), fixture.cursor);
        assertEquals(stack(3), original);
        assertEquals(1, fixture.writes);
        original.getItemMeta().setDisplayName("caller later changed");
        assertEquals("§6Named container", fixture.cursor.getItemMeta().getDisplayName());
    }

    @Test void fullOrIncompatibleCursorRejectsBeforeWritingAnyPart() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(63);
        assertFalse(fixture.access.canPut(stack(2)));
        var incompatible = stack(1);
        incompatible.getItemMeta().getPersistentDataContainer().set(IDENTIFIER, PersistentDataType.STRING, "other:item");
        try (var transaction = FluidTransaction.open()) {
            assertFalse(fixture.access.put(stack(2), transaction));
            assertFalse(fixture.access.put(incompatible, transaction));
            transaction.commit();
        }
        assertEquals(stack(63), fixture.cursor);
        assertEquals(0, fixture.writes);
        fixture.cursor = null;
        assertFalse(fixture.access.canPut(stack(65)));
    }

    @Test void countedTakeAndUncommittedRollbackConserveBothAmountAndMetadata() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(9);
        try (var transaction = FluidTransaction.open()) {
            var taken = fixture.access.take(4, transaction);
            assertEquals(stack(4), taken);
            assertEquals(stack(5), fixture.cursor);
            taken.getItemMeta().setDisplayName("detached taken stack");
            assertEquals("§6Named container", fixture.cursor.getItemMeta().getDisplayName());
        }
        assertEquals(stack(9), fixture.cursor);
    }

    @Test void takeClampsToAvailableItemsAndTreatsInvalidMaximumAsNoOp() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(3);
        try (var transaction = FluidTransaction.open()) {
            assertNull(fixture.access.take(0, transaction));
            assertNull(fixture.access.take(-1, transaction));
            assertEquals(stack(3), fixture.access.take(64, transaction));
            assertNull(fixture.access.current());
            transaction.commit();
        }
        assertNull(fixture.cursor);
        assertEquals(1, fixture.writes);
    }

    @Test void replacementRequiresTheExactOriginalAmountAndMetadata() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(5);
        var wrongMetadata = stack(5);
        wrongMetadata.getItemMeta().setDisplayName("same material, wrong metadata");
        var replacement = new ItemContainerTransfersTest.DataStack(Material.BUCKET, 1);
        try (var transaction = FluidTransaction.open()) {
            assertFalse(fixture.access.replace(stack(4), replacement, transaction));
            assertFalse(fixture.access.replace(wrongMetadata, replacement, transaction));
            assertFalse(fixture.access.replace(stack(5), stack(65), transaction));
            assertTrue(fixture.access.replace(stack(5), replacement, transaction));
            transaction.commit();
        }
        assertEquals(replacement, fixture.cursor);
        assertNotSame(replacement, fixture.cursor);
        assertEquals(1, fixture.writes);
    }

    @Test void allAirValuesNormalizeToEmptyAndCanBeReplacedByAnExactEmptyExpectation() {
        for (var air : new Material[]{Material.AIR, Material.CAVE_AIR, Material.VOID_AIR}) {
            var fixture = new CursorFixture();
            fixture.cursor = new ItemContainerTransfersTest.DataStack(air, 1);
            assertNull(fixture.access.current());
            try (var transaction = FluidTransaction.open()) {
                assertTrue(fixture.access.replace(null, stack(2), transaction));
            }
            assertNull(fixture.access.current());
        }
    }

    @Test void zeroAmountAlsoNormalizesToEmptyWithoutChangingRealItemMetadata() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(0);
        assertNull(fixture.access.current());
        try (var transaction = FluidTransaction.open()) {
            assertTrue(fixture.access.put(stack(2), transaction));
            assertTrue(fixture.access.replace(stack(2), null, transaction));
            transaction.commit();
        }
        assertNull(fixture.cursor);
    }

    @Test void conflictingExternalCursorSurvivesValidationFailureAndRollback() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(7);
        var external = stack(12);
        external.getItemMeta().setDisplayName("another plugin owns this value");
        try (var transaction = FluidTransaction.open()) {
            assertEquals(stack(2), fixture.access.take(2, transaction));
            fixture.cursor = external;
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertSame(external, fixture.cursor);
    }

    @Test void externalReplacementOfAnEmptyExpectedCursorIsAlsoProtected() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(1);
        try (var transaction = FluidTransaction.open()) {
            assertEquals(stack(1), fixture.access.take(1, transaction));
            fixture.cursor = stack(3);
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(stack(3), fixture.cursor);
    }

    @Test void partlyFailingSetterRestoresTheActualCursor() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(7);
        fixture.failAfterWrite = true;
        try (var transaction = FluidTransaction.open()) {
            assertThrows(IllegalStateException.class, () -> fixture.access.take(2, transaction));
        }
        assertEquals(stack(7), fixture.cursor);
    }

    @Test void crossingTickPreventsCommitAndRestoresTheCursor() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(7);
        try (var transaction = FluidTransaction.open()) {
            assertEquals(stack(2), fixture.access.take(2, transaction));
            fixture.owner.tick++;
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(stack(7), fixture.cursor);
    }

    @Test void ownershipIsCheckedBeforeTouchingThePlayerCursor() {
        var fixture = new CursorFixture();
        fixture.owner.available = false;
        assertThrows(StorageAccessException.class, fixture.access::current);
        assertThrows(StorageAccessException.class, () -> fixture.access.canPut(stack(1)));
        try (var transaction = FluidTransaction.open()) {
            assertThrows(StorageAccessException.class, () -> fixture.access.replace(null, stack(1), transaction));
        }
        assertEquals(0, fixture.reads);
        assertEquals(0, fixture.writes);
    }

    @Test void nestedRollbackRestoresParentAndOuterCommitKeepsOnlyItsEdit() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(7);
        try (var outer = FluidTransaction.open()) {
            assertEquals(stack(2), fixture.access.take(2, outer));
            try (var nested = outer.openNested()) { assertTrue(fixture.access.put(stack(3), nested)); }
            assertEquals(stack(5), fixture.cursor);
            outer.commit();
        }
        assertEquals(stack(5), fixture.cursor);
    }

    @Test void nestedConflictStaysUnownedUntilTheRootTransactionCloses() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(7);
        var external = stack(12);
        external.getItemMeta().setDisplayName("external after nested edit");
        try (var outer = FluidTransaction.open()) {
            assertEquals(stack(2), fixture.access.take(2, outer));
            try (var nested = outer.openNested()) {
                assertTrue(fixture.access.put(stack(3), nested));
                fixture.cursor = external;
                assertThrows(StorageAccessException.class, nested::commit);
            }
            assertThrows(StorageAccessException.class, outer::commit);
        }
        assertSame(external, fixture.cursor);
    }

    @Test void committedNestedEditStillRollsBackWhenTheRootTransactionDoesNotCommit() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(7);
        try (var outer = FluidTransaction.open()) {
            assertEquals(stack(2), fixture.access.take(2, outer));
            try (var nested = outer.openNested()) {
                assertTrue(fixture.access.put(stack(3), nested));
                nested.commit();
            }
        }
        assertEquals(stack(7), fixture.cursor);
    }

    @Test void rolledBackParticipantCanBeReusedAfterAnIndependentCursorChange() {
        var fixture = new CursorFixture();
        fixture.cursor = stack(7);
        try (var transaction = FluidTransaction.open()) { fixture.access.take(2, transaction); }
        fixture.cursor = stack(12);
        try (var transaction = FluidTransaction.open()) {
            assertEquals(stack(2), fixture.access.take(2, transaction));
            transaction.commit();
        }
        assertEquals(stack(10), fixture.cursor);
    }

    @Test void cursorAndMultiSlotInventoryAreOneAtomicLocalTransaction() {
        var cursor = new CursorFixture();
        var inventory = new InventoryFixture();
        cursor.cursor = stack(8);
        inventory.contents[0] = stack(63);
        try (var transaction = FluidTransaction.open()) {
            assertTrue(inventory.access.put(cursor.access.take(4, transaction), transaction));
            assertEquals(stack(4), cursor.cursor);
            assertEquals(stack(64), inventory.contents[0]);
            assertEquals(stack(3), inventory.contents[1]);
        }
        assertEquals(stack(8), cursor.cursor);
        assertEquals(stack(63), inventory.contents[0]);
        assertNull(inventory.contents[1]);
    }
}
