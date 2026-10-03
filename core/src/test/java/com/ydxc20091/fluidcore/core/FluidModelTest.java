package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FluidModelTest {
    @Test void namespacedKeysValidateAndRoundTrip() {
        assertEquals("ydxc20091:crude_oil", FluidKey.of("ydxc20091:crude_oil").toString());
        assertThrows(IllegalArgumentException.class, () -> FluidKey.of("water"));
        assertThrows(IllegalArgumentException.class, () -> FluidKey.of("FluidCore:water"));
        assertThrows(IllegalArgumentException.class, () -> FluidKey.of("a:b:c"));
    }
    @Test void encodedComponentsAreDeeplyImmutable() {
        byte[] original = {1, 2, 3};
        var component = ComponentValue.of(original);
        original[0] = 9;
        component.bytes()[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, component.bytes());
        assertEquals(ComponentValue.of(new byte[]{1, 2, 3}), component);
        var map = new java.util.HashMap<FluidKey, ComponentValue>();
        map.put(FluidKey.of("test:temperature"), component);
        var variant = FluidVariant.of(FluidKey.of("minecraft:water"), map);
        map.clear();
        assertEquals(1, variant.components().size());
        assertThrows(UnsupportedOperationException.class, () -> variant.components().clear());
    }
    @Test void emptyIsCanonicalAndNegativeAmountsAreRejected() {
        assertSame(FluidStack.EMPTY, FluidStack.of(FluidVariant.of("minecraft:water"), 0));
        assertNull(new FluidStack(FluidVariant.of("minecraft:water"), 0).variant());
        assertThrows(IllegalArgumentException.class, () -> FluidStack.of(FluidVariant.of("minecraft:water"), -1));
        assertThrows(NullPointerException.class, () -> FluidStack.of(null, 1));
    }
    @Test void variantsIncludeComponentIdentity() {
        var plain = FluidVariant.of("minecraft:water");
        var warm = plain.withComponent(FluidKey.of("test:temperature"), ComponentValue.of(new byte[]{20}));
        assertNotEquals(plain, warm);
        assertEquals(Map.of(), plain.components());
    }
}
