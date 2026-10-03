/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidStack;
import java.util.List;
import java.util.UUID;

/** Detached immutable data, safe to inspect outside the world owner's thread. */
public record BlockFluidSnapshot(UUID world, int x, int y, int z,
                                 List<FluidStack> contents, List<Long> capacities, long generation) {
    public BlockFluidSnapshot {
        contents = List.copyOf(contents);
        capacities = List.copyOf(capacities);
        if (contents.size() != capacities.size()) throw new IllegalArgumentException("Mismatched tank snapshot sizes");
    }
}
