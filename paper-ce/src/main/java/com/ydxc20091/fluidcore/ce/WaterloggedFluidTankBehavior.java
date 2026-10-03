package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.bukkit.block.behavior.BukkitSimpleWaterloggedBlock;
import net.momirealms.craftengine.bukkit.block.behavior.WaterloggedBlockBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;

/** Uses CE's current native waterlogging behavior while retaining the tank controller and contents. */
final class WaterloggedFluidTankBehavior extends FluidTankBehavior implements BukkitSimpleWaterloggedBlock {
    private final WaterloggedBlockBehavior waterlogging;
    WaterloggedFluidTankBehavior(BlockDefinition block, ConfigSection section, CraftEngineBridge bridge, Property<Boolean> property) {
        super(block, section, bridge); waterlogging = new WaterloggedBlockBehavior(block, property);
    }
    @Override public Object pickupBlock(Object block, Object[] arguments) { return waterlogging.pickupBlock(block, arguments); }
    @Override public boolean placeLiquid(Object block, Object[] arguments) { return waterlogging.placeLiquid(block, arguments); }
    @Override public boolean canPlaceLiquid(Object block, Object[] arguments) { return waterlogging.canPlaceLiquid(block, arguments); }
    @Override public Object updateShape(Object block, Object[] arguments) { super.updateShape(block, arguments); return waterlogging.updateShape(block, arguments); }
    @Override public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        return super.updateStateForPlacement(context, waterlogging.updateStateForPlacement(context, state));
    }
}
