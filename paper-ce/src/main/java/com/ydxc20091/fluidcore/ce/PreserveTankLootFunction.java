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
            return BukkitItemManager.instance().wrap(stack);
        }
        throw new IllegalStateException("CE did not provide a Bukkit item for a tank loot entry");
    }
}
