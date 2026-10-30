/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A real-slot container transfer awaiting commit on its owning thread. Cancellation rolls back
 * both the tentative fluid change and slot replacement. This is not fired for detached simulations.
 * Listeners must not wait, cross regions, or perform non-transactional resource changes.
 */
public final class FluidContainerTransferEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ItemContainerTransfers.TransferContext transfer;
    private boolean cancelled;

    public FluidContainerTransferEvent(ItemContainerTransfers.TransferContext transfer) {
        this.transfer = java.util.Objects.requireNonNull(transfer);
    }
    /** Player and target location may be absent for integrations using the raw slot entry point. */
    public ItemContainerTransfers.TransferContext transfer() { return transfer; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
