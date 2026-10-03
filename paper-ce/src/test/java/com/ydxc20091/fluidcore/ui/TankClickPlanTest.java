/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TankClickPlanTest {
    @Test void emptyCursorLeftTakesAllAndRightTakesRoundedUpHalf() {
        var input = new UiActionTestStack(7);
        var left = TankClickPlan.normal(input, null, false, 64);
        assertNull(left.slot()); assertEquals(new UiActionTestStack(7), left.cursor());
        var right = TankClickPlan.normal(input, null, true, 64);
        assertEquals(new UiActionTestStack(3), right.slot()); assertEquals(new UiActionTestStack(4), right.cursor());
        assertEquals(7, input.getAmount());
    }

    @Test void emptySlotRightDepositsOneAndLeftDepositsTheAvailableStack() {
        var cursor = new UiActionTestStack(7);
        var right = TankClickPlan.normal(null, cursor, true, 64);
        assertEquals(new UiActionTestStack(1), right.slot()); assertEquals(new UiActionTestStack(6), right.cursor());
        var left = TankClickPlan.normal(null, cursor, false, 64);
        assertEquals(new UiActionTestStack(7), left.slot()); assertNull(left.cursor());
        assertEquals(7, cursor.getAmount());
    }

    @Test void matchingStackOnlyConsumesActualFreeCapacity() {
        var cursor = new UiActionTestStack(7);
        var plan = TankClickPlan.normal(new UiActionTestStack(62), cursor, false, 64);
        assertEquals(new UiActionTestStack(64), plan.slot()); assertEquals(new UiActionTestStack(5), plan.cursor());
        assertNull(TankClickPlan.normal(new UiActionTestStack(64), cursor, true, 64));
    }

    @Test void differentMetadataSwapsWholeStacksInsteadOfMergingThem() {
        var input = new UiActionTestStack(5);
        var other = new UiActionTestStack(Material.STONE, 7, 64, "Different named input", new byte[]{1, 7, 3});
        var plan = TankClickPlan.normal(input, other, true, 64);
        assertEquals(other, plan.slot()); assertEquals(input, plan.cursor());
        assertNotSame(other, plan.slot()); assertNotSame(input, plan.cursor());
    }

    @Test void swapUsesTheNewCursorItemsLimitRatherThanTheOldCursorsLimit() {
        var input = new UiActionTestStack(64);
        var filledBucket = new UiActionTestStack(Material.WATER_BUCKET, 1, 1, "Bucket", new byte[]{5});
        var plan = TankClickPlan.normal(input, filledBucket, false, 64);
        assertEquals(filledBucket, plan.slot()); assertEquals(input, plan.cursor());
    }

    @Test void originalMetadataArraysRemainDetachedFromEveryPlannedResult() {
        var original = new UiActionTestStack(7);
        var plan = TankClickPlan.normal(original, null, true, 64);
        ((UiActionTestStack) plan.cursor()).payload[0] = 99;
        assertArrayEquals(new byte[]{1, 7, -2}, original.payload);
        assertArrayEquals(new byte[]{1, 7, -2}, ((UiActionTestStack) plan.slot()).payload);
    }

    @Test void explicitSwapRejectsAnOversizedIncomingStackAndDoesNotMutateSources() {
        var original = new UiActionTestStack(2);
        var incoming = new UiActionTestStack(65);
        assertNull(TankClickPlan.swap(original, incoming, 64, 64));
        assertEquals(2, original.getAmount()); assertEquals(65, incoming.getAmount());
        assertNull(TankClickPlan.swap(null, null, 64, 64));
    }

    @Test void shiftToInputTakesAsMuchAsFitsAndNeverMatchesAnotherMetadataVariant() {
        assertEquals(2, TankClickPlan.inputFit(new UiActionTestStack(62), new UiActionTestStack(7)));
        assertEquals(64, TankClickPlan.inputFit(null, new UiActionTestStack(130)));
        var wrong = new UiActionTestStack(Material.STONE, 7, 64, "Other", new byte[]{1});
        assertEquals(0, TankClickPlan.inputFit(new UiActionTestStack(7), wrong));
        assertEquals(0, TankClickPlan.inputFit(new UiActionTestStack(64), new UiActionTestStack(7)));
    }

    @Test void shiftFromInputOnlyCountsStorageCapacityForCompatibleItems() {
        var wrong = new UiActionTestStack(Material.STONE, 12, 64, "Other", new byte[]{9});
        ItemStack[] full = {new UiActionTestStack(62), wrong, new UiActionTestStack(63)};
        assertEquals(3, TankClickPlan.storageFit(new UiActionTestStack(9), full, 64));
        assertEquals(9, TankClickPlan.storageFit(new UiActionTestStack(9), new ItemStack[]{null}, 64));
    }

    @Test void evenDragSplitsAcrossAllDestinationsAndLeavesTheRemainderOnRealCursor() {
        var carried = new UiActionTestStack(11);
        var plan = TankClickPlan.drag(carried, Arrays.asList(null, null, null), List.of(64, 64, 64), false);
        assertEquals(List.of(new UiActionTestStack(3), new UiActionTestStack(3), new UiActionTestStack(3)), plan.slots());
        assertEquals(new UiActionTestStack(2), plan.cursor()); assertEquals(9, plan.moved());
        assertEquals(11, carried.getAmount());
    }

    @Test void evenDragClampsEachShareWithoutRedistributingItsOverflow() {
        var plan = TankClickPlan.drag(new UiActionTestStack(12), Arrays.asList(new UiActionTestStack(63), null, null), List.of(64, 64, 64), false);
        assertEquals(List.of(new UiActionTestStack(64), new UiActionTestStack(4), new UiActionTestStack(4)), plan.slots());
        assertEquals(new UiActionTestStack(3), plan.cursor()); assertEquals(9, plan.moved());
    }

    @Test void singleDragDepositsAtMostOnePerDestinationAndNeverInventsAdditionalItems() {
        var plan = TankClickPlan.drag(new UiActionTestStack(2), Arrays.asList(null, null, null), List.of(64, 64, 64), true);
        assertEquals(new UiActionTestStack(1), plan.slots().get(0));
        assertEquals(new UiActionTestStack(1), plan.slots().get(1));
        assertNull(plan.slots().get(2)); assertNull(plan.cursor()); assertEquals(2, plan.moved());
    }

    @Test void dragLeavesIncompatibleAndFullDestinationsUnchanged() {
        var foreign = new UiActionTestStack(Material.STONE, 1, 64, "Foreign metadata", new byte[]{9});
        var plan = TankClickPlan.drag(new UiActionTestStack(4), Arrays.asList(foreign, new UiActionTestStack(64), null), List.of(64, 64, 64), false);
        assertEquals(foreign, plan.slots().get(0)); assertEquals(new UiActionTestStack(64), plan.slots().get(1));
        assertEquals(new UiActionTestStack(4), plan.slots().get(2)); assertNull(plan.cursor());
    }

    @Test void dragCannotProceedWithZeroShareOrNoCompatibleSpace() {
        assertNull(TankClickPlan.drag(new UiActionTestStack(1), Arrays.asList(null, null), List.of(64, 64), false));
        assertNull(TankClickPlan.drag(new UiActionTestStack(1), List.of(new UiActionTestStack(64)), List.of(64), true));
        assertNull(TankClickPlan.drag(null, Arrays.asList((ItemStack) null), List.of(64), true));
    }

    @Test void dragKeepsOpaqueMetadataOnEveryDestinationAndRetainedCursor() {
        var source = new UiActionTestStack(7);
        var plan = TankClickPlan.drag(source, Arrays.asList(null, null), List.of(64, 64), false);
        for (ItemStack value : plan.slots()) assertTrue(value.isSimilar(source));
        assertTrue(plan.cursor().isSimilar(source));
        ((UiActionTestStack) plan.slots().getFirst()).payload[0] = 99;
        assertArrayEquals(new byte[]{1, 7, -2}, source.payload);
        assertArrayEquals(new byte[]{1, 7, -2}, ((UiActionTestStack) plan.slots().get(1)).payload);
        assertArrayEquals(new byte[]{1, 7, -2}, ((UiActionTestStack) plan.cursor()).payload);
    }
}
