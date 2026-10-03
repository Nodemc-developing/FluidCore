/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Emitted after a CE tank commits, on its owning thread. Wake dependent machinery here. */
public final class FluidStorageCommitEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Location location;
    private final long version;

    public FluidStorageCommitEvent(Location location, long version) {
        super(!org.bukkit.Bukkit.isPrimaryThread());
        this.location = location.clone();
        this.version = version;
    }
    public Location location() { return location.clone(); }
    public long version() { return version; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
