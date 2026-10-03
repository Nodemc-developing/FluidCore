/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.world.context.UseOnContext;

final class FluidContainerBehavior extends ItemBehavior {
    private final CraftEngineBridge bridge;
    private final String itemModel;
    FluidContainerBehavior(CraftEngineBridge bridge) { this(bridge, null); }
    FluidContainerBehavior(CraftEngineBridge bridge, String itemModel) { this.bridge = bridge; this.itemModel = itemModel; }
    String itemModel() { return itemModel; }
    @Override public InteractionResult useOnBlock(UseOnContext context) { return bridge.interact(context); }
}
