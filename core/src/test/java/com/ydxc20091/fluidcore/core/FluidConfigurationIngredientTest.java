package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FluidConfigurationIngredientTest {
    private final FluidRegistry registry = new FluidRegistry();
    private final ComponentType<Integer> batch = ComponentType.of("test:batch", ComponentCodecs.INTEGER);
    FluidConfigurationIngredientTest() { registry.registerComponentCodec("test", batch.key(), batch.codec()); }

    @Test void realCodecValuesMatchComponentsAndExactModeRejectsAdditionalIdentity() {
        var predicate = FluidIngredient.parseConfiguration(Map.of("id", "minecraft:water", "components", Map.of("test:batch", 7)), registry, 250);
        var matching = FluidVariant.of(FluidKey.of("minecraft:water"), Map.of(batch.key(), batch.encode(7)));
        var other = FluidVariant.of(FluidKey.of("minecraft:water"), Map.of(batch.key(), batch.encode(8)));
        assertTrue(predicate.matches(FluidStack.of(matching, 250)));
        assertFalse(predicate.matches(FluidStack.of(matching, 249)));
        assertFalse(predicate.matches(FluidStack.of(other, 250)));
        var exact = FluidIngredient.parseConfiguration(Map.of("tag", "fluidcore:water", "components", Map.of("test:batch", 7), "exact-components", true), registry, 1);
        var extra = FluidVariant.of(matching.fluid(), Map.of(batch.key(), batch.encode(7), FluidKey.of("test:extra"), ComponentValue.of(new byte[]{1})));
        assertTrue(exact.matches(matching)); assertFalse(exact.matches(extra));
        assertTrue(predicate.matches(extra));
    }
    @Test void alternativesAndMetadataDoNotWeakenConditions() {
        var predicate = FluidIngredient.parseConfiguration(Map.of("any-of", List.of("minecraft:milk", Map.of("fluid", "minecraft:honey")), "x-ui", "retained"), registry, 250);
        assertTrue(predicate.matches(FluidStack.of(FluidVariant.of("minecraft:honey"), 250)));
        assertFalse(predicate.matches(FluidStack.of(FluidVariant.of("minecraft:water"), 250)));
    }
    @Test void unknownSemanticFieldsUnknownCodecsWrongTypesAndInvalidBytesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> FluidIngredient.parseConfiguration(Map.of("fluid", "minecraft:water", "temperature", 12), registry, 1));
        assertThrows(IllegalArgumentException.class, () -> FluidIngredient.parseConfiguration(Map.of("fluid", "minecraft:water", "components", Map.of("missing:codec", 1)), registry, 1));
        assertThrows(IllegalArgumentException.class, () -> FluidIngredient.parseConfiguration(Map.of("fluid", "minecraft:water", "components", Map.of("test:batch", "wrong")), registry, 1));
        assertThrows(IllegalArgumentException.class, () -> FluidIngredient.parseConfiguration(Map.of("fluid", "minecraft:water", "components", Map.of("test:batch", ComponentValue.of(new byte[]{1}))), registry, 1));
    }
}
