/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Replaces only its own appended display lines; names and unrelated item data remain intact. */
public final class TankItemPresentation {
    private static final NamespacedKey LINES = new NamespacedKey("fluidcore", "display_lines");
    private TankItemPresentation() {}
    public static void apply(ItemStack item, FluidStack content, long capacity, FluidRegistry registry, String modelPrefix) {
        if (org.bukkit.Bukkit.getServer() == null || net.momirealms.craftengine.core.plugin.CraftEngine.instance() == null) return;
        if (item == null || item.getType() == org.bukkit.Material.AIR || capacity <= 0) return;
        var meta = item.getItemMeta();
        if (meta == null) return;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        Integer previous = meta.getPersistentDataContainer().get(LINES, PersistentDataType.INTEGER);
        if (previous != null && previous >= 0 && previous <= lore.size()) lore.subList(lore.size() - previous, lore.size()).clear();
        lore.removeIf(TankItemPresentation::isConfiguredEmptyLine);
        var bridge = org.bukkit.Bukkit.getServicesManager().load(CraftEngineBridge.class);
        var messages = bridge == null ? com.ydxc20091.fluidcore.config.FluidMessages.defaults() : bridge.messages();
        String name = content.isEmpty() ? messages.text(null, "empty") : messages.fluidName(registry, content.variant().fluid(), null);
        if (content.isEmpty()) {
            lore.add(Component.translatable("tooltip.farmersdelight.jug.empty").fallback(name)
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.translatable("tooltip.farmersdelight.jug.contains")
                    .fallback(messages.text(null, "contains", "fluid", "").stripTrailing())
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.translatable("tooltip.farmersdelight.jug.fluid")
                    .fallback("%s (%s mB)").arguments(Component.text(name), Component.text(content.amount()))
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(LINES, PersistentDataType.INTEGER, content.isEmpty() ? 1 : 2);
        if (meta instanceof LeatherArmorMeta leather && !content.isEmpty()) registry.find(content.variant().fluid()).ifPresent(definition -> {
            if (definition.color().isPresent()) leather.setColor(Color.fromRGB(definition.color().getAsInt() & 0xffffff));
        });
        item.setItemMeta(meta);
        // This class is called only on a real item owner. CE supplies the current-version component codec.
        var wrapped = BukkitItemManager.instance().wrap(item);
        wrapped.maxStackSize(1).maxDamage(1001).damage(content.isEmpty() ? 0 : 1000 - (int) Math.min(1000, Math.floor(content.amount() * 1000.0 / capacity)));
        if (modelPrefix == null) {
            var definition = net.momirealms.craftengine.bukkit.api.CraftEngineItems.byItemStack(item);
            var behavior = definition == null ? null : definition.behavior().getFirst(FluidContainerBehavior.class);
            if (behavior != null) modelPrefix = behavior.itemModel();
        }
        Integer shell = meta.getPersistentDataContainer().get(new NamespacedKey("fluidcore", "glass_color"), PersistentDataType.INTEGER);
        int fluidColor = content.isEmpty() ? 0xffffff : registry.find(content.variant().fluid()).map(definition -> definition.color().orElse(0xff9966bf)).orElse(0xff9966bf);
        TankRenderState.colors(item, shell == null ? 0xffffff : shell, fluidColor);
        if (com.ydxc20091.fluidcore.bukkit.ServerCapabilities.modernItems() && modelPrefix != null && !modelPrefix.isBlank()) {
            FluidTankVisualState state = FluidTankVisualState.of(content, capacity);
            if (state.level() == 0) wrapped.itemModel(modelPrefix + "/empty");
            else wrapped.itemModel(modelPrefix + "/level_" + String.format(Locale.ROOT, "%02d", state.level()));
        }
        ItemStack changed = (ItemStack) wrapped.platformItem();
        if (changed != item) item.setItemMeta(changed.getItemMeta());
    }

    private static boolean isConfiguredEmptyLine(Component line) {
        if (line instanceof TranslatableComponent translated)
            return translated.key().equals("tooltip.farmersdelight.jug.empty")
                    && translated.arguments().isEmpty() && translated.children().isEmpty();
        return line instanceof TextComponent text && text.content().isEmpty() && text.children().size() == 1
                && isConfiguredEmptyLine(text.children().getFirst());
    }
}
