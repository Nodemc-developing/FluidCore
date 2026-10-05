package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidStack;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.inventory.ItemStack;
import com.ydxc20091.fluidcore.bukkit.ServerCapabilities;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.IntTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import java.util.Locale;

/** Immutable display data published on the block owner; packet rendering never reads live storage. */
public record TankRenderState(FluidStack fluid, long capacity, int glassColor, int fluidColor, String itemModel, boolean waterlogged) {
    public void apply(Item item) {
        FluidTankVisualState visual = FluidTankVisualState.of(fluid, capacity);
        if (ServerCapabilities.modernItems() && itemModel != null && !itemModel.isBlank()) item.itemModel(itemModel + (waterlogged ? "/waterlogged" : "") + "/" + (visual.level() == 0 ? "empty" : "level_" + String.format(Locale.ROOT, "%02d", visual.level())));
        if (item.platformItem() instanceof ItemStack stack) colors(stack, glassColor, fluidColor);
    }
    public static void colors(ItemStack stack, int glassColor, int fluidColor) {
        if (!ServerCapabilities.modernItems()) return;
        Item wrapped = BukkitItemManager.instance().wrap(stack);
        Tag previous = wrapped.getComponentAsSparrowTag(DataComponentKeys.CUSTOM_MODEL_DATA);
        wrapped.setSparrowTagComponent(DataComponentKeys.CUSTOM_MODEL_DATA, colorData(previous, glassColor, fluidColor));
        ItemStack changed = (ItemStack) wrapped.platformItem();
        if (changed != stack) stack.setItemMeta(changed.getItemMeta());
    }
    static CompoundTag colorData(Tag previous, int glassColor, int fluidColor) {
        if (previous != null && !(previous instanceof CompoundTag))
            throw new IllegalStateException("Expected structured custom_model_data on Minecraft 1.21.4 or later");
        CompoundTag result = previous == null ? new CompoundTag() : ((CompoundTag) previous).copy();
        Tag existing = result.get("colors");
        if (existing != null && !(existing instanceof ListTag))
            throw new IllegalStateException("custom_model_data.colors must be a list");
        ListTag colors = new ListTag();
        colors.add(IntTag.valueOf(glassColor & 0xffffff));
        colors.add(IntTag.valueOf(fluidColor & 0xffffff));
        if (existing instanceof ListTag old) for (int index = 2; index < old.size(); index++) colors.add(old.get(index).copy());
        result.put("colors", colors);
        return result;
    }
}
