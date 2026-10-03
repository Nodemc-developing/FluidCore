package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FluidRegistryCodecTest {
    private static final FluidVariant WATER = FluidVariant.of("minecraft:water");
    private static final FluidKey TEMPERATURE = FluidKey.of("test:temperature");
    private static final ComponentCodec<Integer> INTEGER = new ComponentCodec<>() {
        @Override public byte[] encode(Integer value) { return ByteBuffer.allocate(4).putInt(value).array(); }
        @Override public Integer decode(byte[] value) { if (value.length != 4) throw new IllegalArgumentException("Expected 4 bytes"); return ByteBuffer.wrap(value).getInt(); }
    };

    @Test void defaultsAndAtomicOwnedReloadPreserveOtherPlugins() {
        var registry = new FluidRegistry();
        assertTrue(registry.contains(FluidKey.of("minecraft:water")));
        registry.register("plugin-a", new FluidDefinition(FluidKey.of("a:oil"), "Oil", Set.of(FluidKey.of("test:fuel"))));
        var oldSnapshot = registry.snapshot();
        registry.replaceOwned("plugin-b", List.of(new FluidDefinition(FluidKey.of("b:steam"), "Steam", Set.of())));
        assertTrue(registry.contains(FluidKey.of("a:oil")));
        assertFalse(oldSnapshot.fluids().containsKey(FluidKey.of("b:steam")));
        assertThrows(UnsupportedOperationException.class, () -> oldSnapshot.fluids().clear());
        long generation = registry.snapshot().generation();
        assertThrows(IllegalArgumentException.class, () -> registry.replaceOwned("plugin-b", List.of(new FluidDefinition(FluidKey.of("a:oil"), "Hijacked", Set.of()))));
        assertEquals(generation, registry.snapshot().generation());
        assertEquals("Oil", registry.find(FluidKey.of("a:oil")).orElseThrow().displayName());
    }
    @Test void duplicateBatchFailureDoesNotPartiallyPublish() {
        var registry = new FluidRegistry();
        var definition = new FluidDefinition(FluidKey.of("a:oil"), "Oil", Set.of());
        assertThrows(IllegalArgumentException.class, () -> registry.replaceOwned("a", List.of(definition, definition)));
        assertFalse(registry.contains(definition.key()));
    }
    @Test void ingredientMatchesTagAmountAndSubsetComponents() {
        var registry = new FluidRegistry();
        var warm = WATER.withComponent(TEMPERATURE, ComponentValue.of(INTEGER.encode(20)));
        var ingredient = FluidIngredient.tag(registry, FluidKey.of("fluidcore:water"), 500).components(Map.of(TEMPERATURE, ComponentValue.of(INTEGER.encode(20))), false);
        assertTrue(ingredient.matches(FluidStack.of(warm, 500)));
        assertFalse(ingredient.matches(FluidStack.of(warm, 499)));
        assertFalse(ingredient.matches(FluidStack.of(WATER, 500)));
    }
    @Test void emptyAndPlainFluidRoundTrip() {
        var codec = new FluidStackCodec(new FluidRegistry());
        assertEquals(FluidReadResult.Status.EMPTY, codec.decode(codec.encode(FluidStack.EMPTY)).status());
        var stack = FluidStack.of(WATER, Long.MAX_VALUE);
        var result = codec.decode(codec.encode(stack));
        assertEquals(FluidReadResult.Status.PRESENT, result.status()); assertEquals(stack, result.stack()); assertTrue(result.usable());
    }
    @Test void knownComponentCodecIsValidatedWithoutChangingStoredBytes() {
        var registry = new FluidRegistry(); registry.registerComponentCodec("test", TEMPERATURE, INTEGER);
        var codec = new FluidStackCodec(registry);
        var stack = FluidStack.of(WATER.withComponent(TEMPERATURE, ComponentValue.of(INTEGER.encode(500))), 1000);
        var encoded = codec.encode(stack);
        var result = codec.decode(encoded);
        assertEquals(FluidReadResult.Status.PRESENT, result.status()); assertEquals(stack, result.stack());
        assertArrayEquals(encoded, codec.encode(result.stack()));
    }
    @Test void unknownFluidAndComponentKeepOriginalRecord() {
        var codec = new FluidStackCodec(new FluidRegistry());
        var unknown = FluidStack.of(FluidVariant.of("addon:honey").withComponent(TEMPERATURE, ComponentValue.of(new byte[]{1, 2})), 2000);
        byte[] encoded = codec.encode(unknown);
        var result = codec.decode(encoded);
        assertEquals(FluidReadResult.Status.UNKNOWN, result.status()); assertEquals(unknown, result.stack()); assertFalse(result.usable());
        assertArrayEquals(encoded, result.raw());
        result.raw()[0] = 0;
        assertArrayEquals(encoded, result.raw());
    }
    @Test void malformedAndOversizedRecordsRemainAvailable() {
        var codec = new FluidStackCodec(new FluidRegistry(), 64);
        byte[] malformed = {1, 2, 3};
        assertEquals(FluidReadResult.Status.INVALID, codec.decode(malformed).status());
        assertArrayEquals(malformed, codec.decode(malformed).raw());
        byte[] huge = new byte[100];
        assertArrayEquals(huge, codec.decode(huge).raw());
        assertThrows(IllegalArgumentException.class, () -> codec.encode(FluidStack.of(WATER.withComponent(TEMPERATURE, ComponentValue.of(huge)), 1)));
    }
    @Test void malformedKnownComponentFailsWithoutDiscardingOriginalBytes() {
        var registry = new FluidRegistry(); registry.registerComponentCodec("test", TEMPERATURE, INTEGER);
        var codec = new FluidStackCodec(registry);
        byte[] encoded = codec.encode(FluidStack.of(WATER.withComponent(TEMPERATURE, ComponentValue.of(new byte[]{1})), 1));
        assertEquals(FluidReadResult.Status.INVALID, codec.decode(encoded).status());
        assertArrayEquals(encoded, codec.decode(encoded).raw());
    }
    @Test void futureFormatIsUnknownAndOriginalBytesArePreserved() {
        var codec = new FluidStackCodec(new FluidRegistry());
        byte[] encoded = codec.encode(FluidStack.of(WATER, 1));
        ByteBuffer.wrap(encoded).putInt(4, 999);
        var result = codec.decode(encoded);
        assertEquals(FluidReadResult.Status.UNKNOWN, result.status()); assertArrayEquals(encoded, result.raw());
    }
    @Test void olderVersionMigrationIsExplicitAndLossless() {
        var codec = new FluidStackCodec(new FluidRegistry());
        byte[] original = codec.encode(FluidStack.of(WATER, 10));
        ByteBuffer.wrap(original).putInt(4, 0);
        codec.registerMigration(0, record -> { ByteBuffer.wrap(record).putInt(4, 1); return record; });
        var result = codec.decode(original);
        assertEquals(FluidReadResult.Status.PRESENT, result.status()); assertEquals(10, result.stack().amount()); assertArrayEquals(original, result.raw());
    }
    @Test void unorderedComponentsHaveDeterministicEncoding() {
        var codec = new FluidStackCodec(new FluidRegistry());
        var a = new LinkedHashMap<FluidKey, ComponentValue>(); var b = new LinkedHashMap<FluidKey, ComponentValue>();
        a.put(FluidKey.of("test:a"), ComponentValue.of(new byte[]{1})); a.put(FluidKey.of("test:b"), ComponentValue.of(new byte[]{2}));
        b.put(FluidKey.of("test:b"), ComponentValue.of(new byte[]{2})); b.put(FluidKey.of("test:a"), ComponentValue.of(new byte[]{1}));
        assertArrayEquals(codec.encode(FluidStack.of(FluidVariant.of(WATER.fluid(), a), 100)), codec.encode(FluidStack.of(FluidVariant.of(WATER.fluid(), b), 100)));
    }
    @Test void trailingBytesAreRejected() {
        var codec = new FluidStackCodec(new FluidRegistry());
        byte[] encoded = codec.encode(FluidStack.of(WATER, 1));
        assertEquals(FluidReadResult.Status.INVALID, codec.decode(Arrays.copyOf(encoded, encoded.length + 1)).status());
    }
    @Test void legalLookingBitCorruptionIsDetectedByChecksum() {
        var codec = new FluidStackCodec(new FluidRegistry());
        byte[] encoded = codec.encode(FluidStack.of(WATER, 1000));
        encoded[encoded.length - 9] ^= 1;
        var result = codec.decode(encoded);
        assertEquals(FluidReadResult.Status.INVALID, result.status());
        assertTrue(result.message().contains("checksum"));
        assertArrayEquals(encoded, result.raw());
    }
}
