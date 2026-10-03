/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.bukkit.ContainerDefinition;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContainerSettingsTest {
    private static final FluidVariant WATER = FluidVariant.of("minecraft:water");
    private static final FluidVariant LAVA = FluidVariant.of("minecraft:lava");

    @Test void anOmittedWhitelistAllowsAllButExplicitEmptyWhitelistAllowsNone() {
        var registry = new FluidRegistry();
        assertTrue(parse(Map.of()).accepts(LAVA, registry));
        var empty = parse(Map.of("allowed-fluids", List.of()));
        assertFalse(empty.accepts(WATER, registry));
        assertFalse(empty.accepts(LAVA, registry));
        assertTrue(new ContainerDefinition(1000, Set.of(), FluidStack.EMPTY).accepts(WATER));
    }

    @Test void identifiersAndTagsAreCombinedAgainstCurrentRegistry() {
        var registry = new FluidRegistry();
        var tag = FluidKey.of("example:drinkable");
        registry.registerTag("test", tag, Set.of(WATER.fluid()));
        var definition = parse(Map.of("allowed-fluids", List.of("#example:drinkable", "minecraft:lava")));
        assertTrue(definition.accepts(WATER, registry));
        assertTrue(definition.accepts(LAVA, registry));
        assertFalse(definition.accepts(FluidVariant.of("minecraft:milk"), registry));
        assertFalse(definition.accepts(WATER));
        registry.replaceOwnedTags("test", Map.of(tag, Set.of(FluidKey.of("minecraft:milk"))));
        assertFalse(definition.accepts(WATER, registry));
        assertTrue(definition.accepts(FluidVariant.of("minecraft:milk"), registry));
    }

    @Test void tagOnlyInitialContentIsValidatedAfterRegistryPublication() {
        var definition = parse(Map.of("capacity", 2000, "default-amount", 1500, "default-fluid", "minecraft:water",
                "allowed-fluids", List.of("#example:drinkable")));
        var registry = new FluidRegistry();
        assertFalse(definition.accepts(definition.initialContent().variant(), registry));
        registry.registerTag("test", FluidKey.of("example:drinkable"), Set.of(WATER.fluid()));
        assertTrue(definition.accepts(definition.initialContent().variant(), registry));
        assertEquals(1500, definition.initialContent().amount());
    }

    @Test void defaultsMustFitCapacityAndDirectFilters() {
        for (Map<String, Object> fields : List.<Map<String, Object>>of(
                Map.of("capacity", 0), Map.of("capacity", -1), Map.of("capacity", 1.5),
                Map.of("default-amount", -1), Map.of("default-amount", 1),
                Map.of("default-fluid", "minecraft:water", "default-amount", 1001),
                Map.of("default-fluid", "minecraft:water", "default-amount", 1000, "allowed-fluids", List.of("minecraft:lava")),
                Map.of("default-fluid", "minecraft:water", "default-amount", 1000, "allowed-fluids", List.of()),
                Map.of("allowed-fluids", List.of("invalid")), Map.of("allowed-fluids", List.of("#")),
                Map.of("allowed-fluids", List.of("minecraft:water", "minecraft:water")),
                Map.of("allowed-fluids", List.of("#example:tag", "#example:tag")),
                Map.of("allowed-fluids", "minecraft:water"), Map.of("allowed-fluids", List.of(1))))
            assertThrows(IllegalArgumentException.class, () -> parse(fields), fields.toString());
    }

    @Test void legacySpellingIsExplicitAndConflictingSpellingsFail() {
        var definition = parse(Map.of("allowed_fluids", List.of("minecraft:water")));
        assertTrue(definition.accepts(WATER));
        assertFalse(definition.accepts(LAVA));
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("allowed-fluids", List.of(), "allowed_fluids", List.of())));
    }

    @Test void longCapacitiesArePreservedAndOverflowRejected() {
        var definition = parse(Map.of("capacity", Long.MAX_VALUE, "default-fluid", "minecraft:water", "default-amount", Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, definition.capacity());
        assertEquals(Long.MAX_VALUE, definition.initialContent().amount());
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("capacity", "9223372036854775808")));
    }

    private static ContainerDefinition parse(Map<String, Object> fields) {
        return ContainerSettings.parse(ConfigSection.of("test", fields));
    }
}
