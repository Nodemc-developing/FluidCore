/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidAction;
import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.api.FluidVariant;
import org.bukkit.inventory.ItemStack;

/**
 * A detached one-item handler. EXECUTE changes its private copy; SIMULATE changes neither private
 * copy nor original. item() returns the replacement, never a live inventory reference. Applying
 * this result to a real slot is the caller's responsibility; use ItemContainerTransfers.transfer
 * for a slot/storage transaction. Instances are confined to the thread that resolves them.
 */
public interface ItemFluidContainer {
    ItemStack item();
    FluidStack content();
    long capacity();
    boolean accepts(FluidVariant variant);
    ItemFluidReadResult readResult();
    long fill(FluidStack offered, FluidAction action);
    FluidStack drain(FluidVariant variant, long maximum, FluidAction action);
    default FluidStack drain(long maximum, FluidAction action) {
        if (maximum < 0) throw new IllegalArgumentException("Negative maximum");
        java.util.Objects.requireNonNull(action, "action");
        FluidStack current = content();
        return current.isEmpty() ? FluidStack.EMPTY : drain(current.variant(), maximum, action);
    }
}
