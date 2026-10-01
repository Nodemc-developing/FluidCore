/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidStack;
import org.bukkit.inventory.ItemStack;
import java.util.Objects;

/** Lossless diagnostics for an item record. Protected records are never implicitly replaced. */
public final class ItemFluidReadResult {
    public enum Status { ABSENT, EMPTY, PRESENT, UNKNOWN, INVALID, WRONG_TYPE, CONFLICT }
    private final Status status;
    private final FluidStack stack;
    private final byte[] raw;
    private final byte[] persistentDataSnapshot;
    private final String message;
    private final ItemStack original;

    public ItemFluidReadResult(Status status, FluidStack stack, byte[] raw, byte[] persistentDataSnapshot,
                        String message, ItemStack original) {
        this.status = Objects.requireNonNull(status);
        this.stack = Objects.requireNonNull(stack);
        this.raw = raw.clone();
        this.persistentDataSnapshot = persistentDataSnapshot.clone();
        this.message = Objects.requireNonNull(message);
        this.original = original.clone();
    }
    public Status status() { return status; }
    public FluidStack stack() { return stack; }
    /** The original codec record, or an empty array when no byte-array record was present. */
    public byte[] raw() { return raw.clone(); }
    /** Complete PDC backup where the platform supports serialization; originalItem() is always retained. */
    public byte[] persistentDataSnapshot() { return persistentDataSnapshot.clone(); }
    public String message() { return message; }
    public ItemStack originalItem() { return original.clone(); }
    public boolean usable() { return status == Status.ABSENT || status == Status.EMPTY || status == Status.PRESENT; }
    public boolean protectedData() { return !usable(); }
}
