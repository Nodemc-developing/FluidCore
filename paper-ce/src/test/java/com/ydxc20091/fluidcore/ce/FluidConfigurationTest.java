/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.core.FluidDefinition;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class FluidConfigurationTest {
    private static final FluidKey HOT = FluidKey.of("example:hot_water");
    private static final FluidKey DRINKABLE = FluidKey.of("example:drinkable");
    private static final FluidKey NESTED = FluidKey.of("example:nested");

    @Test void standardPropertiesAndSoundEventsDecodeIntoTypedModel() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("display-name", "Hot water");
        fields.put("tags", List.of("example:processed"));
        fields.put("density", -990);
        fields.put("viscosity", 550);
        fields.put("temperature", 350);
        fields.put("light-level", 7);
        fields.put("color", "#804AAEFF");
        fields.put("texture", "minecraft:block/water_still");
        fields.put("rarity", "uncommon");
        fields.put("bucket-item", "example:hot_bucket");
        fields.put("sounds", Map.of("container-fill", "minecraft:item.bucket.fill", "container-empty", "minecraft:item.bucket.empty",
                "vaporize", "minecraft:block.fire.extinguish", "cauldron-drip", "minecraft:block.pointed_dripstone.drip_water"));
        FluidDefinition definition = FluidConfigParser.decode(HOT, section(fields));
        assertEquals("Hot water", definition.displayName());
        assertEquals(Set.of(FluidKey.of("example:processed")), definition.tags());
        assertEquals(-990, definition.properties().density());
        assertEquals(550, definition.properties().viscosity());
        assertEquals(350, definition.properties().temperature());
        assertEquals(7, definition.properties().lightLevel());
        assertEquals(0x804AAEFF, definition.properties().color().orElseThrow());
        assertEquals("minecraft:block/water_still", definition.properties().texture().orElseThrow());
        assertEquals(FluidRarity.UNCOMMON, definition.properties().rarity());
        assertEquals(FluidKey.of("example:hot_bucket"), definition.properties().bucketItem().orElseThrow());
        assertEquals(4, definition.properties().sounds().size());
        assertEquals(FluidKey.of("minecraft:item.bucket.fill"), definition.properties().sound(FluidSound.CONTAINER_FILL).orElseThrow());
    }

    @Test void omittedFieldsUseStandardDefaults() {
        FluidDefinition definition = FluidConfigParser.decode(HOT, section(Map.of()));
        assertEquals(HOT.toString(), definition.displayName());
        assertEquals(FluidProperties.defaults(), definition.properties());
    }

    @Test void colorAcceptsOpaqueRgbExplicitArgbAndPackedValuesWithoutTruncation() {
        assertEquals(0xff4aaeff, FluidConfigParser.color("#4AAEFF"));
        assertEquals(0x804aaeff, FluidConfigParser.color("0x804AAEFF"));
        assertEquals(0xffffffff, FluidConfigParser.color(4294967295L));
        assertEquals(-1, FluidConfigParser.color(-1));
        for (Object invalid : new Object[]{"#abc", "#GG0000", 4294967296L, -2147483649L, 1.5, true})
            assertThrows(IllegalArgumentException.class, () -> FluidConfigParser.color(invalid), invalid.toString());
    }

    @Test void invalidPhysicalValuesAndIdentifiersFailStrictly() {
        for (Map<String, Object> fields : List.<Map<String, Object>>of(
                Map.of("light-level", 16), Map.of("light-level", -1), Map.of("temperature", -1),
                Map.of("viscosity", -1), Map.of("density", 2147483648L), Map.of("temperature", 300.25),
                Map.of("temperature", true), Map.of("texture", "  "), Map.of("rarity", "legendary"),
                Map.of("bucket-item", "missing_namespace"), Map.of("tags", List.of("example:tag", "example:tag")),
                Map.of("sounds", Map.of("fill", "minecraft:item.bucket.fill", "container-fill", "minecraft:item.bucket.fill")),
                Map.of("sounds", Map.of("unknown-event", "minecraft:item.bucket.fill"))))
            assertThrows(IllegalArgumentException.class, () -> FluidConfigParser.decode(HOT, section(fields)), fields.toString());
    }

    @Test void textureMetadataAcceptsPackRelativeNamesWithoutResolvingFiles() {
        assertEquals("apple_juice", FluidConfigParser.decode(HOT, section(Map.of("texture", "apple_juice"))).properties().texture().orElseThrow());
        assertEquals("minecraft:block/water_still", FluidConfigParser.decode(HOT, section(Map.of("texture", "minecraft:block/water_still"))).properties().texture().orElseThrow());
    }

    @Test void loadingStagesHaveActualParserDependenciesAndRegistryPublicationBarrier() {
        var registry = new FluidRegistry();
        var parser = parser(registry);
        assertEquals(FluidCoreLoadingStages.FLUIDS, parser.loadingStage());
        assertEquals(FluidCoreLoadingStages.FLUID_TAGS, parser.tagsParser().loadingStage());
        assertEquals(List.of(FluidCoreLoadingStages.FLUIDS), parser.tagsParser().dependencies());
        assertEquals(FluidCoreLoadingStages.REGISTRY_READY, parser.readyParser().loadingStage());
        assertEquals(List.of(FluidCoreLoadingStages.FLUID_TAGS), parser.readyParser().dependencies());
        long generation = registry.snapshot().generation();
        begin(parser);
        fluid(parser, HOT, Map.of("temperature", 350));
        tag(parser, DRINKABLE, List.of(HOT.toString(), "minecraft:water"));
        tag(parser, NESTED, List.of("#example:drinkable", "#fluidcore:milk"));
        complete(parser);
        assertFalse(registry.contains(HOT));
        parser.readyParser().postProcess();
        assertTrue(registry.contains(HOT));
        assertEquals(generation + 1, registry.snapshot().generation());
        assertTrue(registry.hasTag(HOT, NESTED));
        assertTrue(registry.hasTag(FluidKey.of("minecraft:milk"), NESTED));
        assertTrue(parser.publish());
        assertFalse(parser.publish());
        assertEquals(generation + 1, registry.snapshot().generation());
    }

    @Test void failingReloadRetainsBothFluidPropertiesAndIndependentTags() {
        var registry = new FluidRegistry();
        var parser = parser(registry);
        begin(parser);
        fluid(parser, HOT, Map.of("temperature", 350));
        tag(parser, DRINKABLE, List.of(HOT.toString()));
        complete(parser);
        assertTrue(parser.publish());
        FluidRegistry.Snapshot previous = registry.snapshot();
        begin(parser);
        fluid(parser, HOT, Map.of("temperature", 400));
        tag(parser, DRINKABLE, List.of("example:missing"));
        complete(parser);
        assertFalse(parser.publish());
        assertSame(previous, registry.snapshot());
        assertEquals(350, registry.find(HOT).orElseThrow().properties().temperature());
        assertTrue(registry.hasTag(HOT, DRINKABLE));
    }

    @Test void duplicateOrMalformedSectionsPreventPartialRegistryPublication() {
        for (boolean duplicate : List.of(false, true)) {
            var registry = new FluidRegistry();
            var parser = parser(registry);
            FluidRegistry.Snapshot previous = registry.snapshot();
            begin(parser);
            fluid(parser, HOT, Map.of());
            if (duplicate) assertThrows(IllegalArgumentException.class, () -> fluid(parser, HOT, Map.of()));
            else assertThrows(IllegalArgumentException.class, () -> fluid(parser, FluidKey.of("example:bad"), Map.of("light-level", 20)));
            tag(parser, DRINKABLE, List.of(HOT.toString()));
            complete(parser);
            assertFalse(parser.publish());
            assertSame(previous, registry.snapshot());
        }
    }

    @Test void tagCyclesAndUnknownReferencesRetainOldRegistry() {
        for (List<String> values : List.of(List.of("#example:missing"), List.of("example:missing"), List.of("#example:nested"))) {
            var registry = new FluidRegistry();
            var parser = parser(registry);
            FluidRegistry.Snapshot previous = registry.snapshot();
            begin(parser);
            fluid(parser, HOT, Map.of());
            tag(parser, DRINKABLE, values);
            tag(parser, NESTED, List.of("#example:drinkable"));
            complete(parser);
            assertFalse(parser.publish());
            assertSame(previous, registry.snapshot());
        }
    }

    @Test void aRemovedFluidCannotBeResolvedThroughPreviousReloadData() {
        var registry = new FluidRegistry();
        var parser = parser(registry);
        begin(parser);
        fluid(parser, HOT, Map.of("tags", List.of("example:old_inline")));
        tag(parser, DRINKABLE, List.of(HOT.toString()));
        complete(parser);
        assertTrue(parser.publish());
        var previous = registry.snapshot();
        begin(parser);
        tag(parser, DRINKABLE, List.of("#example:old_inline"));
        complete(parser);
        assertFalse(parser.publish());
        assertSame(previous, registry.snapshot());
        begin(parser);
        complete(parser);
        assertTrue(parser.publish());
        assertFalse(registry.contains(HOT));
        assertFalse(registry.hasTag(HOT, DRINKABLE));
        assertFalse(registry.snapshot().tags().containsKey(DRINKABLE));
    }

    @Test void tagsCanReferenceLaterDeclarationsAndInlineMemberships() {
        var resolved = FluidTagConfigParser.resolve(Map.of(NESTED, List.of("#example:drinkable"), DRINKABLE, List.of(HOT.toString())),
                Set.of(HOT, FluidKey.of("minecraft:water")), Map.of(DRINKABLE, Set.of(FluidKey.of("minecraft:water"))));
        assertEquals(Set.of(HOT, FluidKey.of("minecraft:water")), resolved.get(NESTED));
        assertThrows(UnsupportedOperationException.class, () -> resolved.get(NESTED).clear());
    }

    @Test void explicitBuiltinMetadataOverridePreservesBaseAndRestoresOnRemoval() {
        var registry = new FluidRegistry();
        var parser = parser(registry);
        FluidKey water = FluidKey.of("minecraft:water");
        FluidDefinition original = registry.find(water).orElseThrow();
        begin(parser);
        fluid(parser, water, Map.of("override", true, "display-name", "Blue water", "color", "#80abcdef"));
        tag(parser, DRINKABLE, List.of("#fluidcore:water"));
        complete(parser);
        assertTrue(parser.publish());
        assertEquals("Blue water", registry.find(water).orElseThrow().displayName());
        assertEquals(0x80abcdef, registry.find(water).orElseThrow().properties().color().orElseThrow());
        assertEquals(original.properties().bucketItem(), registry.find(water).orElseThrow().properties().bucketItem());
        assertEquals(original.properties().sounds(), registry.find(water).orElseThrow().properties().sounds());
        assertEquals(original, registry.baseDefinition(water).orElseThrow());
        assertTrue(registry.hasTag(water, DRINKABLE));
        begin(parser);
        complete(parser);
        assertTrue(parser.publish());
        assertEquals(original, registry.find(water).orElseThrow());
        assertFalse(registry.snapshot().tags().containsKey(DRINKABLE));
    }

    @Test void removingAnOverrideDoesNotRetainItsInlineTagInNewCandidates() {
        var registry = new FluidRegistry();
        var parser = parser(registry);
        FluidKey water = FluidKey.of("minecraft:water");
        begin(parser);
        fluid(parser, water, Map.of("override", true, "tags", List.of("example:temporary")));
        complete(parser);
        assertTrue(parser.publish());
        var previous = registry.snapshot();
        begin(parser);
        tag(parser, DRINKABLE, List.of("#example:temporary"));
        complete(parser);
        assertFalse(parser.publish());
        assertSame(previous, registry.snapshot());
        begin(parser);
        tag(parser, DRINKABLE, List.of("#fluidcore:water"));
        complete(parser);
        assertTrue(parser.publish());
        assertFalse(registry.hasTag(water, FluidKey.of("example:temporary")));
        assertTrue(registry.hasTag(water, DRINKABLE));
    }

    @Test void overridesRequireExplicitBooleanFlagAndExistingBase() {
        for (Map<String, Object> values : List.<Map<String, Object>>of(Map.of(), Map.of("override", "true"), Map.of("override", true))) {
            var registry = new FluidRegistry();
            var parser = parser(registry);
            var previous = registry.snapshot();
            begin(parser);
            FluidKey key = values.equals(Map.of("override", true)) ? HOT : FluidKey.of("minecraft:water");
            if (values.isEmpty()) fluid(parser, key, values);
            else assertThrows(IllegalArgumentException.class, () -> fluid(parser, key, values));
            complete(parser);
            assertFalse(parser.publish());
            assertSame(previous, registry.snapshot());
        }
    }

    @Test void externalOverlayControlsTagCandidatesWhenItsCeBaseIsReplacedOrRemoved() {
        var registry = new FluidRegistry();
        var parser = parser(registry);
        begin(parser);
        fluid(parser, HOT, Map.of("tags", List.of("example:original")));
        complete(parser);
        assertTrue(parser.publish());
        FluidKey externalTag = FluidKey.of("example:external");
        FluidDefinition overlay = new FluidDefinition(HOT, "External display", Set.of(externalTag));
        registry.replaceOwnedOverrides("another-addon", List.of(overlay));
        begin(parser);
        fluid(parser, HOT, Map.of("tags", List.of("example:new_base")));
        tag(parser, DRINKABLE, List.of("#example:external"));
        complete(parser);
        assertTrue(parser.publish());
        assertEquals(overlay, registry.find(HOT).orElseThrow());
        assertTrue(registry.hasTag(HOT, DRINKABLE));
        assertTrue(registry.hasTag(HOT, externalTag));
        assertFalse(registry.hasTag(HOT, FluidKey.of("example:new_base")));
        assertEquals(Set.of(FluidKey.of("example:new_base")), registry.baseDefinition(HOT).orElseThrow().tags());
        var previous = registry.snapshot();
        begin(parser);
        tag(parser, DRINKABLE, List.of("#example:external"));
        complete(parser);
        assertFalse(parser.publish());
        assertSame(previous, registry.snapshot());
        begin(parser);
        complete(parser);
        assertTrue(parser.publish());
        assertFalse(registry.contains(HOT));
        assertTrue(registry.metadataOverride(HOT).isEmpty());
    }

    @Test void anExternalOverlayEqualToItsPreviousBaseStillSurvivesBaseTagChanges() {
        var registry = new FluidRegistry();
        var parser = parser(registry);
        begin(parser);
        fluid(parser, HOT, Map.of("tags", List.of("example:original")));
        complete(parser);
        assertTrue(parser.publish());
        FluidDefinition original = registry.baseDefinition(HOT).orElseThrow();
        registry.replaceOwnedOverrides("another-addon", List.of(original));
        begin(parser);
        fluid(parser, HOT, Map.of("tags", List.of("example:new_base")));
        tag(parser, DRINKABLE, List.of("#example:original"));
        complete(parser);
        assertTrue(parser.publish());
        assertEquals(original, registry.find(HOT).orElseThrow());
        assertTrue(registry.hasTag(HOT, DRINKABLE));
        assertFalse(registry.hasTag(HOT, FluidKey.of("example:new_base")));
    }

    @Test void tagListsRequireValuesAndRejectDuplicateEntriesAndDefinitions() {
        var parser = parser(new FluidRegistry());
        begin(parser);
        assertThrows(IllegalArgumentException.class, () -> parser.tagsParser().parseSection(null, Path.of("test.yml"), Key.of("example:bad"), section(Map.of())));
        assertThrows(IllegalArgumentException.class, () -> tag(parser, DRINKABLE, List.of("minecraft:water", "minecraft:water")));
        tag(parser, DRINKABLE, List.of());
        assertThrows(IllegalArgumentException.class, () -> tag(parser, DRINKABLE, List.of()));
    }

    private static ConfigSection section(Map<String, Object> values) { return ConfigSection.of("test", values); }
    private static FluidConfigParser parser(FluidRegistry registry) { return new FluidConfigParser(registry, Logger.getLogger("FluidCore-Test")); }
    private static void begin(FluidConfigParser parser) { parser.preProcess(); parser.tagsParser().preProcess(); }
    private static void complete(FluidConfigParser parser) { parser.postProcess(); parser.tagsParser().postProcess(); }
    private static void fluid(FluidConfigParser parser, FluidKey key, Map<String, Object> fields) {
        parser.parseSection(null, Path.of("test.yml"), Key.of(key.toString()), section(fields));
    }
    private static void tag(FluidConfigParser parser, FluidKey key, List<String> values) {
        parser.tagsParser().parseSection(null, Path.of("test.yml"), Key.of(key.toString()), section(Map.of("values", values)));
    }
}
