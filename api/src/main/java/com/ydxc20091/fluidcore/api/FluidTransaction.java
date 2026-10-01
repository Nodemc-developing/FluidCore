package com.ydxc20091.fluidcore.api;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** Same-thread, synchronous snapshot transaction. Close without commit rolls back. */
public final class FluidTransaction implements AutoCloseable {
    private static final ThreadLocal<FluidTransaction> CURRENT = new ThreadLocal<>();
    private final FluidTransaction parent;
    private final Thread owner = Thread.currentThread();
    private final IdentityHashMap<TransactionParticipant, Object> snapshots = new IdentityHashMap<>();
    private final List<TransactionParticipant> order = new ArrayList<>();
    private boolean open = true;
    private boolean committed;

    private FluidTransaction(FluidTransaction parent) { this.parent = parent; CURRENT.set(this); }

    public static FluidTransaction open() {
        if (CURRENT.get() != null) throw new IllegalStateException("A transaction is already open; use openNested()");
        return new FluidTransaction(null);
    }

    public static boolean hasOpenTransaction() { return CURRENT.get() != null; }

    public FluidTransaction openNested() { checkCurrent(); return new FluidTransaction(this); }

    public void enlist(TransactionParticipant participant) {
        checkCurrent();
        capture(Objects.requireNonNull(participant, "participant"));
    }

    private void capture(TransactionParticipant participant) {
        if (snapshots.containsKey(participant)) return;
        if (parent != null) parent.capture(participant);
        participant.validate();
        snapshots.put(participant, participant.snapshot());
        order.add(participant);
    }

    public void commit() {
        checkCurrent();
        for (TransactionParticipant participant : order) participant.validateSnapshot(snapshots.get(participant));
        committed = true;
        finish();
        if (parent == null) {
            RuntimeException failure = null;
            for (TransactionParticipant participant : order) {
                try { participant.afterCommit(); }
                catch (RuntimeException exception) {
                    if (failure == null) failure = new CommitNotificationException("State committed, but a notification failed", exception);
                    else failure.addSuppressed(exception);
                }
            }
            if (failure != null) throw failure;
        }
    }

    public boolean isOpen() { return open; }
    public boolean isCommitted() { return committed; }
    public void checkOpen() { checkCurrent(); }

    private void checkCurrent() {
        if (Thread.currentThread() != owner) throw new StorageAccessException("Transaction accessed from another thread");
        if (!open) throw new IllegalStateException("Transaction is closed");
        if (CURRENT.get() != this) throw new IllegalStateException("Close the nested transaction first");
    }

    private void finish() {
        open = false;
        snapshots.clear();
        if (parent == null) CURRENT.remove(); else CURRENT.set(parent);
    }

    @Override public void close() {
        if (!open) return;
        checkCurrent();
        RuntimeException failure = null;
        for (int index = order.size() - 1; index >= 0; index--) {
            var participant = order.get(index);
            try { participant.restore(snapshots.get(participant)); }
            catch (RuntimeException exception) {
                if (failure == null) failure = exception; else failure.addSuppressed(exception);
            }
        }
        finish();
        if (failure != null) throw failure;
    }

    /** State has already committed when this exception is thrown. */
    public static final class CommitNotificationException extends RuntimeException {
        public CommitNotificationException(String message, Throwable cause) { super(message, cause); }
    }
}
