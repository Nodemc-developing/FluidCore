/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.bukkit.ItemContainerTransfers;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.loot.LootContext;
import net.momirealms.craftengine.core.loot.function.LootFunction;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import org.bukkit.inventory.ItemStack;

/** Attach to exactly one self-drop entry, with count 1, in a tank's CE loot table. */
final class PreserveTankLootFunction implements LootFunction {
    private final CraftEngineBridge bridge;
    PreserveTankLootFunction(CraftEngineBridge bridge) { this.bridge = bridge; }

    @Override public Item apply(Item item, LootContext context) {
        if (item.isEmpty()) return item;
        var position = context.getOptionalParameter(DirectContextParameters.POSITION);
        if (position.isEmpty()) return item;
        var data = bridge.dataForDrop(LocationUtils.toLocation(position.get()));
        if (data.isEmpty()) return item;
        if (Boolean.TRUE.equals(context.getVariable("fluidcore:tank_preserved")))
            throw new IllegalArgumentException("A tank loot table may preserve its fluid data only once");
        context.setVariable("fluidcore:tank_preserved", true);
        if (item.count() != 1) throw new IllegalArgumentException("A fluid-preserving tank loot entry must have count 1");
        Item result = item.copy();
        if (result.platformItem() instanceof ItemStack stack) {
            ItemContainerTransfers.writeData(stack, ItemContainerTransfers.TANK_DATA_KEY, data.get());
            var decoded = new com.ydxc20091.fluidcore.core.FluidStackCodec(bridge.registry()).decode(data.get());
            if (decoded.usable()) {
                var definition = net.momirealms.craftengine.bukkit.api.CraftEngineItems.byItemStack(stack);
                var container = definition == null ? null : definition.settings().getCustomData(ContainerSettings.TYPE);
                long capacity = container == null ? bridge.resolver().controller(LocationUtils.toLocation(position.get())).map(controller -> controller.storage().capacity(0)).orElse(16000L) : container.capacity();
                var render = bridge.renderStateForDrop(LocationUtils.toLocation(position.get())).orElse(null);
                if (render != null) stack.editMeta(meta -> meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("fluidcore", "glass_color"), org.bukkit.persistence.PersistentDataType.INTEGER, render.glassColor()));
                TankItemPresentation.apply(stack, decoded.stack(), capacity, bridge.registry(), render == null ? null : render.itemModel());
            }
            return BukkitItemManager.instance().wrap(stack);
        }
        throw new IllegalStateException("CE did not provide a Bukkit item for a tank loot entry");
    }
}
