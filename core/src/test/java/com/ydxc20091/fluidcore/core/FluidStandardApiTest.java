package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Behavioral checks for the independent standard API. Copyright 2026 ydxc20091. */
class FluidStandardApiTest {
    private static final FluidVariant WATER = FluidVariant.of("minecraft:water");
    private static final FluidVariant LAVA = FluidVariant.of("minecraft:lava");
    private static final FluidKey FUEL = FluidKey.of("test:fuel");
    private static final ComponentType<Integer> TEMPERATURE = ComponentType.of("test:temperature", ComponentCodecs.INTEGER);

    @Test void propertiesHaveDeclaredDefaultsAndPhysicalBounds() {
        var defaults = FluidProperties.defaults();
        assertEquals(1000, defaults.density()); assertEquals(1000, defaults.viscosity());
        assertEquals(300, defaults.temperature()); assertEquals(0, defaults.lightLevel());
        assertEquals(FluidRarity.COMMON, defaults.rarity()); assertTrue(defaults.color().isEmpty());
        assertTrue(defaults.texture().isEmpty()); assertTrue(defaults.bucketItem().isEmpty());
        assertEquals(-100, FluidProperties.builder().density(-100).build().density());
        assertThrows(IllegalArgumentException.class, () -> FluidProperties.builder().viscosity(-1).build());
        assertThrows(IllegalArgumentException.class, () -> FluidProperties.builder().temperature(-1).build());
        assertThrows(IllegalArgumentException.class, () -> FluidProperties.builder().lightLevel(-1).build());
        assertThrows(IllegalArgumentException.class, () -> FluidProperties.builder().lightLevel(16).build());
        assertThrows(IllegalArgumentException.class, () -> FluidProperties.builder().texture(" "));
    }
    @Test void metadataIsImmutableAndCanBeEditedByCopy() {
        var builder = FluidProperties.builder().color(0x80ff2200).texture("test:oil")
            .bucketItem("test:oil_bucket").rarity(FluidRarity.RARE).sound(FluidSound.CONTAINER_FILL, "test:fill");
        var original = builder.build(); builder.color(0).sound(FluidSound.CONTAINER_FILL, "test:changed");
        assertEquals(0x80ff2200, original.color().orElseThrow());
        assertEquals(FluidKey.of("test:fill"), original.sound(FluidSound.CONTAINER_FILL).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> original.sounds().clear());
        var copy = original.toBuilder().withoutColor().withoutTexture().withoutBucketItem().withoutSound(FluidSound.CONTAINER_FILL).build();
        assertTrue(copy.color().isEmpty()); assertTrue(copy.texture().isEmpty()); assertTrue(copy.bucketItem().isEmpty());
        assertTrue(copy.sound(FluidSound.CONTAINER_FILL).isEmpty()); assertEquals(FluidRarity.RARE, copy.rarity());
        assertEquals(original, original.toBuilder().build()); assertEquals(original.hashCode(), original.toBuilder().build().hashCode());
    }
    @Test void definitionBuilderKeepsLegacyConstructionAndExposesMetadata() {
        var key = FluidKey.of("test:oil"); var legacy = new FluidDefinition(key, "Oil", Set.of(FUEL));
        assertEquals(FluidProperties.defaults(), legacy.properties());
        var tags = new HashSet<>(Set.of(FUEL));
        var definition = FluidDefinition.builder(key).displayName("Oil").tags(tags)
            .properties(FluidProperties.builder().density(800).viscosity(1200).temperature(350).lightLevel(2)
                .color(0xff000000).texture("test:oil").rarity(FluidRarity.EPIC).bucketItem("test:bucket")
                .sound(FluidSound.VAPORIZE, "test:hiss").build()).build();
        tags.clear(); assertTrue(definition.tags().contains(FUEL));
        assertEquals(800, definition.density()); assertEquals(1200, definition.viscosity()); assertEquals(350, definition.temperature());
        assertEquals(2, definition.lightLevel()); assertEquals(0xff000000, definition.color().orElseThrow());
        assertEquals("test:oil", definition.texture().orElseThrow()); assertEquals(FluidRarity.EPIC, definition.rarity());
        assertEquals(FluidKey.of("test:bucket"), definition.bucketItem().orElseThrow());
        assertEquals(FluidKey.of("test:hiss"), definition.sound(FluidSound.VAPORIZE).orElseThrow());
    }
    @Test void builtinDefinitionsExposeStandardMetadata() {
        var registry = new FluidRegistry();
        var water = registry.find("minecraft:water").orElseThrow();
        var lava = registry.find("minecraft:lava").orElseThrow();
        var milk = registry.find("minecraft:milk").orElseThrow();
        assertEquals(1000, water.density()); assertEquals(15, lava.lightLevel());
        assertEquals(1300, lava.temperature()); assertTrue(milk.color().isPresent());
        assertEquals(FluidKey.of("minecraft:milk_bucket"), milk.bucketItem().orElseThrow());
        assertTrue(water.sound(FluidSound.CONTAINER_FILL).isPresent()); assertTrue(lava.sound(FluidSound.CAULDRON_DRIP).isPresent());
        assertEquals(4, registry.size()); assertTrue(registry.contains("minecraft:water"));
        assertTrue(registry.hasTag(FluidKey.of("minecraft:honey"), FluidKey.of("c:honey")));
        assertThrows(UnsupportedOperationException.class, () -> registry.all().clear());
    }
    @Test void typedComponentsRoundTripWithoutMutationAndRejectMalformedData() {
        var warm = WATER.with(TEMPERATURE, 375);
        assertEquals(375, warm.get(TEMPERATURE).orElseThrow()); assertTrue(WATER.get(TEMPERATURE).isEmpty());
        assertEquals(300, WATER.getOrDefault(TEMPERATURE, 300)); assertEquals(WATER, warm.remove(TEMPERATURE));
        assertSame(WATER, WATER.remove(TEMPERATURE));
        var invalid = WATER.withComponent(TEMPERATURE.key(), ComponentValue.of(new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> invalid.get(TEMPERATURE));
        assertThrows(NullPointerException.class, () -> WATER.with(TEMPERATURE, null));
    }
    @Test void standardCodecsValidateEncodingAndPreserveMutableByteIsolation() {
        assertEquals(Long.MIN_VALUE, ComponentCodecs.LONG.decode(ComponentCodecs.LONG.encode(Long.MIN_VALUE)));
        assertEquals(1.25f, ComponentCodecs.FLOAT.decode(ComponentCodecs.FLOAT.encode(1.25f)));
        assertEquals(1.5d, ComponentCodecs.DOUBLE.decode(ComponentCodecs.DOUBLE.encode(1.5d)));
        assertEquals(true, ComponentCodecs.BOOLEAN.decode(ComponentCodecs.BOOLEAN.encode(true)));
        assertThrows(IllegalArgumentException.class, () -> ComponentCodecs.BOOLEAN.decode(new byte[]{2}));
        assertThrows(IllegalArgumentException.class, () -> ComponentCodecs.DOUBLE.encode(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> ComponentCodecs.FLOAT.decode(ComponentCodecs.INTEGER.encode(0x7f800000)));
        assertEquals("液体", ComponentCodecs.STRING.decode(ComponentCodecs.STRING.encode("液体")));
        assertThrows(IllegalArgumentException.class, () -> ComponentCodecs.STRING.decode(new byte[]{(byte)0xc0, (byte)0xaf}));
        assertThrows(IllegalArgumentException.class, () -> ComponentCodecs.STRING.encode("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> ComponentCodecs.boundedString(1).encode("液"));
        assertThrows(IllegalArgumentException.class, () -> ComponentCodecs.boundedString(1).decode(new byte[]{1, 2}));
        var uuid = java.util.UUID.randomUUID(); assertEquals(uuid, ComponentCodecs.UUID.decode(ComponentCodecs.UUID.encode(uuid)));
        assertEquals(FUEL, ComponentCodecs.KEY.decode(ComponentCodecs.KEY.encode(FUEL)));
        byte[] original = {1, 2}; byte[] encoded = ComponentCodecs.BYTES.encode(original); original[0] = 9;
        assertArrayEquals(new byte[]{1, 2}, encoded); byte[] decoded = ComponentCodecs.BYTES.decode(encoded); decoded[1] = 9;
        assertArrayEquals(new byte[]{1, 2}, encoded);
    }
    @Test void typedAccessDoesNotImplicitlyRegisterUnknownComponents() {
        var registry = new FluidRegistry(); var codec = new FluidStackCodec(registry);
        var stack = FluidStack.of(WATER.with(TEMPERATURE, 350), 500); byte[] bytes = codec.encode(stack);
        assertEquals(FluidReadResult.Status.UNKNOWN, codec.decode(bytes).status());
        registry.registerComponentCodec("test", TEMPERATURE);
        assertEquals(FluidReadResult.Status.PRESENT, codec.decode(bytes).status());
        registry.unregisterComponentCodecs("test");
        assertEquals(FluidReadResult.Status.UNKNOWN, codec.decode(bytes).status());
        assertArrayEquals(bytes, codec.decode(bytes).raw());
    }
    @Test void stackQuantityHelpersPreserveIdentityAndCheckOverflow() {
        var stack = FluidStack.of(WATER, 500);
        assertEquals(600, stack.grow(100).amount()); assertEquals(400, stack.shrink(100).amount());
        assertSame(FluidStack.EMPTY, stack.shrink(Long.MAX_VALUE)); assertEquals(250, stack.limitSize(250).amount());
        var split = stack.split(700); assertEquals(stack, split.taken()); assertSame(FluidStack.EMPTY, split.remainder());
        var partial = stack.split(123); assertEquals(123, partial.taken().amount()); assertEquals(377, partial.remainder().amount());
        assertEquals(1000, FluidStack.BUCKET); assertEquals(250, FluidStack.BOTTLE);
        assertThrows(ArithmeticException.class, () -> FluidStack.of(WATER, Long.MAX_VALUE).grow(1));
        assertThrows(IllegalArgumentException.class, () -> stack.grow(-1));
        assertThrows(IllegalArgumentException.class, () -> stack.shrink(-1));
        assertThrows(IllegalArgumentException.class, () -> stack.limitSize(-1));
        assertThrows(IllegalArgumentException.class, () -> stack.split(-1));
        assertThrows(IllegalStateException.class, () -> FluidStack.EMPTY.grow(1));
    }
    @Test void stackTypedHelpersAndIdentityMatchingAreDistinctFromQuantity() {
        var original = FluidStack.of(WATER, 500); var warm = original.with(TEMPERATURE, 350);
        assertEquals(350, warm.get(TEMPERATURE).orElseThrow()); assertEquals(original, warm.without(TEMPERATURE));
        assertTrue(original.sameFluid(warm)); assertFalse(original.sameVariant(warm));
        assertTrue(original.sameVariant(original.withAmount(100))); assertFalse(original.matches(original.withAmount(100)));
        assertTrue(FluidStack.EMPTY.sameFluid(FluidStack.EMPTY)); assertFalse(original.sameFluid(FluidStack.EMPTY));
        assertTrue(FluidStack.EMPTY.get(TEMPERATURE).isEmpty()); assertSame(FluidStack.EMPTY, FluidStack.EMPTY.without(TEMPERATURE));
        assertThrows(IllegalStateException.class, () -> FluidStack.EMPTY.with(TEMPERATURE, 1));
        assertTrue(FluidAction.SIMULATE.simulate()); assertTrue(FluidAction.EXECUTE.execute()); assertFalse(FluidAction.EXECUTE.simulate());
    }
    @Test void standaloneTagsHaveSeparateOwnersAndKeepSnapshotsImmutable() {
        var registry = new FluidRegistry(); var members = new HashSet<>(Set.of(WATER.fluid(), FluidKey.of("future:oil")));
        registry.registerTag("a", FUEL, members); members.clear(); var old = registry.snapshot();
        assertTrue(registry.hasTag(WATER.fluid(), FUEL)); assertFalse(registry.hasTag(FluidKey.of("future:oil"), FUEL));
        assertTrue(registry.tagMembers(FUEL).contains(FluidKey.of("future:oil")));
        assertThrows(UnsupportedOperationException.class, () -> old.tags().get(FUEL).clear());
        assertThrows(IllegalArgumentException.class, () -> registry.unregisterTag("b", FUEL));
        registry.replaceOwnedTags("a", Map.of(FUEL, Set.of(LAVA.fluid())));
        assertFalse(registry.hasTag(WATER.fluid(), FUEL)); assertTrue(registry.hasTag(LAVA.fluid(), FUEL));
        assertTrue(old.hasTag(WATER.fluid(), FUEL)); assertTrue(registry.unregisterTag("a", FUEL));
        assertFalse(registry.unregisterTag("a", FUEL));
        assertTrue(registry.hasTag(WATER.fluid(), FluidKey.of("fluidcore:water")));
    }
    @Test void fluidAndTagBatchIsAtomicAndDoesNotReplaceOtherOwners() {
        var registry = new FluidRegistry(); var oil = FluidDefinition.builder("a:oil").build();
        registry.replaceOwned("a", List.of(oil), Map.of(FUEL, Set.of(oil.key())));
        registry.registerTag("b", FluidKey.of("b:tag"), Set.of(WATER.fluid())); var old = registry.snapshot();
        assertThrows(IllegalArgumentException.class, () -> registry.replaceOwned("a", List.of(), Map.of(FluidKey.of("b:tag"), Set.of())));
        assertSame(old, registry.snapshot()); assertTrue(registry.contains(oil.key())); assertTrue(registry.hasTag(oil.key(), FUEL));
        registry.replaceOwned("a", List.of(), Map.of());
        assertFalse(registry.contains(oil.key())); assertFalse(registry.snapshot().tags().containsKey(FUEL));
        assertTrue(registry.snapshot().tags().containsKey(FluidKey.of("b:tag")));
        var legacy = new FluidRegistry.Snapshot(old.fluids(), old.componentCodecs(), old.generation());
        assertTrue(legacy.tags().isEmpty()); assertEquals(old.fluids(), legacy.fluids());
    }
    @Test void legacyFluidReplacementRetainsStandaloneTagsAndOtherOwners() {
        var registry = new FluidRegistry(); registry.registerTag("a", FUEL, Set.of(WATER.fluid()));
        registry.replaceOwned("a", List.of(FluidDefinition.builder("a:oil").build()));
        assertTrue(registry.hasTag(WATER.fluid(), FUEL)); registry.unregisterTags("a");
        assertFalse(registry.hasTag(WATER.fluid(), FUEL)); assertTrue(registry.contains("a:oil"));
    }
    @Test void explicitMetadataOverlayPreservesBaseOwnerAndRestoresOriginalTags() {
        var registry = new FluidRegistry(); var base = registry.baseDefinition(WATER.fluid()).orElseThrow();
        assertTrue(registry.metadataOverride(WATER.fluid()).isEmpty());
        var overlay = new FluidDefinition(WATER.fluid(), "Configured water", Set.of(FUEL), base.properties().toBuilder().temperature(400).build());
        registry.replaceOwnedOverrides("config", List.of(overlay));
        assertEquals(overlay, registry.metadataOverride(WATER.fluid()).orElseThrow());
        assertEquals(overlay, registry.find(WATER.fluid()).orElseThrow()); assertEquals(base, registry.baseDefinition(WATER.fluid()).orElseThrow());
        assertEquals(base, registry.codeDefinition(WATER.fluid()).orElseThrow());
        assertTrue(registry.tagsOf(WATER.fluid()).contains(FUEL)); assertFalse(registry.tagsOf(WATER.fluid()).contains(FluidKey.of("fluidcore:water")));
        assertThrows(UnsupportedOperationException.class, () -> registry.tagsOf(WATER.fluid()).clear());
        assertThrows(IllegalArgumentException.class, () -> registry.replaceOwned("config", List.of(overlay)));
        var old = registry.snapshot(); registry.unregisterOverrides("config");
        assertTrue(registry.metadataOverride(WATER.fluid()).isEmpty());
        assertEquals(base, registry.find(WATER.fluid()).orElseThrow()); assertFalse(registry.tagsOf(WATER.fluid()).contains(FUEL));
        assertTrue(registry.tagsOf(WATER.fluid()).contains(FluidKey.of("fluidcore:water")));
        assertEquals(overlay, old.find(WATER.fluid()).orElseThrow()); assertEquals(base, old.baseDefinition(WATER.fluid()).orElseThrow());
    }
    @Test void metadataOverlayConflictsAndMissingBasesLeaveWholeBatchUnchanged() {
        var registry = new FluidRegistry(); var overlay = FluidDefinition.builder(WATER.fluid()).displayName("Override").build();
        registry.replaceOwnedOverrides("a", List.of(overlay)); var old = registry.snapshot();
        assertThrows(IllegalArgumentException.class, () -> registry.replaceOwned("b", List.of(FluidDefinition.builder("b:oil").build()), Map.of(FUEL, Set.of(LAVA.fluid())), List.of(overlay)));
        assertSame(old, registry.snapshot()); assertFalse(registry.contains("b:oil")); assertFalse(registry.snapshot().tags().containsKey(FUEL));
        assertThrows(IllegalArgumentException.class, () -> registry.replaceOwnedOverrides("b", List.of(FluidDefinition.builder("unknown:oil").build())));
        assertSame(old, registry.snapshot());
        registry.replaceOwned("b", List.of(FluidDefinition.builder("b:oil").build()), Map.of(), List.of());
        assertEquals(overlay, registry.find(WATER.fluid()).orElseThrow());
        registry.replaceOwned("a", List.of(), Map.of()); assertEquals(overlay, registry.find(WATER.fluid()).orElseThrow());
        registry.replaceOwned("a", List.of(), Map.of(), List.of()); assertEquals(registry.baseDefinition(WATER.fluid()), registry.find(WATER.fluid()));
    }
    @Test void baseReloadKeepsOtherOwnerOverlayButBaseRemovalDiscardsIt() {
        var registry = new FluidRegistry(); var key = FluidKey.of("a:oil");
        registry.register("a", FluidDefinition.builder(key).displayName("Original").build());
        var overlay = FluidDefinition.builder(key).displayName("Configured").build(); registry.replaceOwnedOverrides("b", List.of(overlay));
        registry.replaceOwned("a", List.of(FluidDefinition.builder(key).displayName("Updated original").build()));
        assertEquals("Updated original", registry.baseDefinition(key).orElseThrow().displayName());
        assertEquals("Configured", registry.find(key).orElseThrow().displayName());
        registry.replaceOwned("a", List.of()); assertTrue(registry.find(key).isEmpty());
        registry.register("a", FluidDefinition.builder(key).displayName("New registration").build());
        assertEquals("New registration", registry.find(key).orElseThrow().displayName());
        registry.replaceOwnedOverrides("c", List.of(FluidDefinition.builder(key).displayName("New override").build()));
        assertEquals("New override", registry.find(key).orElseThrow().displayName());
    }
    @Test void ingredientsSeparateIdentityAndQuantityAndProvideCandidates() {
        var registry = new FluidRegistry(); var ingredient = FluidIngredient.fluid(WATER.fluid()).withAmount(500);
        assertTrue(ingredient.matches(WATER)); assertTrue(ingredient.matchesIdentity(FluidStack.of(WATER, 1)));
        assertFalse(ingredient.matches(FluidStack.of(WATER, 499))); assertTrue(ingredient.withoutAmount().matches(FluidStack.of(WATER, 1)));
        assertFalse(ingredient.withoutAmount().matches(FluidStack.EMPTY)); assertTrue(FluidIngredient.empty().matches(FluidStack.EMPTY));
        assertFalse(FluidIngredient.empty().matches(FluidStack.of(WATER, 1)));
        var combined = FluidIngredient.anyOf(ingredient, FluidIngredient.fluid(LAVA.fluid()));
        assertTrue(combined.matches(FluidStack.of(LAVA, 1))); assertEquals(List.of(WATER, LAVA), combined.candidates(registry));
        assertEquals(List.of(WATER.with(TEMPERATURE, 375)), ingredient.component(TEMPERATURE, 375).candidates(registry));
        assertTrue(FluidIngredient.fluid(FluidKey.of("future:oil")).candidates(registry).isEmpty());
    }
    @Test void tagIngredientsTrackPublishedTagChangesAndSkipUnregisteredCandidates() {
        var registry = new FluidRegistry(); registry.registerTag("a", FUEL, Set.of(WATER.fluid(), FluidKey.of("future:oil")));
        var ingredient = FluidIngredient.tag(registry, FUEL);
        assertEquals(List.of(WATER), ingredient.candidates(registry)); assertTrue(ingredient.matches(WATER));
        registry.replaceOwnedTags("a", Map.of(FUEL, Set.of(LAVA.fluid())));
        assertFalse(ingredient.matches(WATER)); assertTrue(ingredient.matches(LAVA)); assertEquals(List.of(LAVA), ingredient.candidates(registry));
    }
    @Test void ingredientsParseStandardFormsWithoutLosingLongAmounts() {
        var registry = new FluidRegistry(); var water = FluidStack.of(WATER, 1);
        assertTrue(FluidIngredient.parse("minecraft:water", registry).matches(water));
        assertTrue(FluidIngredient.parse("#fluidcore:water", registry).matches(water));
        assertTrue(FluidIngredient.parse(List.of("minecraft:lava", "#fluidcore:water"), registry).matches(water));
        assertEquals(Long.MAX_VALUE, FluidIngredient.parseSized(Map.of("fluid", "minecraft:water", "amount", Long.MAX_VALUE), registry, 1).amount());
        assertEquals(500, FluidIngredient.parseSized("minecraft:water", registry, 500).amount());
        assertTrue(FluidIngredient.parse(Map.of("empty", true), registry).matches(FluidStack.EMPTY));
        var warm = FluidIngredient.parse(Map.of("fluid", "minecraft:water", "components", Map.of(TEMPERATURE.key(), TEMPERATURE.encode(350)), "exact-components", true), registry);
        assertTrue(warm.matches(WATER.with(TEMPERATURE, 350))); assertFalse(warm.matches(WATER));
    }
    @Test void ingredientParsingRejectsAmbiguousFractionalAndOverflowedInputs() {
        var registry = new FluidRegistry();
        assertThrows(IllegalArgumentException.class, () -> FluidIngredient.parse(Map.of("fluid", "minecraft:water", "tag", "fluidcore:water"), registry));
        assertThrows(IllegalArgumentException.class, () -> FluidIngredient.parse(Map.of("fluid", "minecraft:water", "ammount", 1), registry));
        assertThrows(IllegalArgumentException.class, () -> FluidIngredient.parse(Map.of("fluid", "minecraft:water", "amount", 1.5), registry));
        assertThrows(IllegalArgumentException.class, () -> FluidIngredient.parse(Map.of("fluid", "minecraft:water", "amount", new BigInteger("9223372036854775808")), registry));
        assertThrows(IllegalArgumentException.class, () -> FluidIngredient.parse(Map.of("fluid", "minecraft:water", "amount", -1), registry));
        assertThrows(IllegalArgumentException.class, () -> FluidIngredient.parse(Map.of("empty", true, "amount", 1), registry));
        assertThrows(IllegalArgumentException.class, () -> FluidIngredient.parse(Map.of("fluid", "minecraft:water", "components", Map.of("test:x", 1)), registry));
    }
    @Test void validityQueriesHonorFiltersPortsRatesAndOwnershipWithoutMutation() {
        var context = new TestContext(); var tank = new FluidTank(1000, context, WATER::equals, () -> {});
        assertTrue(tank.isFluidValid(0, WATER)); assertFalse(tank.isFluidValid(0, LAVA));
        assertFalse(StorageViews.readonly(tank).isFluidValid(0, WATER)); assertFalse(StorageViews.output(tank).isFluidValid(0, WATER));
        assertTrue(StorageViews.input(tank).isFluidValid(0, WATER)); assertFalse(StorageViews.filter(tank, LAVA::equals).isFluidValid(0, WATER));
        var limited = StorageViews.rateLimit(tank, 100, 50); assertTrue(limited.isFluidValid(0, WATER));
        limited.fill(FluidStack.of(WATER, 100), FluidAction.EXECUTE); assertFalse(limited.isFluidValid(0, WATER));
        context.tick++; assertTrue(limited.isFluidValid(0, WATER)); assertEquals(100, tank.amount());
        var multi = new MultiTankStorage(tank, new FluidTank(100, context, LAVA::equals, () -> {}));
        assertTrue(multi.isFluidValid(1, LAVA)); assertFalse(multi.isFluidValid(1, WATER));
        assertThrows(IndexOutOfBoundsException.class, () -> multi.isFluidValid(2, WATER));
        context.retired = true; assertThrows(StorageAccessException.class, () -> limited.isFluidValid(0, WATER));
        assertThrows(StorageAccessException.class, tank::amount); assertThrows(StorageAccessException.class, tank::space);
    }
    @Test void singleTankQueriesPreserveOverCapacityData() {
        var tank = new FluidTank(100, new TestContext()); assertTrue(tank.isEmpty()); assertEquals(100, tank.space());
        tank.restore(FluidStack.of(WATER, 200)); assertEquals(100, tank.capacity()); assertEquals(200, tank.amount());
        assertEquals(0, tank.space()); assertEquals(FluidStack.of(WATER, 200), tank.current());
    }
    @Test void automaticDrainSkipsDisallowedIdentityAndSimulatesWithoutChanges() {
        var context = new TestContext(); var first = new FluidTank(1000, context); var second = new FluidTank(1000, context);
        first.restore(FluidStack.of(WATER, 500)); second.restore(FluidStack.of(LAVA, 400));
        var multi = StorageViews.filter(new MultiTankStorage(first, second), LAVA::equals);
        assertEquals(FluidStack.of(LAVA, 200), multi.drain(200, FluidAction.SIMULATE));
        assertEquals(400, second.amount()); assertEquals(500, first.amount());
        assertEquals(FluidStack.of(LAVA, 200), multi.drain(200, FluidAction.EXECUTE)); assertEquals(200, second.amount());
        assertEquals(FluidStack.of(LAVA, 100), multi.drain(FluidStack.of(LAVA, 100), FluidAction.EXECUTE));
        assertTrue(StorageViews.input(second).drain(100, FluidAction.EXECUTE).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> multi.drain(-1, FluidAction.SIMULATE));
    }
    @Test void automaticTransferSkipsRejectedFirstIdentityAndConservesSelectedFluid() {
        var context = new TestContext(); var water = new FluidTank(1000, context); var lava = new FluidTank(1000, context);
        water.restore(FluidStack.of(WATER, 500)); lava.restore(FluidStack.of(LAVA, 700));
        var source = new MultiTankStorage(water, lava); var target = new FluidTank(300, context, LAVA::equals, () -> {});
        assertEquals(300, FluidTransfers.move(source, target, 600, FluidAction.SIMULATE).amount());
        assertEquals(500, water.amount()); assertEquals(700, lava.amount()); assertTrue(target.isEmpty());
        var moved = FluidTransfers.move(StorageViews.output(source), StorageViews.input(target), 600, FluidAction.EXECUTE);
        assertEquals(TransferResult.Status.SUCCESS, moved.status()); assertEquals(300, moved.amount());
        assertEquals(500, water.amount()); assertEquals(400, lava.amount()); assertEquals(300, target.amount());
        assertEquals(700, lava.amount() + target.amount());
    }
    @Test void automaticTransferRejectsWrongContextAndUnsupportedStorages() {
        var sourceContext = new TestContext(); var targetContext = new TestContext();
        var source = new FluidTank(1000, sourceContext); var target = new FluidTank(1000, targetContext);
        source.restore(FluidStack.of(WATER, 100)); targetContext.retired = true;
        assertEquals(TransferResult.Status.WRONG_CONTEXT, FluidTransfers.move(source, target, 100, FluidAction.EXECUTE).status());
        assertEquals(100, source.amount());
        FluidStorage unsupported = new FluidStorage() {
            public int tanks() { return 1; } public FluidStack content(int tank) { return FluidStack.EMPTY; }
            public long capacity(int tank) { return 100; } public boolean supportsTransactions() { return false; }
            public long insert(FluidVariant variant, long amount, FluidTransaction transaction) { throw new AssertionError(); }
            public long extract(FluidVariant variant, long amount, FluidTransaction transaction) { throw new AssertionError(); }
        };
        assertEquals(TransferResult.Status.UNSUPPORTED, FluidTransfers.move(source, unsupported, 100, FluidAction.EXECUTE).status());
    }
}
