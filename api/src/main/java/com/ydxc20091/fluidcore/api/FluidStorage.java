package com.ydxc20091.fluidcore.api;

import java.util.Objects;

/** insert/extract tentatively change state inside the supplied native transaction. */
public interface FluidStorage {
    int tanks();
    FluidStack content(int tank);
    long capacity(int tank);
    long insert(FluidVariant variant, long amount, FluidTransaction transaction);
    long extract(FluidVariant variant, long amount, FluidTransaction transaction);

    /** Advisory insertion policy, independent of free capacity. Implementations with filters override this. */
    default boolean isFluidValid(int tank, FluidVariant variant) {
        context().checkAccess(); Objects.requireNonNull(variant, "variant");
        if (tank < 0 || tank >= tanks()) throw new IndexOutOfBoundsException(tank);
        return true;
    }

    default StorageContext context() { throw new UnsupportedOperationException("This storage does not expose an ownership context"); }
    default boolean supportsTransactions() { return true; }

    default long fill(FluidStack stack, FluidAction action) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(action, "action");
        if (stack.isEmpty()) return 0;
        try (FluidTransaction transaction = FluidTransaction.open()) {
            long accepted = insert(stack.variant(), stack.amount(), transaction);
            if (action == FluidAction.EXECUTE) transaction.commit();
            return accepted;
        }
    }

    default FluidStack drain(FluidVariant variant, long maximum, FluidAction action) {
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(action, "action");
        if (maximum < 0) throw new IllegalArgumentException("Negative maximum");
        try (FluidTransaction transaction = FluidTransaction.open()) {
            long extracted = extract(variant, maximum, transaction);
            if (action == FluidAction.EXECUTE) transaction.commit();
            return FluidStack.of(variant, extracted);
        }
    }
    default FluidStack drain(FluidStack requested, FluidAction action) {
        context().checkAccess(); Objects.requireNonNull(requested, "requested"); Objects.requireNonNull(action, "action");
        return requested.isEmpty() ? FluidStack.EMPTY : drain(requested.variant(), requested.amount(), action);
    }
    /** Selects the first tank identity that can actually be extracted by this port. */
    default FluidStack drain(long maximum, FluidAction action) {
        context().checkAccess(); Objects.requireNonNull(action, "action");
        if (maximum < 0) throw new IllegalArgumentException("Negative maximum");
        if (maximum == 0) return FluidStack.EMPTY;
        try (FluidTransaction transaction = FluidTransaction.open()) {
            for (int tank = 0, count = tanks(); tank < count; tank++) {
                FluidStack current = content(tank);
                if (current.isEmpty()) continue;
                long extracted;
                try (FluidTransaction probe = transaction.openNested()) {
                    extracted = extract(current.variant(), maximum, probe);
                    if (extracted < 0 || extracted > maximum) throw new IllegalStateException("Storage violated amount contract");
                    if (extracted > 0) probe.commit();
                }
                if (extracted > 0) {
                    if (action.execute()) transaction.commit();
                    return FluidStack.of(current.variant(), extracted);
                }
            }
            return FluidStack.EMPTY;
        }
    }
}
