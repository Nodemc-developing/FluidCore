package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidStack;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.Color;
import org.bukkit.inventory.ItemStack;
import java.util.List;
import java.util.Locale;

/** Immutable display data published on the block owner; packet rendering never reads live storage. */
public record TankRenderState(FluidStack fluid, long capacity, int glassColor, int fluidColor, String itemModel, boolean waterlogged) {
    public void apply(Item item) {
        FluidTankVisualState visual = FluidTankVisualState.of(fluid, capacity);
        if (itemModel != null && !itemModel.isBlank()) item.itemModel(itemModel + (waterlogged ? "/waterlogged" : "") + "/" + (visual.level() == 0 ? "empty" : "level_" + String.format(Locale.ROOT, "%02d", visual.level())));
        if (item.platformItem() instanceof ItemStack stack) colors(stack, glassColor, fluidColor);
    }
    public static void colors(ItemStack stack, int glassColor, int fluidColor) {
        var key = io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA;
        var previous = stack.getData(key);
        var data = io.papermc.paper.datacomponent.item.CustomModelData.customModelData();
        if (previous != null) data.addFloats(previous.floats()).addFlags(previous.flags()).addStrings(previous.strings());
        data.addColors(List.of(Color.fromRGB(glassColor & 0xffffff), Color.fromRGB(fluidColor & 0xffffff)));
        if (previous != null && previous.colors().size() > 2) data.addColors(previous.colors().subList(2, previous.colors().size()));
        stack.setData(key, data);
    }
}
