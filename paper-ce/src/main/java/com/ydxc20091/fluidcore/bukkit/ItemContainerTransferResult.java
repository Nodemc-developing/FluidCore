/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidStack;
import org.bukkit.inventory.ItemStack;
import java.util.Objects;
import java.util.Optional;

/** Detached one-item replacement and transferred fluid. This does not replace any real slot. */
public final class ItemContainerTransferResult {
    public enum Status { SUCCESS, NOT_A_CONTAINER, PROTECTED_DATA, NO_TRANSFER, ATOMIC_TRANSFER_UNSUPPORTED }
    private final Status status;
    private final ItemStack replacement;
    private final FluidStack moved;
    private final ItemFluidReadResult readResult;
    ItemContainerTransferResult(Status status, ItemStack replacement, FluidStack moved, ItemFluidReadResult readResult) {
        this.status = Objects.requireNonNull(status); this.replacement = replacement.clone();
        this.moved = Objects.requireNonNull(moved); this.readResult = readResult;
    }
    public Status status() { return status; }
    public boolean success() { return status == Status.SUCCESS; }
    public ItemStack replacement() { return replacement.clone(); }
    public FluidStack moved() { return moved; }
    public Optional<ItemFluidReadResult> readResult() { return Optional.ofNullable(readResult); }
}
