package com.ydxc20091.fluidcore.bukkit;

import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.inventory.ItemStack;
import java.util.Locale;

/** A foreign payload is kept opaque rather than treated as an empty container. */
public final class ForeignFluidData {
    private ForeignFluidData() {}
    public static boolean key(String key) {
        String value = key.toLowerCase(Locale.ROOT);
        if (value.equals("fluidcore") || value.startsWith("fluidcore:")) return false;
        return value.equals("libuid") || value.startsWith("libuid:") || value.startsWith("jug_")
                || value.equals("papersdelight") || value.startsWith("papersdelight:")
                || value.contains("fluid") || value.contains("liquid") || value.contains("tank_data");
    }
    public static boolean compound(CompoundTag tag) { return compound(tag, 0); }
    private static boolean compound(CompoundTag tag, int depth) {
        if (depth > 32) return true;
        for (var entry : tag.entrySet()) {
            if (entry.getKey().startsWith("fluidcore:")) continue;
            if (key(entry.getKey()) || entry.getValue() instanceof CompoundTag nested && compound(nested, depth + 1)) return true;
        }
        return false;
    }
    public static boolean item(ItemStack item) {
        if (item == null || (item.getType() == org.bukkit.Material.AIR || item.getType() == org.bukkit.Material.CAVE_AIR || item.getType() == org.bukkit.Material.VOID_AIR)) return false;
        for (var key : item.getPersistentDataContainer().getKeys()) if (key(key.toString())) return true;
        if (org.bukkit.Bukkit.getServer() == null || net.momirealms.craftengine.core.plugin.CraftEngine.instance() == null) return false;
        var tag = BukkitItemManager.instance().wrap(item).getComponentAsSparrowTag(DataComponentKeys.CUSTOM_DATA);
        return tag instanceof CompoundTag compound && compound(compound);
    }
}
