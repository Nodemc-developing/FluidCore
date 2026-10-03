/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TankMenuSettingsTest {
    @Test void ordinaryTankDoesNotRequireExternalAssets() {
        TankMenuSettings settings = TankMenuSettings.parse(ConfigSection.of("tank", Map.of()));
        assertEquals(TankMenuSettings.Theme.AUTO, settings.theme());
        assertEquals("", settings.backgroundImage());
        assertEquals("", settings.borderItem());
        assertEquals("", settings.fluidItemPrefix());
    }

    @Test void themedIdentifiersAreTypedAndIndependentOfCallerMap() {
        var menu = new LinkedHashMap<String, Object>();
        menu.put("layout", "jug"); menu.put("background-image", "ce:example:tank");
        menu.put("bucket-item", "example:bucket"); menu.put("progress-item-prefix", "example:progress_");
        menu.put("title-key", "container.example.tank"); menu.put("unrelated-metadata", Map.of("custom", true));
        TankMenuSettings settings = TankMenuSettings.fromMap(menu);
        menu.put("background-image", "example:changed");
        assertEquals("example:tank", settings.backgroundImage());
        assertEquals("example:bucket", settings.bucketItem());
        assertEquals("example:progress_", settings.progressItemPrefix());
        assertEquals("container.example.tank", settings.titleKey());
    }

    @Test void plainThemeIsExplicitWithoutRemovingSuppliedResourceMetadata() {
        TankMenuSettings settings = TankMenuSettings.fromMap(Map.of("theme", "plain", "background-image", "example:tank"));
        assertEquals(TankMenuSettings.Theme.PLAIN, settings.theme());
        assertEquals("example:tank", settings.backgroundImage());
    }

    @Test void malformedAndNullFieldsFailWithoutSilentlyEnablingDefaults() {
        for (Map<String, Object> fields : java.util.List.<Map<String, Object>>of(Map.of("layout", "other"), Map.of("theme", "other"),
                Map.of("background-image", "../foreign.png"), Map.of("background-image", "example:UPPER"),
                Map.of("title-key", "<image:example:tank>"), Map.of("border-item", 42),
                Map.of("title-offset", 0.5), Map.of("background-offset", true), Map.of("background-offset", 4097)))
            assertThrows(IllegalArgumentException.class, () -> TankMenuSettings.fromMap(fields), fields.toString());
        var nullField = new LinkedHashMap<String, Object>(); nullField.put("background-image", null);
        assertThrows(IllegalArgumentException.class, () -> TankMenuSettings.fromMap(nullField));
    }

    @Test void badMenuErrorsIncludeContentPath() {
        var invalid = ConfigSection.of("blocks.example:jug.behavior", Map.of("menu", "not-a-map"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TankMenuSettings.parse(invalid)).getMessage().contains("menu"));
        var malformed = ConfigSection.of("blocks.example:jug.behavior", Map.of("menu", Map.of("bucket-item", "invalid")));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TankMenuSettings.parse(malformed)).getMessage().contains("blocks.example:jug.behavior"));
    }

    @Test void exactIntegralStringOffsetsAndEmptyResourcesAreSupported() {
        TankMenuSettings settings = TankMenuSettings.fromMap(Map.of("background-offset", "-16", "title-offset", 0, "border-item", ""));
        assertEquals(-16, settings.backgroundOffset()); assertEquals(0, settings.titleOffset()); assertEquals("", settings.borderItem());
    }
}
