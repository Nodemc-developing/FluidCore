package com.ydxc20091.fluidcore.api;

import java.util.Map;
import java.util.Objects;

/** Fluid identity is separate from amount. Components are immutable encoded values. */
public record FluidVariant(FluidKey fluid, Map<FluidKey, ComponentValue> components) {
    public FluidVariant {
        Objects.requireNonNull(fluid, "fluid");
        components = Map.copyOf(Objects.requireNonNull(components, "components"));
    }

    public static FluidVariant of(String fluid) { return of(FluidKey.of(fluid)); }
    public static FluidVariant of(FluidKey fluid) { return new FluidVariant(fluid, Map.of()); }
    public static FluidVariant of(FluidKey fluid, Map<FluidKey, ComponentValue> components) { return new FluidVariant(fluid, components); }
    public FluidVariant withComponent(FluidKey key, ComponentValue value) {
        var copy = new java.util.HashMap<>(components);
        copy.put(key, value);
        return new FluidVariant(fluid, copy);
    }
    public FluidVariant withoutComponent(FluidKey key) {
        Objects.requireNonNull(key, "key");
        if (!components.containsKey(key)) return this;
        var copy = new java.util.HashMap<>(components); copy.remove(key); return new FluidVariant(fluid, copy);
    }
    public <T> FluidVariant with(ComponentType<T> type, T value) {
        Objects.requireNonNull(type, "type"); return withComponent(type.key(), type.encode(value));
    }
    public <T> java.util.Optional<T> get(ComponentType<T> type) {
        Objects.requireNonNull(type, "type");
        ComponentValue encoded = components.get(type.key());
        return encoded == null ? java.util.Optional.empty() : java.util.Optional.of(type.decode(encoded));
    }
    public <T> T getOrDefault(ComponentType<T> type, T fallback) { return get(type).orElse(fallback); }
    public FluidVariant remove(ComponentType<?> type) { return withoutComponent(Objects.requireNonNull(type, "type").key()); }
    public FluidVariant without(ComponentType<?> type) { return remove(type); }
    public boolean sameFluid(FluidVariant other) { return other != null && fluid.equals(other.fluid); }
}
