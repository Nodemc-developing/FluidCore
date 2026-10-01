/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.examples;

import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.context.UseOnContext;

final class HeatingBehavior extends BukkitBlockBehavior implements EntityBlock {
    private final FluidCoreExamplesPlugin plugin;
    HeatingBehavior(BlockDefinition definition, FluidCoreExamplesPlugin plugin) { super(definition); this.plugin = plugin; }
    @Override public BlockEntityController createBlockEntityController(BlockEntity entity) { return new HeatingController(entity, plugin); }
    @Override public void initControllerId(int id) {}

    @Override public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) {
        var entity = context.getWorld().storageWorld().getBlockEntityAtIfLoaded(context.getClickedPos());
        if (entity != null) entity.controller.let(HeatingController.class, HeatingController::wakeUp);
        return super.useWithoutItem(context, state);
    }
}
