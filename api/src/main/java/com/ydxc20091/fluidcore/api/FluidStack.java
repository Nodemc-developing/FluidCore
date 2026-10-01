package com.ydxc20091.fluidcore.api;

import java.util.Objects;

/** Zero amount has one representation. variant() is null only on EMPTY. */
public record FluidStack(FluidVariant variant, long amount) {
    public static final long BUCKET = 1000L;
    public static final long BOTTLE = 250L;
    public static final FluidStack EMPTY = new FluidStack(null, 0);

    public FluidStack {
        if (amount < 0) throw new IllegalArgumentException("Negative fluid amount");
        if (amount == 0) variant = null;
        else Objects.requireNonNull(variant, "variant");
    }

    public static FluidStack of(FluidVariant variant, long amount) {
        return amount == 0 ? EMPTY : new FluidStack(variant, amount);
    }
    public boolean isEmpty() { return amount == 0; }
    public FluidStack withAmount(long amount) { return of(variant, amount); }
    public FluidStack grow(long delta) {
        nonnegative(delta); if (delta == 0) return this;
        if (isEmpty()) throw new IllegalStateException("An empty stack has no fluid identity to grow");
        return withAmount(Math.addExact(amount, delta));
    }
    /** Removes up to delta, retaining the canonical empty representation. */
    public FluidStack shrink(long delta) { nonnegative(delta); return delta == 0 ? this : withAmount(amount - Math.min(amount, delta)); }
    public FluidStack limitSize(long maximum) { nonnegative(maximum); return amount <= maximum ? this : withAmount(maximum); }
    public Split split(long requested) {
        nonnegative(requested); long taken = Math.min(requested, amount);
        return new Split(withAmount(taken), withAmount(amount - taken));
    }
    public record Split(FluidStack taken, FluidStack remainder) { }
    /** Two empty stacks have the same identity; components are ignored. */
    public boolean sameFluid(FluidStack other) {
        Objects.requireNonNull(other, "other");
        return isEmpty() ? other.isEmpty() : !other.isEmpty() && variant.sameFluid(other.variant);
    }
    /** Two empty stacks have the same variant; amount is ignored. */
    public boolean sameVariant(FluidStack other) { return java.util.Objects.equals(variant, Objects.requireNonNull(other, "other").variant); }
    public boolean matches(FluidStack other) { return equals(Objects.requireNonNull(other, "other")); }
    public <T> java.util.Optional<T> get(ComponentType<T> type) {
        Objects.requireNonNull(type, "type"); return isEmpty() ? java.util.Optional.empty() : variant.get(type);
    }
    public <T> T getOrDefault(ComponentType<T> type, T fallback) { return get(type).orElse(fallback); }
    public <T> FluidStack with(ComponentType<T> type, T value) {
        Objects.requireNonNull(type, "type"); Objects.requireNonNull(value, "value");
        if (isEmpty()) throw new IllegalStateException("An empty stack has no fluid identity for components");
        return of(variant.with(type, value), amount);
    }
    public FluidStack without(ComponentType<?> type) {
        Objects.requireNonNull(type, "type"); return isEmpty() ? this : of(variant.remove(type), amount);
    }
    private static void nonnegative(long value) { if (value < 0) throw new IllegalArgumentException("Amount must be nonnegative"); }
}
