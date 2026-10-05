/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Location;
import org.bukkit.event.world.ChunkLoadEvent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Older CE lacks native subscriptions; loaded chunks notify only their indexed observers. */
final class ChunkLoadWatch implements AutoCloseable {
    private record ChunkKey(UUID world, int x, int z) {}
    private final CraftEngineBridge bridge;
    private final Map<ChunkKey, Set<Watch>> watches = new ConcurrentHashMap<>();
    ChunkLoadWatch(CraftEngineBridge bridge) { this.bridge = bridge; }
    Runnable subscribe(Location owner, BlockPos neighbor, Runnable notification) {
        ChunkKey key = new ChunkKey(owner.getWorld().getUID(), neighbor.x() >> 4, neighbor.z() >> 4);
        Watch watch = new Watch(key, owner.clone(), notification);
        watches.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(watch);
        return watch::cancel;
    }
    void loaded(ChunkLoadEvent event) {
        var chunk = event.getChunk();
        Set<Watch> interested = watches.get(new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
        if (interested == null || !bridge.running()) return;
        for (Watch watch : interested) bridge.schedule(watch.owner, () -> {
            if (!watch.cancelled.get()) watch.notification.run();
            return null;
        }).exceptionally(failure -> {
            if (bridge.running() && !watch.cancelled.get()) bridge.plugin().getLogger().log(java.util.logging.Level.WARNING,
                    "Cannot wake a tank observing a loaded neighbor chunk", failure);
            return null;
        });
    }
    private final class Watch {
        final ChunkKey key; final Location owner; final Runnable notification; final AtomicBoolean cancelled = new AtomicBoolean();
        Watch(ChunkKey key, Location owner, Runnable notification) { this.key = key; this.owner = owner; this.notification = notification; }
        void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            watches.computeIfPresent(key, (ignored, entries) -> { entries.remove(this); return entries.isEmpty() ? null : entries; });
        }
    }
    @Override public void close() { watches.values().forEach(entries -> entries.forEach(watch -> watch.cancelled.set(true))); watches.clear(); }
}
