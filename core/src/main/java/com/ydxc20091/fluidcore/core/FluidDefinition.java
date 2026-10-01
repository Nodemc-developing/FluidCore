package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.OptionalInt;

public record FluidDefinition(FluidKey key, String displayName, Set<FluidKey> tags, FluidProperties properties) {
    public FluidDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
        Objects.requireNonNull(properties, "properties");
    }
    public FluidDefinition(FluidKey key, String displayName, Set<FluidKey> tags) { this(key, displayName, tags, FluidProperties.defaults()); }
    public static Builder builder(FluidKey key) { return new Builder(key); }
    public static Builder builder(String key) { return builder(FluidKey.of(key)); }
    public int density() { return properties.density(); }
    public int viscosity() { return properties.viscosity(); }
    public int temperature() { return properties.temperature(); }
    public int lightLevel() { return properties.lightLevel(); }
    public OptionalInt color() { return properties.color(); }
    public Optional<String> texture() { return properties.texture(); }
    public Optional<FluidKey> bucketItem() { return properties.bucketItem(); }
    public FluidRarity rarity() { return properties.rarity(); }
    public Optional<FluidKey> sound(FluidSound event) { return properties.sound(event); }
    public static final class Builder {
        private final FluidKey key;
        private String displayName;
        private Set<FluidKey> tags = Set.of();
        private FluidProperties properties = FluidProperties.defaults();
        private Builder(FluidKey key) { this.key = Objects.requireNonNull(key, "key"); displayName = key.toString(); }
        public Builder displayName(String value) { displayName = Objects.requireNonNull(value, "displayName"); return this; }
        public Builder tags(Set<FluidKey> value) { tags = Set.copyOf(value); return this; }
        public Builder tags(FluidKey... value) { return tags(Set.of(value)); }
        public Builder properties(FluidProperties value) { properties = Objects.requireNonNull(value, "properties"); return this; }
        public FluidDefinition build() { return new FluidDefinition(key, displayName, tags, properties); }
    }
}
