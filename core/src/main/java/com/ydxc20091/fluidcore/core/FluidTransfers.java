package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import java.util.Objects;

/** Atomic local transfer. Probe rollback also accommodates output-only sources. */
public final class FluidTransfers {
    private FluidTransfers() {}

    /** Chooses one transferable identity in source-tank order; different fluids are never mixed. */
    public static TransferResult move(FluidStorage source, FluidStorage destination, long maximum, FluidAction action) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(destination, "destination"); Objects.requireNonNull(action, "action");
        if (maximum < 0) throw new IllegalArgumentException("Negative maximum");
        if (source == destination) return result(TransferResult.Status.REJECTED, 0, "Source and destination are identical");
        try {
            if (!source.supportsTransactions() || !destination.supportsTransactions()) return result(TransferResult.Status.UNSUPPORTED, 0, "Both participants must support native transactions");
            source.context().checkSameContext(destination.context());
            if (maximum == 0) return result(TransferResult.Status.EMPTY, 0, "Zero maximum");
            var visited = new java.util.HashSet<FluidVariant>();
            boolean found = false;
            for (int tank = 0, count = source.tanks(); tank < count; tank++) {
                FluidStack stack = source.content(tank);
                if (stack.isEmpty() || !visited.add(stack.variant())) continue;
                found = true;
                TransferResult transferred = move(source, destination, stack.variant(), maximum, action);
                if (transferred.status() != TransferResult.Status.EMPTY && transferred.status() != TransferResult.Status.REJECTED) return transferred;
            }
            return result(found ? TransferResult.Status.REJECTED : TransferResult.Status.EMPTY, 0, found ? "No source identity was accepted" : "Source is empty");
        } catch (StorageAccessException exception) { return result(TransferResult.Status.WRONG_CONTEXT, 0, exception.getMessage()); }
        catch (UnsupportedOperationException exception) { return result(TransferResult.Status.UNSUPPORTED, 0, exception.getMessage()); }
    }

    public static TransferResult move(FluidStorage source, FluidStorage destination, FluidVariant variant, long maximum, FluidAction action) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(variant, "variant"); Objects.requireNonNull(action, "action");
        if (maximum < 0) throw new IllegalArgumentException("Negative maximum");
        if (source == destination) return result(TransferResult.Status.REJECTED, 0, "Source and destination are identical");
        long transferred = 0;
        try {
            if (!source.supportsTransactions() || !destination.supportsTransactions()) return result(TransferResult.Status.UNSUPPORTED, 0, "Both participants must support native transactions");
            source.context().checkSameContext(destination.context());
            if (maximum == 0) return result(TransferResult.Status.EMPTY, 0, "Zero maximum");
            try (FluidTransaction root = FluidTransaction.open()) {
                long available;
                long accepted;
                try (FluidTransaction probe = root.openNested()) {
                    available = source.extract(variant, maximum, probe);
                    checkAmount(available, maximum);
                    accepted = destination.insert(variant, available, probe);
                    checkAmount(accepted, available);
                }
                if (available == 0) return result(TransferResult.Status.EMPTY, 0, "Source has no matching fluid");
                if (accepted == 0) return result(TransferResult.Status.REJECTED, 0, "Destination rejected the fluid");
                long removed = source.extract(variant, accepted, root);
                long inserted = destination.insert(variant, removed, root);
                if (removed != accepted || inserted != accepted) return result(TransferResult.Status.STALE, 0, "Storage changed its acceptance during the transfer");
                transferred = accepted;
                if (action == FluidAction.EXECUTE) root.commit();
                return result(TransferResult.Status.SUCCESS, transferred, action == FluidAction.SIMULATE ? "Simulated" : "Committed");
            }
        } catch (FluidTransaction.CommitNotificationException exception) {
            return result(TransferResult.Status.SUCCESS, transferred, "Committed; notification failed: " + exception.getCause().getClass().getSimpleName());
        } catch (StorageAccessException exception) {
            return result(TransferResult.Status.WRONG_CONTEXT, 0, exception.getMessage());
        } catch (UnsupportedOperationException exception) {
            return result(TransferResult.Status.UNSUPPORTED, 0, exception.getMessage());
        }
    }

    private static void checkAmount(long actual, long maximum) {
        if (actual < 0 || actual > maximum) throw new IllegalStateException("Storage violated amount contract");
    }
    private static TransferResult result(TransferResult.Status status, long amount, String message) { return new TransferResult(status, amount, message); }
}
