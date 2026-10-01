/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidStorage;
import org.bukkit.Location;
import java.util.Optional;

/** Invoked on the location's owning thread; adapters need not inherit a FluidCore or CE class. */
@FunctionalInterface
public interface BlockStorageProvider {
    Optional<FluidStorage> resolve(Location location);
}
