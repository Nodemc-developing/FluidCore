/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import com.ydxc20091.fluidcore.api.FluidKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TankMenuThemeTest {
    @Test void missingBackgroundRetainsReadableUprightTitle() {
        Component title = TankMenuTheme.composeTitle(null, Component.text("储罐").decorate(TextDecoration.ITALIC));
        assertEquals(NamedTextColor.GOLD, title.color()); assertEquals(TextDecoration.State.FALSE, title.decoration(TextDecoration.ITALIC));
    }

    @Test void externalGlyphFontAndColorRemainIntact() {
        Component image = Component.text("\ue800", NamedTextColor.WHITE).font(Key.key("example:gui"));
        Component title = TankMenuTheme.composeTitle(image, Component.text("储罐"));
        assertEquals(image.font(), title.children().getFirst().font()); assertEquals(NamedTextColor.WHITE, title.children().getFirst().color());
        assertEquals(TextDecoration.State.FALSE, title.children().getFirst().decoration(TextDecoration.ITALIC));
        assertEquals("\ue800", ((net.kyori.adventure.text.TextComponent) title.children().getFirst()).content());
    }

    @Test void fluidTextureMetadataResolvesOnlyLoadedItemIdentifiers() {
        List<String> candidates = TankMenuTheme.fluidCandidates("example:fluid_", "content:drink/milk", FluidKey.of("minecraft:milk"));
        assertEquals(List.of("example:fluid_content_drink_milk", "example:fluid_drink_milk", "example:fluid_minecraft_milk", "example:fluid_milk"), candidates);
        assertEquals(List.of(), TankMenuTheme.fluidCandidates("", "milk", FluidKey.of("minecraft:milk")));
    }

    @Test void anUnknownFluidIsNeverPresentedAsWater() {
        List<String> candidates = TankMenuTheme.fluidCandidates("example:fluid_", "", FluidKey.of("custom:unmapped"));
        assertEquals(List.of("example:fluid_custom_unmapped", "example:fluid_unmapped"), candidates);
        assertFalse(candidates.contains("example:fluid_water"));
    }

    @Test void textureTraversalOrMiniMessageIsNeverUsedAsAnAssetPath() {
        for (String invalid : List.of("<image:example:foreign>", "../../foreign.png", "E:/outside.png")) {
            List<String> candidates = TankMenuTheme.fluidCandidates("example:fluid_", invalid, FluidKey.of("minecraft:water"));
            assertTrue(candidates.stream().allMatch(candidate -> candidate.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")));
            assertFalse(candidates.stream().anyMatch(candidate -> candidate.contains("..")));
        }
    }
}
