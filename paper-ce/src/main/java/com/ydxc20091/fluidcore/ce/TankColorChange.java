/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.api.StorageContext;
import com.ydxc20091.fluidcore.api.TransactionParticipant;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Tracks the color actually written by this owner-local operation, publishing only after commit. */
final class TankColorChange implements TransactionParticipant {
    private final StorageContext context;
    private final IntSupplier read;
    private final IntConsumer write;
    private final LongSupplier generation;
    private final Runnable committed;
    private Integer expected;

    TankColorChange(StorageContext context, IntSupplier read, IntConsumer write, LongSupplier generation, Runnable committed) {
        this.context = context;
        this.read = read;
        this.write = write;
        this.generation = generation;
        this.committed = committed;
    }

    void set(int color, FluidTransaction transaction) {
        transaction.enlist(this);
        validate();
        write.accept(color);
        expected = color;
    }

    @Override public Object snapshot() {
        context.checkAccess();
        expected = read.getAsInt();
        return new Snapshot(expected, context.tick(), generation.getAsLong());
    }

    @Override public void restore(Object snapshot) {
        context.checkAccess();
        if (expected != null && read.getAsInt() == expected) write.accept(((Snapshot) snapshot).color());
        expected = read.getAsInt();
    }

    @Override public void validate() {
        context.checkAccess();
        if (expected != null && read.getAsInt() != expected) throw new StorageAccessException("Tank dye changed concurrently");
    }

    @Override public void validateSnapshot(Object snapshot) {
        validate();
        Snapshot original = (Snapshot) snapshot;
        if (original.tick() != context.tick()) throw new StorageAccessException("Tank dye transaction crossed a server tick");
        if (original.generation() != generation.getAsLong()) throw new StorageAccessException("Reload invalidated the tank dye transaction");
    }

    @Override public void afterCommit() { committed.run(); }
    private record Snapshot(int color, long tick, long generation) {}
}
