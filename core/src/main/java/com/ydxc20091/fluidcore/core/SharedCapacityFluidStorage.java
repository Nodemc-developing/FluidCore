/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Several independent fluid identities share one capacity budget. Slot contents never expose
 * mutable child tanks, so ordinary fills, transactions and storage views use the same budget.
 * capacity(index) describes the shared physical capacity, not a budget to sum across slots.
 */
public final class SharedCapacityFluidStorage implements FluidStorage, TransactionParticipant {
    private final StorageContext context;
    private final Predicate<FluidVariant> filter;
    private final Runnable onCommit;
    private final FluidStack[] contents;
    private long capacity, amount, version;
    private List<FluidStack> committed;
    private long committedCapacity;

    public SharedCapacityFluidStorage(long capacity, int tanks, StorageContext context) {
        this(capacity, tanks, context, variant -> true, () -> { });
    }

    public SharedCapacityFluidStorage(long capacity, int tanks, StorageContext context,
                                      Predicate<FluidVariant> filter, Runnable onCommit) {
        if (capacity < 0) throw new IllegalArgumentException("Negative shared capacity");
        if (tanks <= 0) throw new IllegalArgumentException("At least one fluid slot is required");
        this.context = Objects.requireNonNull(context, "context");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.onCommit = Objects.requireNonNull(onCommit, "onCommit");
        this.capacity = committedCapacity = capacity;
        contents = new FluidStack[tanks]; Arrays.fill(contents, FluidStack.EMPTY);
        committed = immutableContents();
    }

    @Override public StorageContext context() { return context; }
    @Override public int tanks() { context.checkAccess(); return contents.length; }
    @Override public FluidStack content(int tank) { checkTank(tank); return contents[tank]; }
    @Override public long capacity(int tank) { checkTank(tank); return capacity; }
    @Override public boolean isFluidValid(int tank, FluidVariant variant) {
        checkTank(tank); return accepts(Objects.requireNonNull(variant, "variant"));
    }
    public long capacity() { context.checkAccess(); return capacity; }
    public long amount() { context.checkAccess(); return amount; }
    public long space() { context.checkAccess(); return amount >= capacity ? 0 : capacity - amount; }
    public long version() { context.checkAccess(); return version; }
    public List<FluidStack> contents() { context.checkAccess(); return immutableContents(); }

    @Override public long insert(FluidVariant variant, long requested, FluidTransaction transaction) {
        checkArguments(variant, requested, transaction);
        if (requested == 0 || amount >= capacity || !accepts(variant)) return 0;
        int slot = -1, empty = -1;
        for (int index = 0; index < contents.length; index++) {
            if (contents[index].isEmpty()) { if (empty < 0) empty = index; }
            else if (contents[index].variant().equals(variant)) { slot = index; break; }
        }
        if (slot < 0) slot = empty;
        if (slot < 0) return 0;
        long accepted = Math.min(requested, capacity - amount);
        transaction.enlist(this);
        contents[slot] = FluidStack.of(variant, Math.addExact(contents[slot].amount(), accepted));
        amount = Math.addExact(amount, accepted);
        return accepted;
    }

    @Override public long extract(FluidVariant variant, long maximum, FluidTransaction transaction) {
        checkArguments(variant, maximum, transaction);
        long removed = 0;
        for (int index = 0; index < contents.length && removed < maximum; index++) {
            FluidStack current = contents[index];
            if (current.isEmpty() || !current.variant().equals(variant)) continue;
            long part = Math.min(maximum - removed, current.amount());
            if (part == 0) continue;
            transaction.enlist(this);
            contents[index] = current.shrink(part);
            amount -= part; removed = Math.addExact(removed, part);
        }
        return removed;
    }

    /** Shrinking preserves existing contents, even when their total is above the new limit. */
    public void resize(long capacity) {
        context.checkAccess();
        if (capacity < 0) throw new IllegalArgumentException("Negative shared capacity");
        if (FluidTransaction.hasOpenTransaction()) throw new IllegalStateException("Cannot resize during a transaction");
        if (this.capacity == capacity) return;
        this.capacity = capacity;
        try { afterCommit(); }
        catch (RuntimeException failure) { throw new FluidTransaction.CommitNotificationException("Capacity changed, but a notification failed", failure); }
    }

    /**
     * Loads a complete fixed-slot record without clipping known or unknown identities. Invalid
     * size, null entries or a total exceeding long range reject the whole record before mutation.
     */
    public void restore(List<FluidStack> saved) {
        context.checkAccess();
        if (FluidTransaction.hasOpenTransaction()) throw new IllegalStateException("Cannot restore persisted contents during a transaction");
        List<FluidStack> copy = List.copyOf(Objects.requireNonNull(saved, "saved"));
        if (copy.size() != contents.length) throw new IllegalArgumentException("Persisted slot count does not match this storage");
        long restored = 0;
        for (FluidStack stack : copy) restored = Math.addExact(restored, stack.amount());
        long nextVersion = Math.incrementExact(version);
        for (int index = 0; index < contents.length; index++) contents[index] = copy.get(index);
        amount = restored; committed = copy; committedCapacity = capacity; version = nextVersion;
    }

    @Override public Object snapshot() {
        context.checkAccess(); return new Snapshot(immutableContents(), amount, capacity, version, context.tick());
    }
    @Override public void restore(Object snapshot) {
        Snapshot saved = (Snapshot) snapshot;
        for (int index = 0; index < contents.length; index++) contents[index] = saved.contents.get(index);
        amount = saved.amount; capacity = saved.capacity; version = saved.version;
    }
    @Override public void validate() { context.checkAccess(); }
    @Override public void validateSnapshot(Object snapshot) {
        context.checkAccess(); Snapshot saved = (Snapshot) snapshot;
        if (saved.tick != context.tick()) throw new StorageAccessException("A fluid transaction must not cross a tick");
        if (saved.version != version || saved.capacity != capacity) throw new IllegalStateException("Storage revision changed during transaction");
    }
    @Override public void afterCommit() {
        if (capacity != committedCapacity || !committed.equals(Arrays.asList(contents))) {
            long nextVersion = Math.incrementExact(version);
            committed = immutableContents(); committedCapacity = capacity; version = nextVersion;
            onCommit.run();
        }
    }
    private List<FluidStack> immutableContents() { return List.copyOf(Arrays.asList(contents)); }
    private void checkTank(int tank) {
        context.checkAccess();
        if (tank < 0 || tank >= contents.length) throw new IndexOutOfBoundsException(tank);
    }
    private void checkArguments(FluidVariant variant, long amount, FluidTransaction transaction) {
        context.checkAccess(); Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(transaction, "transaction").checkOpen();
        if (amount < 0) throw new IllegalArgumentException("Negative fluid amount");
    }
    private boolean accepts(FluidVariant variant) {
        long tick = context.tick();
        boolean accepted = filter.test(variant);
        context.checkAccess();
        if (tick != context.tick()) throw new StorageAccessException("A fluid operation must not cross a tick");
        return accepted;
    }
    private record Snapshot(List<FluidStack> contents, long amount, long capacity, long version, long tick) { }
}
