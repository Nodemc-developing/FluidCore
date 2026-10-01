/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.async;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded background work. Callers must pass detached, immutable input only. */
public final class SnapshotWorkQueue implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicLong rejected = new AtomicLong();
    private volatile boolean closed;

    public SnapshotWorkQueue(int workers, int capacity) {
        if (workers < 1 || capacity < 1) throw new IllegalArgumentException("Positive workers and capacity required");
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, "FluidCore-IO-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> CompletableFuture<T> submit(Callable<T> work) {
        java.util.Objects.requireNonNull(work, "work");
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) return CompletableFuture.failedFuture(new CancellationException("FluidCore is stopped"));
        track(result);
        try {
            executor.execute(() -> {
                if (result.isDone()) return;
                try { result.complete(work.call()); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException failure) {
            rejected.incrementAndGet();
            result.completeExceptionally(failure);
        }
        return result;
    }

    /** Tracks a dependent owner-thread completion so shutdown cannot strand it. */
    public <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pending.add(future);
        future.whenComplete((ignored, failure) -> pending.remove(future));
        if (closed) future.cancel(false);
        return future;
    }

    public int waiting() { return executor.getQueue().size(); }
    public int active() { return executor.getActiveCount(); }
    public long rejected() { return rejected.get(); }
    public boolean isClosed() { return closed; }

    @Override public void close() {
        closed = true;
        executor.shutdownNow();
        for (CompletableFuture<?> future : pending) future.cancel(false);
    }
}
