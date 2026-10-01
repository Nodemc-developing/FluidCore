package com.ydxc20091.fluidcore.api;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable descriptive metadata, independent of per-stack components and platform behavior.
 * Density uses kg/m³ (negative values represent buoyant fluids), viscosity uses mPa·s,
 * temperature uses Kelvin, and color uses a 32-bit ARGB value.
 * Copyright 2026 ydxc20091.
 */
public final class FluidProperties {
    private static final FluidProperties DEFAULTS = new Builder().build();
    private final int density, viscosity, temperature, lightLevel;
    private final Integer color;
    private final String texture;
    private final FluidKey bucketItem;
    private final FluidRarity rarity;
    private final Map<FluidSound, FluidKey> sounds;

    private FluidProperties(Builder builder) {
        if (builder.viscosity < 0) throw new IllegalArgumentException("Viscosity must be nonnegative");
        if (builder.temperature < 0) throw new IllegalArgumentException("Temperature must be nonnegative Kelvin");
        if (builder.lightLevel < 0 || builder.lightLevel > 15) throw new IllegalArgumentException("Light level must be in [0,15]");
        density = builder.density; viscosity = builder.viscosity; temperature = builder.temperature;
        lightLevel = builder.lightLevel; color = builder.color; texture = builder.texture;
        bucketItem = builder.bucketItem; rarity = builder.rarity; sounds = Map.copyOf(builder.sounds);
    }

    public static FluidProperties defaults() { return DEFAULTS; }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() {
        Builder builder = new Builder().density(density).viscosity(viscosity).temperature(temperature)
            .lightLevel(lightLevel).rarity(rarity);
        builder.color = color; builder.texture = texture; builder.bucketItem = bucketItem;
        builder.sounds.putAll(sounds); return builder;
    }
    public int density() { return density; }
    public int viscosity() { return viscosity; }
    public int temperature() { return temperature; }
    public int lightLevel() { return lightLevel; }
    public OptionalInt color() { return color == null ? OptionalInt.empty() : OptionalInt.of(color); }
    public Optional<String> texture() { return Optional.ofNullable(texture); }
    public Optional<FluidKey> bucketItem() { return Optional.ofNullable(bucketItem); }
    public FluidRarity rarity() { return rarity; }
    public Optional<FluidKey> sound(FluidSound event) { return Optional.ofNullable(sounds.get(Objects.requireNonNull(event, "event"))); }
    public Map<FluidSound, FluidKey> sounds() { return sounds; }

    @Override public boolean equals(Object other) {
        return other instanceof FluidProperties value && density == value.density && viscosity == value.viscosity
            && temperature == value.temperature && lightLevel == value.lightLevel && Objects.equals(color, value.color)
            && Objects.equals(texture, value.texture) && Objects.equals(bucketItem, value.bucketItem)
            && rarity == value.rarity && sounds.equals(value.sounds);
    }
    @Override public int hashCode() { return Objects.hash(density, viscosity, temperature, lightLevel, color, texture, bucketItem, rarity, sounds); }
    @Override public String toString() { return "FluidProperties[density=" + density + ", viscosity=" + viscosity + ", temperature=" + temperature + ", lightLevel=" + lightLevel + ", rarity=" + rarity + "]"; }

    public static final class Builder {
        private int density = 1000, viscosity = 1000, temperature = 300, lightLevel;
        private Integer color;
        private String texture;
        private FluidKey bucketItem;
        private FluidRarity rarity = FluidRarity.COMMON;
        private final EnumMap<FluidSound, FluidKey> sounds = new EnumMap<>(FluidSound.class);
        private Builder() { }
        public Builder density(int value) { density = value; return this; }
        public Builder viscosity(int value) { viscosity = value; return this; }
        public Builder temperature(int value) { temperature = value; return this; }
        public Builder lightLevel(int value) { lightLevel = value; return this; }
        public Builder color(int argb) { color = argb; return this; }
        public Builder withoutColor() { color = null; return this; }
        public Builder texture(String value) {
            Objects.requireNonNull(value, "texture");
            if (value.isBlank()) throw new IllegalArgumentException("Texture must not be blank");
            texture = value; return this;
        }
        public Builder withoutTexture() { texture = null; return this; }
        public Builder bucketItem(FluidKey value) { bucketItem = Objects.requireNonNull(value, "bucketItem"); return this; }
        public Builder bucketItem(String value) { return bucketItem(FluidKey.of(value)); }
        public Builder withoutBucketItem() { bucketItem = null; return this; }
        public Builder rarity(FluidRarity value) { rarity = Objects.requireNonNull(value, "rarity"); return this; }
        public Builder sound(FluidSound event, FluidKey key) { sounds.put(Objects.requireNonNull(event, "event"), Objects.requireNonNull(key, "key")); return this; }
        public Builder sound(FluidSound event, String key) { return sound(event, FluidKey.of(key)); }
        public Builder withoutSound(FluidSound event) { sounds.remove(Objects.requireNonNull(event, "event")); return this; }
        public FluidProperties build() { return new FluidProperties(this); }
    }
}
