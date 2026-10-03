/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FluidContentMenuDefaultsTest {
    @Test void loadingDefaultsPreserveExplicitValuesAndDoNotShareSourceDocuments() {
        Map<String, Object> extension = new LinkedHashMap<>(Map.of("notes", new ArrayList<>(List.of("keep"))));
        Map<String, Object> menu = new LinkedHashMap<>(Map.of("theme", "plain", "background-offset", 0, "x-data", extension));
        Map<String, Object> source = new LinkedHashMap<>(Map.of("capacity", 24000, "menu", menu));
        var resolved = FluidContentFactories.withMenuDefaults(ConfigSection.of("items/example:jug/behavior", source),
                Map.of("layout", "jug", "theme", "auto", "background-offset", -8, "border-item", "example:border"));
        var selected = resolved.getValue("menu").getAsMap();
        assertEquals("items/example:jug/behavior", resolved.path());
        assertEquals(24000, resolved.get("capacity"));
        assertEquals("plain", selected.get("theme"));
        assertEquals(0, selected.get("background-offset"));
        assertEquals("jug", selected.get("layout"));
        assertEquals("example:border", selected.get("border-item"));
        assertFalse(menu.containsKey("layout"));
        extension.put("notes", List.of("changed"));
        assertEquals(List.of("keep"), ((Map<?, ?>) selected.get("x-data")).get("notes"));
    }

    @Test void explicitNullAndUnknownFieldsRemainAvailableForTypedValidation() {
        Map<String, Object> menu = new LinkedHashMap<>();
        menu.put("border-item", null); menu.put("x-owner", "user");
        var resolved = FluidContentFactories.withMenuDefaults(ConfigSection.of("tank", Map.of("menu", menu)),
                Map.of("border-item", "example:border", "layout", "jug"));
        var selected = resolved.getValue("menu").getAsMap();
        assertTrue(selected.containsKey("border-item")); assertNull(selected.get("border-item"));
        assertEquals("user", selected.get("x-owner"));
    }

    @Test void aScalarMenuIsRejectedAtTheActualConfigurationPath() {
        var configuration = ConfigSection.of("tank/behavior", Map.of("menu", false));
        var error = assertThrows(IllegalArgumentException.class, () ->
                FluidContentFactories.withMenuDefaults(configuration, Map.of("layout", "jug")));
        assertTrue(error.getMessage().contains(configuration.assemblePath("menu")));
    }
}
