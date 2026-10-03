/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Optional owner-scoped navigation. This API contains no dependency on a recipe plugin. */
public final class TankRecipeNavigation {
    @FunctionalInterface public interface Navigator {
        void open(Player player, Location position, ReturnHandle back);
    }
    public interface ReturnHandle {
        /** Performs player and block checks on their respective owners. */
        CompletableFuture<Boolean> valid();
        /** Opens a fresh window for the same live controller, or safely refuses the return. */
        CompletableFuture<Boolean> reopen();
        void cancel();
    }
    public interface Registration extends AutoCloseable { @Override void close(); }
    public record Entry(Object owner, Navigator navigator) { }
    private final AtomicReference<Entry> current = new AtomicReference<>();
    public Registration register(Object owner, Navigator navigator) {
        Entry entry = new Entry(Objects.requireNonNull(owner), Objects.requireNonNull(navigator));
        current.set(entry);
        return () -> current.compareAndSet(entry, null);
    }
    public Entry current() { return current.get(); }
    public void unregisterOwner(Object owner) { current.updateAndGet(entry -> entry != null && entry.owner() == owner ? null : entry); }
    public void clear() { current.set(null); }
}
