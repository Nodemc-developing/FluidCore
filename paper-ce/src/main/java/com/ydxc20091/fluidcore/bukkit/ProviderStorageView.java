/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.*;
import java.util.Objects;

/** A revocable provider view; revoking it never replaces the delegate's rollback context. */
public final class ProviderStorageView implements FluidStorage {
    private final FluidStorage storage;
    private final StorageContext lease, delegated;
    private final StorageContext context;
    private final TransactionParticipant guard;

    public ProviderStorageView(FluidStorage storage, StorageContext lease) {
        this.storage = Objects.requireNonNull(storage); this.lease = Objects.requireNonNull(lease);
        this.delegated = storage.context();
        context = new StorageContext() {
            public void checkAccess() { check(); }
            public long tick() { check(); return lease.tick(); }
        };
        guard = new TransactionParticipant() {
            public Object snapshot() { check(); return lease.tick(); }
            public void restore(Object ignored) { /* The storage participant owns its rollback. */ }
            public void validate() { check(); }
            public void validateSnapshot(Object value) { check(); if ((long) value != lease.tick()) throw new StorageAccessException("Provider transaction crossed a tick"); }
            public void afterCommit() { }
        };
        check();
    }
    private void check() { lease.checkSameContext(delegated); }
    public StorageContext context() { return context; }
    public boolean supportsTransactions() { check(); return storage.supportsTransactions(); }
    public int tanks() { check(); return storage.tanks(); }
    public FluidStack content(int index) { check(); return storage.content(index); }
    public long capacity(int index) { check(); return storage.capacity(index); }
    public boolean isFluidValid(int index, FluidVariant variant) { check(); return storage.isFluidValid(index, variant); }
    public long insert(FluidVariant fluid, long amount, FluidTransaction transaction) { check(); transaction.enlist(guard); return storage.insert(fluid, amount, transaction); }
    public long extract(FluidVariant fluid, long amount, FluidTransaction transaction) { check(); transaction.enlist(guard); return storage.extract(fluid, amount, transaction); }
}
