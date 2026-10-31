package com.ydxc20091.fluidcore.render;

import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.api.FluidVariant;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FluidRenderLayoutTest {
    private static FluidStack water(long amount) { return FluidStack.of(FluidVariant.of("minecraft:water"), amount); }
    private static FluidStack lava(long amount) { return FluidStack.of(FluidVariant.of("minecraft:lava"), amount); }

    @Test void layersFillBottomToTopAndSelectTheDominantFluidPerCell() {
        var layout = FluidRenderLayout.of(3, 2, List.of(water(500), lava(250)), 1000);
        assertArrayEquals(new int[]{1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1,-1,-1,-1}, layout.bands(0));
        assertArrayEquals(new int[16], layout.bands(1));
        assertEquals(1, layout.layerAt(0, 2));
        assertEquals(0, layout.layerAt(1, 0));
        assertFalse(layout.overflow());
    }
    @Test void arithmeticDoesNotOverflowWhenStacksTogetherExceedLongCapacity() {
        var layout = FluidRenderLayout.of(1, 6, List.of(water(Long.MAX_VALUE), lava(Long.MAX_VALUE)), Long.MAX_VALUE);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), layout.totalAmount());
        assertTrue(layout.overflow());
        for (int row = 0; row < layout.rows(); row++) assertEquals(0, layout.layerAt(row, 0));
    }
    @Test void emptyAndZeroCapacityLayoutsStayEmptyWithoutDiscardingInput() {
        var layout = FluidRenderLayout.of(1, 1, List.of(water(1000)), 0);
        assertEquals(-1, layout.layerAt(0, 0));
        assertTrue(layout.overflow());
        assertEquals(1000, layout.layers().get(0).amount());
        assertEquals(-1, FluidRenderLayout.of(1, 1, List.of(), 0).layerAt(0, 0));
    }
    @Test void tinyPositiveAmountsRemainVisibleAndReturnedBandsAreDetached() {
        var layout = FluidRenderLayout.of(1, 1, List.of(FluidStack.EMPTY, water(1)), Long.MAX_VALUE);
        assertEquals(1, layout.layerAt(0, 0));
        int[] bands = layout.bands(0); bands[0] = 55;
        assertEquals(1, layout.bands(0)[0]);
    }
    @Test void invalidDimensionsAndUnboundedLayerCountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FluidRenderLayout.of(10, 1, List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> FluidRenderLayout.of(1, 7, List.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> FluidRenderLayout.of(1, 1, List.of(), -1));
        assertThrows(IllegalArgumentException.class, () -> FluidRenderLayout.of(1, 1, Collections.nCopies(65, water(1)), 100));
        assertDoesNotThrow(() -> FluidRenderLayout.of(9, 6, Collections.nCopies(64, water(1)), 100));
    }
}
