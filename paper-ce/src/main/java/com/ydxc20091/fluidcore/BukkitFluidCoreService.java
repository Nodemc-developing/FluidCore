/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore;

import com.ydxc20091.fluidcore.api.FluidStorage;
import com.ydxc20091.fluidcore.bukkit.ItemContainerTransfers;
import com.ydxc20091.fluidcore.bukkit.ItemFluidContainerRegistry;
import com.ydxc20091.fluidcore.bukkit.ItemFluidData;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import com.ydxc20091.fluidcore.ce.CraftEngineBridge;
import com.ydxc20091.fluidcore.async.SnapshotWorkQueue;
import org.bukkit.Location;
import java.util.Optional;

/** Server-specific integration service. The standalone API module has no Bukkit dependency. */
public interface BukkitFluidCoreService {
    FluidRegistry registry();
    CraftEngineBridge bridge();
    SnapshotWorkQueue workQueue();
    Optional<FluidStorage> storageAt(Location location);

    /** Count-one container copies and custom providers; live item access remains owner-confined. */
    default ItemFluidContainerRegistry containers() { return bridge().containers().containers(); }

    /** Lossless reads and protected writes of this library's persistent item data. */
    default ItemFluidData itemData() { return bridge().containers().itemData(); }

    /** Real-slot atomic operations and explicitly detached replacement-item operations. */
    default ItemContainerTransfers containerTransfers() { return bridge().containers(); }
}
