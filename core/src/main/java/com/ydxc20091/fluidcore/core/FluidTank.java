package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import java.util.Objects;
import java.util.function.Predicate;

/** A zero-ticker tank. Copyright 2026 ydxc20091. */
public final class FluidTank implements FluidStorage, TransactionParticipant {
    private final long capacity;
    private final StorageContext context;
    private final Predicate<FluidVariant> filter;
    private final Runnable onCommit;
    private FluidVariant variant;
    private long amount;
    private FluidVariant committedVariant;
    private long committedAmount;
    private FluidStack contentCache = FluidStack.EMPTY;
    private long version;

    public FluidTank(long capacity, StorageContext context) { this(capacity, context, variant -> true, () -> {}); }

    public FluidTank(long capacity, StorageContext context, Predicate<FluidVariant> filter, Runnable onCommit) {
        if (capacity < 0) throw new IllegalArgumentException("Negative capacity");
        this.capacity = capacity;
        this.context = Objects.requireNonNull(context, "context");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.onCommit = Objects.requireNonNull(onCommit, "onCommit");
    }

    @Override public int tanks() { context.checkAccess(); return 1; }
    @Override public FluidStack content(int tank) {
        checkTank(tank);
        if (contentCache == null) contentCache = FluidStack.of(variant, amount);
        return contentCache;
    }
    @Override public long capacity(int tank) { checkTank(tank); return capacity; }
    @Override public boolean isFluidValid(int tank, FluidVariant variant) { checkTank(tank); return filter.test(Objects.requireNonNull(variant, "variant")); }
    @Override public StorageContext context() { return context; }
    public long version() { context.checkAccess(); return version; }
    public long amount() { context.checkAccess(); return amount; }
    public long capacity() { context.checkAccess(); return capacity; }
    public long space() { context.checkAccess(); return amount >= capacity ? 0 : capacity - amount; }
    public boolean isEmpty() { context.checkAccess(); return amount == 0; }
    public FluidStack current() { return content(0); }

    private void checkTank(int tank) {
        context.checkAccess();
        if (tank != 0) throw new IndexOutOfBoundsException("Single tank index: " + tank);
    }

    /** A single local tank needs no speculative snapshot for a synchronous fill. */
    @Override public long fill(FluidStack stack, FluidAction action) {
        context.checkAccess();
        Objects.requireNonNull(stack, "stack"); Objects.requireNonNull(action, "action");
        if (stack.isEmpty()) return 0;
        if (FluidTransaction.hasOpenTransaction()) throw new IllegalStateException("Use insert() inside an existing transaction");
        long tick = context.tick();
        long accepted = insertable(stack.variant(), stack.amount());
        context.checkAccess();
        if (context.tick() != tick) throw new StorageAccessException("A fluid operation must not cross a tick");
        if (accepted > 0 && action == FluidAction.EXECUTE) {
            setContent(stack.variant(), Math.addExact(amount, accepted));
            notifyCommitted();
        }
        return accepted;
    }

    @Override public FluidStack drain(FluidVariant variant, long maximum, FluidAction action) {
        context.checkAccess();
        Objects.requireNonNull(variant, "variant"); Objects.requireNonNull(action, "action");
        if (maximum < 0) throw new IllegalArgumentException("Negative maximum");
        if (FluidTransaction.hasOpenTransaction()) throw new IllegalStateException("Use extract() inside an existing transaction");
        if (amount == 0 || !this.variant.equals(variant) || maximum == 0) return FluidStack.EMPTY;
        long extracted = Math.min(maximum, amount);
        FluidStack result = FluidStack.of(variant, extracted);
        if (action == FluidAction.EXECUTE) {
            setContent(variant, amount - extracted);
            notifyCommitted();
        }
        return result;
    }

    private void notifyCommitted() {
        try { afterCommit(); }
        catch (RuntimeException exception) { throw new FluidTransaction.CommitNotificationException("State committed, but a notification failed", exception); }
    }

    private long insertable(FluidVariant variant, long amount) {
        if (amount == 0 || !filter.test(variant) || (this.amount != 0 && !this.variant.equals(variant))) return 0;
        return this.amount >= capacity ? 0 : Math.min(amount, capacity - this.amount);
    }

    @Override public long insert(FluidVariant variant, long amount, FluidTransaction transaction) {
        checkArguments(variant, amount, transaction);
        long accepted = insertable(variant, amount);
        if (accepted == 0) return 0;
        transaction.enlist(this);
        setContent(variant, Math.addExact(this.amount, accepted));
        return accepted;
    }

    @Override public long extract(FluidVariant variant, long amount, FluidTransaction transaction) {
        checkArguments(variant, amount, transaction);
        if (amount == 0 || this.amount == 0 || !this.variant.equals(variant)) return 0;
        long extracted = Math.min(amount, this.amount);
        transaction.enlist(this);
        setContent(variant, this.amount - extracted);
        return extracted;
    }

    private void checkArguments(FluidVariant variant, long amount, FluidTransaction transaction) {
        context.checkAccess();
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(transaction, "transaction").checkOpen();
        if (amount < 0) throw new IllegalArgumentException("Negative amount");
    }

    /** Restore persisted data verbatim, including an over-capacity or unknown variant. */
    public void restore(FluidStack stack) {
        context.checkAccess();
        if (FluidTransaction.hasOpenTransaction()) throw new IllegalStateException("Cannot load persisted state during a transaction");
        Objects.requireNonNull(stack, "stack");
        setContent(stack.variant(), stack.amount());
        contentCache = stack;
        committedVariant = variant;
        committedAmount = amount;
        version = Math.incrementExact(version);
    }

    @Override public Object snapshot() {
        context.checkAccess();
        return new Snapshot(variant, amount, version, context.tick());
    }

    @Override public void restore(Object snapshot) {
        Snapshot value = (Snapshot) snapshot;
        setContent(value.variant, value.amount);
        version = value.version;
    }

    @Override public void validate() { context.checkAccess(); }
    @Override public void validateSnapshot(Object snapshot) {
        context.checkAccess();
        Snapshot value = (Snapshot) snapshot;
        if (context.tick() != value.tick) throw new StorageAccessException("A fluid transaction must not cross a tick");
        if (version != value.version) throw new IllegalStateException("Storage revision changed during transaction");
    }

    @Override public void afterCommit() {
        if (amount != committedAmount || !Objects.equals(variant, committedVariant)) {
            committedVariant = variant;
            committedAmount = amount;
            version = Math.incrementExact(version);
            onCommit.run();
        }
    }

    private void setContent(FluidVariant variant, long amount) {
        this.variant = amount == 0 ? null : variant;
        this.amount = amount;
        contentCache = amount == 0 ? FluidStack.EMPTY : null;
    }

    private record Snapshot(FluidVariant variant, long amount, long version, long tick) {}
}
