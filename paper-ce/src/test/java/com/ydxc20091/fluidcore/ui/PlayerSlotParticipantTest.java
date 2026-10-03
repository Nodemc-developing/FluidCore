/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.api.StorageContext;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSlotParticipantTest {
    @Test void explicitOffhandReplacementCommitsOnlyOnceWithDetachedMetadata() {
        var fixture = new Fixture();
        fixture.items[40] = new UiActionTestStack(5);
        var participant = fixture.slot(40);
        var replacement = new UiActionTestStack(3);
        try (var transaction = FluidTransaction.open()) {
            assertTrue(participant.replace(new UiActionTestStack(5), replacement, transaction));
            transaction.commit();
            assertThrows(IllegalStateException.class, transaction::commit);
        }
        replacement.payload[0] = 99;
        assertEquals(new UiActionTestStack(3), fixture.items[40]);
        assertEquals(1, fixture.writes);
    }

    @Test void currentReturnsACloneOfTheWholeSelectedItem() {
        var fixture = new Fixture();
        fixture.items[4] = new UiActionTestStack(5);
        var copy = (UiActionTestStack) fixture.slot(4).current();
        copy.setAmount(1); copy.payload[0] = 99;
        assertEquals(new UiActionTestStack(5), fixture.items[4]);
    }

    @Test void staleExpectedValueAndOversizedReplacementNeverEditTheSlot() {
        var fixture = new Fixture();
        fixture.items[4] = new UiActionTestStack(5);
        var participant = fixture.slot(4);
        var wrong = new UiActionTestStack(5); wrong.payload[0] = 99;
        try (var transaction = FluidTransaction.open()) {
            assertFalse(participant.replace(wrong, new UiActionTestStack(1), transaction));
            assertFalse(participant.replace(new UiActionTestStack(5), new UiActionTestStack(65), transaction));
            transaction.commit();
        }
        assertEquals(new UiActionTestStack(5), fixture.items[4]);
        assertEquals(0, fixture.writes);
    }

    @Test void rejectedSecondHalfOfSwapRollsBackTheFirstExactReplacement() {
        var fixture = new Fixture();
        fixture.items[4] = new UiActionTestStack(5);
        fixture.items[40] = new UiActionTestStack(3);
        try (var transaction = FluidTransaction.open()) {
            assertTrue(fixture.slot(4).replace(new UiActionTestStack(5), new UiActionTestStack(3), transaction));
            assertFalse(fixture.slot(40).replace(new UiActionTestStack(2), new UiActionTestStack(5), transaction));
        }
        assertEquals(new UiActionTestStack(5), fixture.items[4]);
        assertEquals(new UiActionTestStack(3), fixture.items[40]);
    }

    @Test void foreignReplacementSurvivesValidationFailureAndRollback() {
        var fixture = new Fixture();
        fixture.items[4] = new UiActionTestStack(5);
        var participant = fixture.slot(4);
        var foreign = new UiActionTestStack(12); foreign.payload[0] = 99;
        try (var transaction = FluidTransaction.open()) {
            assertTrue(participant.replace(new UiActionTestStack(5), new UiActionTestStack(3), transaction));
            fixture.items[4] = foreign;
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertSame(foreign, fixture.items[4]);
    }

    @Test void writeFailureAfterMutationStillRollsBackTheSelectedSlot() {
        var fixture = new Fixture();
        fixture.items[4] = new UiActionTestStack(5);
        var participant = fixture.slot(4);
        fixture.failAfterWrite = true;
        try (var transaction = FluidTransaction.open()) {
            assertThrows(IllegalStateException.class, () -> participant.replace(new UiActionTestStack(5), new UiActionTestStack(3), transaction));
        }
        assertEquals(new UiActionTestStack(5), fixture.items[4]);
    }

    @Test void crossingTickRejectsCommitAndRestoresTheSource() {
        var fixture = new Fixture();
        fixture.items[4] = new UiActionTestStack(5);
        var participant = fixture.slot(4);
        try (var transaction = FluidTransaction.open()) {
            assertTrue(participant.replace(new UiActionTestStack(5), new UiActionTestStack(3), transaction));
            fixture.tick++;
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(new UiActionTestStack(5), fixture.items[4]);
    }

    @Test void nestedConflictRemainsProtectedDuringOuterRollback() {
        var fixture = new Fixture();
        fixture.items[4] = new UiActionTestStack(5);
        var participant = fixture.slot(4);
        var foreign = new UiActionTestStack(12); foreign.payload[0] = 99;
        try (var outer = FluidTransaction.open()) {
            assertTrue(participant.replace(new UiActionTestStack(5), new UiActionTestStack(3), outer));
            try (var nested = outer.openNested()) {
                assertTrue(participant.replace(new UiActionTestStack(3), new UiActionTestStack(1), nested));
                fixture.items[4] = foreign;
                assertThrows(StorageAccessException.class, nested::commit);
            }
            assertThrows(StorageAccessException.class, outer::commit);
        }
        assertSame(foreign, fixture.items[4]);
    }

    @Test void armorSlotsCannotBeAccidentallyAddressedAsStorage() {
        var fixture = new Fixture();
        for (int slot : new int[]{-1, 36, 37, 38, 39, 41}) assertThrows(IllegalArgumentException.class, () -> fixture.slot(slot));
    }

    @Test void ownerCheckRunsBeforeAnyPlayerInventoryRead() {
        var fixture = new Fixture();
        var participant = fixture.slot(4);
        fixture.owned = false;
        assertThrows(StorageAccessException.class, participant::current);
        try (var transaction = FluidTransaction.open()) {
            assertThrows(StorageAccessException.class, () -> participant.replace(null, new UiActionTestStack(1), transaction));
        }
        assertEquals(0, fixture.reads); assertEquals(0, fixture.writes);
    }

    private static final class Fixture {
        final ItemStack[] items = new ItemStack[41];
        int reads, writes;
        long tick = 7;
        boolean owned = true, failAfterWrite;
        final StorageContext context = new StorageContext() {
            @Override public void checkAccess() { if (!owned) throw new StorageAccessException("Not owned"); }
            @Override public long tick() { checkAccess(); return tick; }
        };
        final PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getItem" -> { reads++; yield items[(int) args[0]]; }
                    case "getMaxStackSize" -> 64;
                    case "setItem" -> {
                        items[(int) args[0]] = (ItemStack) args[1]; writes++;
                        if (failAfterWrite) { failAfterWrite = false; throw new IllegalStateException("Injected write failure"); }
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> method.getName().equals("getInventory") ? inventory : null);
        PlayerSlotParticipant slot(int slot) { return new PlayerSlotParticipant(player, slot, context); }
    }
}
