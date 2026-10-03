/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class TankMenuLayoutTest {
    @Test void upperAndLowerSlotsHaveNoOverlapOrMissingPlayerSlots() {
        assertEquals(36, TankMenuLayout.SIZE); assertEquals(72, TankMenuLayout.TOTAL_SIZE);
        assertEquals(java.util.Set.of(2, 11, 13, 24, 29, 33), java.util.Set.of(TankMenuLayout.INPUT, TankMenuLayout.PROGRESS,
                TankMenuLayout.FLUID, TankMenuLayout.BUCKETS, TankMenuLayout.OUTPUT, TankMenuLayout.BOTTLES));
        var slots = new HashSet<Integer>();
        for (int raw = 36; raw < 72; raw++) assertTrue(slots.add(TankMenuLayout.playerStorageSlot(raw)));
        assertEquals(36, slots.size()); assertEquals(9, TankMenuLayout.playerStorageSlot(36)); assertEquals(0, TankMenuLayout.playerStorageSlot(63));
        assertThrows(IllegalArgumentException.class, () -> TankMenuLayout.playerStorageSlot(35));
        assertThrows(IllegalArgumentException.class, () -> TankMenuLayout.playerStorageSlot(72));
    }

    @Test void decorationsIncludeEveryNonStorageUpperSlot() {
        for (int raw = 0; raw < 72; raw++) assertEquals(raw < 36 && raw != 2 && raw != 29, TankMenuLayout.decoration(raw));
        assertFalse(TankMenuLayout.decoration(-999));
    }

    @Test void mixedDragHasOneAuthoritativeInputAndRealPlayerSlotMapping() {
        var targets = TankMenuLayout.dragTargets(java.util.List.of(36, 2, 63, 36));
        assertNotNull(targets); assertTrue(targets.includesInput()); assertEquals(java.util.List.of(9, 0), targets.playerSlots());
        var lowerOnly = TankMenuLayout.dragTargets(java.util.List.of(71, 62));
        assertNotNull(lowerOnly); assertFalse(lowerOnly.includesInput()); assertEquals(java.util.List.of(8, 35), lowerOnly.playerSlots());
    }

    @Test void outputOrAnyDecorationRejectsEntireDragBeforeAnyMutation() {
        for (int raw = 0; raw < 36; raw++) if (raw != 2) assertNull(TankMenuLayout.dragTargets(java.util.List.of(36, 2, raw)), Integer.toString(raw));
        assertNull(TankMenuLayout.dragTargets(java.util.List.of(2, 72))); assertNull(TankMenuLayout.dragTargets(java.util.List.of()));
    }

    @Test void progressHasExactCeilingStagesAtNormalAndLargeDurations() {
        assertEquals(0, TankMenuLayout.progressStage(10, 0)); assertEquals(0, TankMenuLayout.progressStage(0, 100));
        for (long total : new long[] {1, 24, 25, 100, 1200, Long.MAX_VALUE}) {
            for (int fraction = 0; fraction <= 100; fraction++) {
                long elapsed = BigInteger.valueOf(total).multiply(BigInteger.valueOf(fraction)).divide(BigInteger.valueOf(100)).longValueExact();
                int expected = elapsed <= 0 ? 0 : BigInteger.valueOf(elapsed).multiply(BigInteger.valueOf(24))
                        .add(BigInteger.valueOf(total - 1)).divide(BigInteger.valueOf(total)).intValueExact();
                assertEquals(expected, TankMenuLayout.progressStage(elapsed, total), elapsed + "/" + total);
            }
        }
        assertEquals(24, TankMenuLayout.progressStage(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test void bucketBottleIconsShowStoredAmountAndRetainAnEmptyIcon() {
        assertEquals(0, TankMenuLayout.units(999, 1000)); assertEquals(1, TankMenuLayout.units(1000, 1000));
        assertEquals(4, TankMenuLayout.units(1000, 250)); assertEquals(99, TankMenuLayout.units(Long.MAX_VALUE, 250));
        assertEquals(0, TankMenuLayout.units(-1, 250)); assertEquals(1, TankMenuLayout.displayAmount(0));
    }
}
