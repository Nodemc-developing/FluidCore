package com.ydxc20091.fluidcore.ui;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** Keeps detached display values; unchanged slots allocate no copies or replacement UI items. */
final class TankMenuRenderCache<T> {
    private final Object[] values;
    private final boolean[] initialized;
    private final UnaryOperator<T> copy;
    private long updates;
    TankMenuRenderCache(int size, UnaryOperator<T> copy) {
        values = new Object[size]; initialized = new boolean[size]; this.copy = Objects.requireNonNull(copy);
    }
    boolean update(int slot, T value) {
        if (initialized[slot] && Objects.equals(values[slot], value)) return false;
        values[slot] = value == null ? null : copy.apply(value); initialized[slot] = true; updates++; return true;
    }
    @SuppressWarnings("unchecked") T value(int slot) { return (T) values[slot]; }
    long updates() { return updates; }
}
