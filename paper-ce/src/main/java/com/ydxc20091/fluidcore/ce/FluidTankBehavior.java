/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidKey;
import com.ydxc20091.fluidcore.api.FluidVariant;
import com.ydxc20091.fluidcore.bukkit.ItemContainerTransfers;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.inventory.ItemStack;

public final class FluidTankBehavior extends BukkitBlockBehavior implements EntityBlock {
    private final CraftEngineBridge bridge;
    private final long capacity;
    private final ContainerSettings.Filter allowed;
    private int controllerId;

    FluidTankBehavior(BlockDefinition block, ConfigSection section, CraftEngineBridge bridge) {
        super(block);
        this.bridge = bridge;
        capacity = FluidConfigurationValues.longValue(section, "capacity", 8000);
        if (capacity <= 0) throw new IllegalArgumentException("Tank capacity must be positive");
        allowed = ContainerSettings.filter(section);
    }
    public long capacity() { return capacity; }
    public boolean accepts(FluidVariant variant) {
        if (allowed.allowAll() || allowed.fluids().contains(variant.fluid())) return true;
        for (FluidKey tag : allowed.tags()) if (bridge.registry().hasTag(variant.fluid(), tag)) return true;
        return false;
    }
    @Override public void initControllerId(int id) { controllerId = id; }
    @Override public BlockEntityController createBlockEntityController(BlockEntity entity) { return new FluidTankController(entity, this, bridge); }
    @Override public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) { return bridge.interact(context); }
    @Override public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) { return bridge.interact(context); }

    @Override public Item itemToPickup(World world, BlockPos position, ImmutableBlockState state,
                                       net.momirealms.craftengine.core.entity.player.Player player) {
        Item item = Item.byId(block().id());
        BlockEntity entity = world.storageWorld().getBlockEntityAtIfLoaded(position);
        if (entity != null && item.platformItem() instanceof ItemStack stack) {
            FluidTankController controller = entity.controller.getAt(FluidTankController.class, controllerId);
            if (controller != null) {
                ItemContainerTransfers.writeData(stack, ItemContainerTransfers.TANK_DATA_KEY, controller.savedData());
                return net.momirealms.craftengine.bukkit.item.BukkitItemManager.instance().wrap(stack);
            }
        }
        return item;
    }
}
