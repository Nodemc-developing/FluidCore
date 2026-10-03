/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidKey;
import com.ydxc20091.fluidcore.api.FluidVariant;
import com.ydxc20091.fluidcore.api.FluidStack;
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

public class FluidTankBehavior extends BukkitBlockBehavior implements EntityBlock {
    private final CraftEngineBridge bridge;
    private final long capacity;
    private final boolean explicitCapacity;
    private final ContainerSettings.Filter allowed;
    private final FluidTankVisual visual;
    private final String itemModel;
    private final boolean dyeable;
    private final TankMenuSettings menuSettings;
    private int controllerId;
    static FluidTankBehavior create(BlockDefinition block, ConfigSection section, CraftEngineBridge bridge) {
        var property = block.getProperty("waterlogged");
        if (property != null) {
            if (property.valueClass() != Boolean.class) throw new IllegalArgumentException("waterlogged must be a boolean block property");
            @SuppressWarnings("unchecked") var waterlogged = (net.momirealms.craftengine.core.block.property.Property<Boolean>) property;
            return new WaterloggedFluidTankBehavior(block, section, bridge, waterlogged);
        }
        return new FluidTankBehavior(block, section, bridge);
    }

    FluidTankBehavior(BlockDefinition block, ConfigSection section, CraftEngineBridge bridge) {
        super(block);
        this.bridge = bridge;
        capacity = FluidConfigurationValues.longValue(section, "capacity", 8000);
        explicitCapacity = section.containsKey("capacity");
        if (capacity <= 0) throw new IllegalArgumentException("Tank capacity must be positive");
        allowed = ContainerSettings.filter(section);
        visual = FluidTankVisual.parse(block, section);
        itemModel = section.getString("item-model", (String) null);
        dyeable = section.getBoolean("dyeable", section.getBoolean("transparent", false));
        menuSettings = TankMenuSettings.parse(section);
    }
    public long capacity() {
        if (!explicitCapacity) {
            var definition = net.momirealms.craftengine.bukkit.api.CraftEngineItems.byId(block().id());
            if (definition != null) {
                var container = definition.settings().getCustomData(ContainerSettings.TYPE);
                if (container != null) return container.capacity();
            }
        }
        return capacity;
    }
    String itemModel() { return itemModel; }
    boolean dyeable() { return dyeable; }
    public TankMenuSettings menuSettings() { return menuSettings; }
    boolean hasVisualState() { return visual != null; }
    ImmutableBlockState visualState(ImmutableBlockState state, FluidStack content) {
        return visual == null ? state : visual.apply(state, FluidTankVisualState.of(content, capacity()));
    }
    public boolean accepts(FluidVariant variant) {
        if (allowed.allowAll() || allowed.fluids().contains(variant.fluid())) return true;
        for (FluidKey tag : allowed.tags()) if (bridge.registry().hasTag(variant.fluid(), tag)) return true;
        return false;
    }
    @Override public void initControllerId(int id) { controllerId = id; }
    @Override public BlockEntityController createBlockEntityController(BlockEntity entity) { return new FluidTankController(entity, this, bridge); }
    @Override public boolean canUseOnBlockIfSecondaryUseActive(UseOnContext context, ImmutableBlockState state) {
        // Container transfers must still consume the click while sneaking; otherwise a bucket can
        // bypass the tank and place its fluid in the world. Non-containers still return PASS below.
        return true;
    }
    @Override public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) { return bridge.interact(context); }
    @Override public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) { return bridge.interact(context); }
    @Override public ImmutableBlockState updateStateForPlacement(net.momirealms.craftengine.core.world.context.BlockPlaceContext context, ImmutableBlockState state) {
        net.momirealms.craftengine.core.block.property.Property<?> property = block().getProperty("facing");
        if (property == null) return state;
        return facing(state, property, context.getHorizontalDirection().opposite().name().toLowerCase(java.util.Locale.ROOT));
    }
    private static <T extends Comparable<T>> ImmutableBlockState facing(ImmutableBlockState state, net.momirealms.craftengine.core.block.property.Property<T> property, String name) {
        T value = property.valueByName(name); return value == null ? state : state.with(property, value);
    }
    @Override public void neighborChanged(Object block, Object[] arguments) {
        if (arguments != null && arguments.length >= 3) wakeNeighbor(arguments[1], arguments[2]);
    }
    @Override public Object updateShape(Object block, Object[] arguments) {
        if (arguments != null && arguments.length > Math.max(updateShape$level, updateShape$blockPos)) wakeNeighbor(arguments[updateShape$level], arguments[updateShape$blockPos]);
        return arguments[0];
    }
    private void wakeNeighbor(Object level, Object position) {
        try {
            org.bukkit.World world = level instanceof org.bukkit.World existing ? existing
                    : net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy.INSTANCE.getWorld(level);
            BlockPos pos = position instanceof BlockPos existing ? existing : net.momirealms.craftengine.bukkit.util.LocationUtils.fromBlockPos(position);
            org.bukkit.Location location = new org.bukkit.Location(world, pos.x(), pos.y(), pos.z());
            bridge.resolver().controller(location).ifPresent(controller -> { bridge.hoppers().invalidate(controller); controller.wakeUp(); });
        } catch (com.ydxc20091.fluidcore.api.StorageAccessException retired) { /* A removed neighbor has no active ticker. */ }
    }

    @Override public Item itemToPickup(World world, BlockPos position, ImmutableBlockState state,
                                       net.momirealms.craftengine.core.entity.player.Player player) {
        Item item = Item.byId(block().id());
        BlockEntity entity = world.storageWorld().getBlockEntityAtIfLoaded(position);
        if (entity != null && item.platformItem() instanceof ItemStack stack) {
            FluidTankController controller = entity.controller.getAt(FluidTankController.class, controllerId);
            if (controller != null) {
                ItemContainerTransfers.writeData(stack, ItemContainerTransfers.TANK_DATA_KEY, controller.savedData());
                controller.writeColor(stack);
                if (!controller.hasProtectedData()) TankItemPresentation.apply(stack, controller.storage().content(0), capacity(), bridge.registry(), itemModel);
                return net.momirealms.craftengine.bukkit.item.BukkitItemManager.instance().wrap(stack);
            }
        }
        return item;
    }
}
