package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** A stable concatenated view; each underlying storage retains ownership and filters. */
public final class MultiTankStorage implements FluidStorage {
    private final List<FluidStorage> storages;
    private final StorageContext context;

    public MultiTankStorage(FluidStorage... storages) { this(Arrays.asList(storages)); }
    public MultiTankStorage(List<? extends FluidStorage> storages) {
        this.storages = List.copyOf(storages);
        if (this.storages.isEmpty()) throw new IllegalArgumentException("At least one storage is required");
        this.context = new StorageContext() {
            @Override public void checkAccess() { MultiTankStorage.this.storages.forEach(storage -> storage.context().checkAccess()); }
            @Override public long tick() { return MultiTankStorage.this.storages.getFirst().context().tick(); }
        };
    }

    @Override public StorageContext context() { return context; }
    @Override public boolean supportsTransactions() { return storages.stream().allMatch(FluidStorage::supportsTransactions); }
    @Override public int tanks() {
        context.checkAccess();
        int total = 0;
        for (FluidStorage storage : storages) total = Math.addExact(total, storage.tanks());
        return total;
    }

    private Address locate(int tank) {
        context.checkAccess();
        if (tank < 0) throw new IndexOutOfBoundsException(tank);
        for (FluidStorage storage : storages) {
            int count = storage.tanks();
            if (tank < count) return new Address(storage, tank);
            tank -= count;
        }
        throw new IndexOutOfBoundsException(tank);
    }

    @Override public FluidStack content(int tank) { Address address = locate(tank); return address.storage.content(address.tank); }
    @Override public long capacity(int tank) { Address address = locate(tank); return address.storage.capacity(address.tank); }
    @Override public boolean isFluidValid(int tank, FluidVariant variant) { Address address = locate(tank); return address.storage.isFluidValid(address.tank, variant); }
    @Override public long insert(FluidVariant variant, long amount, FluidTransaction transaction) { return move(variant, amount, transaction, true); }
    @Override public long extract(FluidVariant variant, long amount, FluidTransaction transaction) { return move(variant, amount, transaction, false); }

    private long move(FluidVariant variant, long amount, FluidTransaction transaction, boolean insert) {
        context.checkAccess();
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(transaction, "transaction").checkOpen();
        if (amount < 0) throw new IllegalArgumentException("Negative amount");
        long moved = 0;
        for (FluidStorage storage : storages) {
            long remaining = amount - moved;
            if (remaining == 0) break;
            long part = insert ? storage.insert(variant, remaining, transaction) : storage.extract(variant, remaining, transaction);
            if (part < 0 || part > remaining) throw new IllegalStateException("Storage violated amount contract");
            moved = Math.addExact(moved, part);
        }
        return moved;
    }
    private record Address(FluidStorage storage, int tank) {}
}
