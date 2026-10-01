/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.async;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Hands pure work back to a current owner executor and rejects stale results. */
public final class SnapshotCoordinator {
    private SnapshotCoordinator() {}

    public static <T> CompletableFuture<T> computeAndApply(SnapshotWorkQueue queue,
            Callable<T> pureWork, Executor ownerExecutor, long expectedVersion, long expectedGeneration,
            LongSupplier currentVersion, LongSupplier currentGeneration, Consumer<T> apply) {
        Objects.requireNonNull(ownerExecutor);
        CompletableFuture<T> result = queue.track(new CompletableFuture<>());
        CompletableFuture<T> computed = queue.submit(pureWork);
        result.whenComplete((ignored, failure) -> { if (result.isCancelled()) computed.cancel(false); });
        computed.whenComplete((value, failure) -> {
            if (failure != null) { result.completeExceptionally(failure); return; }
            if (result.isDone()) return;
            try {
                ownerExecutor.execute(() -> {
                    if (result.isDone()) return;
                    try {
                        if (queue.isClosed()) throw new CancellationException("FluidCore stopped");
                        if (currentVersion.getAsLong() != expectedVersion
                                || currentGeneration.getAsLong() != expectedGeneration)
                            throw new StaleSnapshotException();
                        apply.accept(value);
                        result.complete(value);
                    } catch (Throwable exception) { result.completeExceptionally(exception); }
                });
            } catch (Throwable exception) { result.completeExceptionally(exception); }
        });
        return result;
    }

    public static final class StaleSnapshotException extends IllegalStateException {
        public StaleSnapshotException() { super("Snapshot version or registry generation changed"); }
    }
}
