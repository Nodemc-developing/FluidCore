package com.ydxc20091.fluidcore.api;

import java.util.Objects;

/**
 * Typed access to a namespaced encoded component. Constructing this type does not register its
 * codec for persistence; plugins must explicitly register it with their owning registry.
 * Copyright 2026 ydxc20091.
 */
public record ComponentType<T>(FluidKey key, ComponentCodec<T> codec) {
    public ComponentType { Objects.requireNonNull(key, "key"); Objects.requireNonNull(codec, "codec"); }
    public static <T> ComponentType<T> of(String key, ComponentCodec<T> codec) { return new ComponentType<>(FluidKey.of(key), codec); }
    public ComponentValue encode(T value) { return ComponentValue.of(Objects.requireNonNull(codec.encode(Objects.requireNonNull(value, "value")), "encoded")); }
    public T decode(ComponentValue value) { return Objects.requireNonNull(codec.decode(Objects.requireNonNull(value, "value").bytes()), "decoded"); }
}
