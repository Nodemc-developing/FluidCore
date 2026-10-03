/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.api.FluidVariant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidTankVisualStateTest {
    private static FluidTankVisualState water(long amount, long capacity) {
        return FluidTankVisualState.of(FluidStack.of(FluidVariant.of("minecraft:water"), amount), capacity);
    }

    @Test void emptyPartialAndFullContentsHaveVisibleLevelsWithoutChangingTheAmount() {
        assertEquals(new FluidTankVisualState(0, "empty"), water(0, 16000));
        assertEquals(new FluidTankVisualState(1, "water"), water(1, 16000));
        assertEquals(water(375, 16000), water(1000, 16000));
        assertEquals(new FluidTankVisualState(2, "water"), water(1001, 16000));
        assertEquals(new FluidTankVisualState(16, "water"), water(16000, 16000));
        assertEquals(water(16000, 16000), water(20000, 16000));
    }

    @Test void longAmountsAndCapacitiesNeverOverflowIntoAnEmptyOrNegativeLevel() {
        assertEquals(16, water(Long.MAX_VALUE, Long.MAX_VALUE).level());
        assertEquals(16, water(Long.MAX_VALUE - 1, Long.MAX_VALUE).level());
        assertEquals(8, water(Long.MAX_VALUE / 2, Long.MAX_VALUE).level());
        assertEquals(9, water(Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE).level());
        assertEquals(1, water(1, Long.MAX_VALUE).level());
    }

    @Test void supportedAndCustomFluidKindsStayDistinct() {
        for (String kind : new String[]{"milk", "lava", "honey"})
            assertEquals(kind, FluidTankVisualState.of(FluidStack.of(FluidVariant.of("minecraft:" + kind), 1000), 16000).kind());
        assertEquals("other", FluidTankVisualState.of(FluidStack.of(FluidVariant.of("example:syrup"), 1000), 16000).kind());
        assertThrows(IllegalArgumentException.class, () -> water(1, 0));
    }
}
