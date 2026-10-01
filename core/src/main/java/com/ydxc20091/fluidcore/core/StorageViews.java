package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Composable ports. Wrappers never bypass a delegate's current ownership check. */
public final class StorageViews {
    private StorageViews() {}
    public enum Side { DOWN, UP, NORTH, SOUTH, WEST, EAST }
    public enum Access { NONE, INPUT, OUTPUT, BOTH }

    public static FluidStorage readonly(FluidStorage storage) { return access(storage, Access.NONE); }
    public static FluidStorage input(FluidStorage storage) { return access(storage, Access.INPUT); }
    public static FluidStorage output(FluidStorage storage) { return access(storage, Access.OUTPUT); }
    public static FluidStorage access(FluidStorage storage, Access access) {
        Objects.requireNonNull(access, "access");
        return new View(storage) {
            @Override public boolean isFluidValid(int tank, FluidVariant variant) {
                boolean valid = super.isFluidValid(tank, variant);
                return (access == Access.INPUT || access == Access.BOTH) && valid;
            }
            @Override public long insert(FluidVariant variant, long amount, FluidTransaction transaction) {
                check(variant, amount, transaction);
                return access == Access.INPUT || access == Access.BOTH ? delegate.insert(variant, amount, transaction) : 0;
            }
            @Override public long extract(FluidVariant variant, long amount, FluidTransaction transaction) {
                check(variant, amount, transaction);
                return access == Access.OUTPUT || access == Access.BOTH ? delegate.extract(variant, amount, transaction) : 0;
            }
        };
    }

    public static FluidStorage filter(FluidStorage storage, Predicate<FluidVariant> filter) {
        Objects.requireNonNull(filter, "filter");
        return new View(storage) {
            @Override public boolean isFluidValid(int tank, FluidVariant variant) { return super.isFluidValid(tank, variant) && filter.test(variant); }
            @Override public long insert(FluidVariant variant, long amount, FluidTransaction transaction) {
                check(variant, amount, transaction);
                return filter.test(variant) ? delegate.insert(variant, amount, transaction) : 0;
            }
            @Override public long extract(FluidVariant variant, long amount, FluidTransaction transaction) {
                check(variant, amount, transaction);
                return filter.test(variant) ? delegate.extract(variant, amount, transaction) : 0;
            }
        };
    }

    /** Create one persistent port per side; recreating a rate-limited view resets its budget. */
    public static Map<Side, FluidStorage> sided(FluidStorage storage, Map<Side, Access> access) {
        var result = new java.util.EnumMap<Side, FluidStorage>(Side.class);
        for (Side side : Side.values()) result.put(side, access(storage, access.getOrDefault(side, Access.NONE)));
        return Map.copyOf(result);
    }

    public static FluidStorage rateLimit(FluidStorage storage, long inputPerTick, long outputPerTick) {
        if (inputPerTick < 0 || outputPerTick < 0) throw new IllegalArgumentException("Negative rate");
        return new RateLimited(storage, inputPerTick, outputPerTick);
    }

    private abstract static class View implements FluidStorage {
        protected final FluidStorage delegate;
        View(FluidStorage delegate) { this.delegate = Objects.requireNonNull(delegate, "delegate"); }
        @Override public int tanks() { context().checkAccess(); return delegate.tanks(); }
        @Override public FluidStack content(int tank) { context().checkAccess(); return delegate.content(tank); }
        @Override public long capacity(int tank) { context().checkAccess(); return delegate.capacity(tank); }
        @Override public boolean isFluidValid(int tank, FluidVariant variant) { context().checkAccess(); return delegate.isFluidValid(tank, variant); }
        @Override public StorageContext context() { return delegate.context(); }
        @Override public boolean supportsTransactions() { return delegate.supportsTransactions(); }
        protected void check(FluidVariant variant, long amount, FluidTransaction transaction) {
            context().checkAccess();
            Objects.requireNonNull(variant, "variant");
            Objects.requireNonNull(transaction, "transaction").checkOpen();
            if (amount < 0) throw new IllegalArgumentException("Negative amount");
        }
    }

    private static final class RateLimited extends View implements TransactionParticipant {
        private final long inputRate;
        private final long outputRate;
        private long budgetTick = Long.MIN_VALUE;
        private long inserted;
        private long extracted;

        private RateLimited(FluidStorage storage, long inputRate, long outputRate) {
            super(storage); this.inputRate = inputRate; this.outputRate = outputRate;
        }

        @Override public long insert(FluidVariant variant, long amount, FluidTransaction transaction) { return move(variant, amount, transaction, true); }
        @Override public long extract(FluidVariant variant, long amount, FluidTransaction transaction) { return move(variant, amount, transaction, false); }
        @Override public boolean isFluidValid(int tank, FluidVariant variant) {
            boolean valid = super.isFluidValid(tank, variant);
            long used = context().tick() == budgetTick ? inserted : 0;
            return valid && inputRate > used;
        }
        private long move(FluidVariant variant, long amount, FluidTransaction transaction, boolean input) {
            check(variant, amount, transaction);
            transaction.enlist(this);
            long now = context().tick();
            if (now != budgetTick) { budgetTick = now; inserted = 0; extracted = 0; }
            long remaining = input ? inputRate - inserted : outputRate - extracted;
            long maximum = Math.min(amount, remaining);
            long moved = input ? delegate.insert(variant, maximum, transaction) : delegate.extract(variant, maximum, transaction);
            if (moved < 0 || moved > maximum) throw new IllegalStateException("Storage violated amount contract");
            if (input) inserted = Math.addExact(inserted, moved); else extracted = Math.addExact(extracted, moved);
            return moved;
        }
        @Override public Object snapshot() { return new Budget(budgetTick, inserted, extracted, context().tick()); }
        @Override public void restore(Object snapshot) {
            Budget value = (Budget) snapshot;
            budgetTick = value.budgetTick; inserted = value.inserted; extracted = value.extracted;
        }
        @Override public void validate() { context().checkAccess(); }
        @Override public void validateSnapshot(Object snapshot) {
            validate();
            if (((Budget) snapshot).enlistedTick != context().tick()) throw new StorageAccessException("Rate-limited transaction crossed a tick");
        }
        @Override public void afterCommit() {}
        private record Budget(long budgetTick, long inserted, long extracted, long enlistedTick) {}
    }
}
