/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Change-driven owner queues. A region has at most one outstanding scheduled drain. */
final class RegionChangeBatcher<K, V> implements AutoCloseable {
    private final ConcurrentHashMap<K, Batch<V>> batches = new ConcurrentHashMap<>();
    private final BiConsumer<K, Runnable> scheduler;
    private final BiConsumer<V, Long> apply;
    private final Consumer<RuntimeException> failures;
    private final int budget;
    private volatile boolean closed;

    RegionChangeBatcher(int budget, BiConsumer<K, Runnable> scheduler,
                        BiConsumer<V, Long> apply, Consumer<RuntimeException> failures) {
        if (budget < 1) throw new IllegalArgumentException("The update budget must be positive");
        this.budget = budget;
        this.scheduler = Objects.requireNonNull(scheduler);
        this.apply = Objects.requireNonNull(apply);
        this.failures = Objects.requireNonNull(failures);
    }

    void mark(K region, V value, long activation) {
        Objects.requireNonNull(region); Objects.requireNonNull(value);
        if (closed) return;
        List<Batch<V>> scheduled = new ArrayList<>(1);
        batches.compute(region, (key, existing) -> {
            if (closed) return null;
            Batch<V> batch = existing == null ? new Batch<>() : existing;
            batch.entries.put(value, activation);
            if (!batch.scheduled) { batch.scheduled = true; scheduled.add(batch); }
            return batch;
        });
        if (!scheduled.isEmpty()) schedule(region, scheduled.getFirst());
    }

    private void schedule(K region, Batch<V> expected) {
        if (closed) return;
        try { scheduler.accept(region, () -> drain(region, expected)); }
        catch (RuntimeException failure) {
            batches.computeIfPresent(region, (key, batch) -> {
                if (batch == expected) batch.scheduled = false;
                return batch;
            });
            failures.accept(failure);
        }
    }

    private void drain(K region, Batch<V> expected) {
        if (closed) return;
        List<Change<V>> work = new ArrayList<>(budget);
        batches.computeIfPresent(region, (key, batch) -> {
            if (batch != expected) return batch;
            var iterator = batch.entries.entrySet().iterator();
            while (iterator.hasNext() && work.size() < budget) {
                var entry = iterator.next();
                work.add(new Change<>(entry.getKey(), entry.getValue()));
                iterator.remove();
            }
            return batch;
        });
        for (Change<V> change : work) {
            if (closed) break;
            try { apply.accept(change.value(), change.activation()); }
            catch (RuntimeException failure) { failures.accept(failure); }
        }
        List<Batch<V>> remaining = new ArrayList<>(1);
        batches.computeIfPresent(region, (key, batch) -> {
            if (closed || batch != expected || batch.entries.isEmpty()) return batch == expected ? null : batch;
            remaining.add(batch);
            return batch;
        });
        if (!remaining.isEmpty()) schedule(region, remaining.getFirst());
    }

    int pendingRegions() { return batches.size(); }
    @Override public void close() { closed = true; batches.clear(); }
    private static final class Batch<V> {
        final LinkedHashMap<V, Long> entries = new LinkedHashMap<>();
        boolean scheduled;
    }
    private record Change<V>(V value, long activation) { }
}
