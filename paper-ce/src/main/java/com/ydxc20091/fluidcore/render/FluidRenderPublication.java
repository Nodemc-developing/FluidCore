/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.render;

import com.ydxc20091.fluidcore.api.FluidKey;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Assets belong to one successful pack attempt, never merely to files left on disk. */
final class FluidRenderPublication {
    private long attempt;
    private long registryGeneration = -1;
    private Set<FluidKey> generated = Set.of();

    /** A new CE cache event must register assets again, even if its eventual compilation fails. */
    synchronized long begin() {
        attempt++;
        generated = Set.of(); registryGeneration = -1;
        return attempt;
    }

    synchronized boolean publish(long expected, long registry, Collection<FluidKey> styles) {
        if (expected != attempt) return false;
        generated = Set.copyOf(styles); registryGeneration = registry;
        return true;
    }

    synchronized boolean current(long expected) { return expected == attempt; }
    synchronized boolean available(FluidKey style, long registry) {
        return registryGeneration == registry && generated.contains(style);
    }

    /** Removal also prevents an older in-flight compilation from resurrecting the withdrawn style. */
    synchronized void remove(FluidKey style) {
        attempt++;
        Set<FluidKey> remaining = new HashSet<>(generated); remaining.remove(style); generated = Set.copyOf(remaining);
    }
    synchronized void retain(Collection<FluidKey> styles) {
        attempt++;
        Set<FluidKey> remaining = new HashSet<>(generated); remaining.retainAll(styles); generated = Set.copyOf(remaining);
    }
}
