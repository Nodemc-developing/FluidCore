/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import com.ydxc20091.fluidcore.api.FluidKey;
import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.ce.CraftEngineBridge;
import com.ydxc20091.fluidcore.ce.TankMenuSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.momirealms.craftengine.bukkit.api.CraftEngineImages;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Resolves presentation resources from loaded content; no resource files are copied or generated. */
final class TankMenuTheme {
    private final CraftEngineBridge bridge;
    private final TankMenuSettings settings;
    private final Component background;

    TankMenuTheme(CraftEngineBridge bridge, TankMenuSettings settings) {
        this.bridge = bridge;
        this.settings = settings;
        Component imageComponent = null;
        if (settings.theme() == TankMenuSettings.Theme.AUTO && !settings.backgroundImage().isEmpty()) {
            var image = CraftEngineImages.byId(Key.of(settings.backgroundImage()));
            if (image != null) {
                var fonts = CraftEngine.instance().fontManager();
                imageComponent = MiniMessage.miniMessage().deserialize(fonts.createMiniMessageOffsets(settings.backgroundOffset())
                        + "<white>" + image.miniMessageAt(0, 0) + "</white>" + fonts.createMiniMessageOffsets(settings.titleOffset()));
            }
        }
        background = imageComponent;
    }

    boolean hasBackground() { return background != null; }

    Component title(String locale) {
        String fallback = bridge.messages().text(locale, "tank");
        Component label = settings.titleKey().isEmpty() ? Component.text(fallback)
                : Component.translatable(settings.titleKey()).fallback(fallback);
        return composeTitle(background, label);
    }

    static Component composeTitle(Component background, Component label) {
        if (background == null) return UiTextStyle.title(label);
        return UiTextStyle.upright(Component.empty().append(background).append(label.colorIfAbsent(NamedTextColor.WHITE)));
    }

    ItemStack border() {
        ItemStack item = hasBackground() ? resource(settings.borderItem()) : null;
        if (item == null) item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        return decorate(item, Component.text(" "), List.of());
    }

    ItemStack emptyInput(String locale) {
        if (hasBackground()) return new ItemStack(Material.AIR);
        return icon(Material.HOPPER, bridge.messages().text(locale, "input"), UiTextStyle.Role.NAME,
                List.of(UiTextStyle.text(bridge.messages().text(locale, "insert"), UiTextStyle.Role.ACTION),
                        UiTextStyle.text(bridge.messages().text(locale, "take-input"), UiTextStyle.Role.DESCRIPTION)));
    }

    ItemStack emptyOutput(String locale) {
        if (hasBackground()) return new ItemStack(Material.AIR);
        return icon(Material.CHEST, bridge.messages().text(locale, "output"), UiTextStyle.Role.NAME,
                List.of(UiTextStyle.text(bridge.messages().text(locale, "take-output"), UiTextStyle.Role.ACTION)));
    }

    ItemStack fluid(FluidStack content, long capacity, boolean protectedData, String locale) {
        var messages = bridge.messages();
        String name = protectedData ? messages.text(locale, "protected") : content.isEmpty() ? messages.text(locale, "empty")
                : messages.fluidName(bridge.registry(), content.variant().fluid(), locale);
        ItemStack icon = content.isEmpty() ? resource(settings.emptyItem()) : fluidItem(content.variant().fluid());
        if (icon == null) icon = new ItemStack(content.isEmpty() ? Material.GLASS_BOTTLE : Material.POTION);
        String translation = settings.titleKey().isEmpty() ? "container.farmersdelight.jug" : settings.titleKey();
        Component title = protectedData ? UiTextStyle.text(name, UiTextStyle.Role.WARNING) : content.isEmpty()
                ? Component.translatable(translation + ".empty").fallback(name)
                : Component.translatable(translation + ".fluid").fallback("%s (%s mB)")
                        .arguments(Component.text(name), Component.text(content.amount()));
        ItemStack result = decorate(icon, title.colorIfAbsent(NamedTextColor.WHITE), List.of());
        if (!content.isEmpty()) bridge.registry().find(content.variant().fluid()).ifPresent(type -> {
            if (type.color().isPresent()) result.editMeta(meta -> {
                Color color = Color.fromRGB(type.color().getAsInt() & 0xffffff);
                if (meta instanceof PotionMeta potion) potion.setColor(color);
                if (meta instanceof LeatherArmorMeta leather) leather.setColor(color);
            });
        });
        return result;
    }

    ItemStack progress(int stage, int percent, String locale) {
        ItemStack item = resource(settings.progressItemPrefix().isEmpty() ? "" : settings.progressItemPrefix() + stage);
        if (item != null) return decorate(item, Component.empty(), List.of());
        if (stage == 0) return border();
        return icon(Material.CLOCK, bridge.messages().text(locale, "progress", "percent", percent), UiTextStyle.Role.ACTION, List.of());
    }

    ItemStack capacity(boolean buckets, long amount, String locale) {
        int units = TankMenuLayout.units(amount, buckets ? 1000 : 250);
        ItemStack item = resource(buckets ? settings.bucketItem() : settings.bottleItem());
        if (item == null) item = new ItemStack(buckets ? Material.BUCKET : Material.GLASS_BOTTLE);
        String translation = settings.titleKey().isEmpty() ? "container.farmersdelight.jug" : settings.titleKey();
        String language = locale == null ? bridge.messages().defaultLanguage() : locale;
        String fallback = language.toLowerCase(Locale.ROOT).startsWith("zh") ? "1 桶 = %s 瓶" : "1 Bucket = %s Bottles";
        Component ratio = Component.translatable(translation + ".ratio").fallback(fallback)
                .arguments(Component.text(4)).color(NamedTextColor.WHITE);
        ItemStack result = decorate(item, ratio, List.of());
        result.setAmount(TankMenuLayout.displayAmount(units));
        return result;
    }

    private ItemStack fluidItem(FluidKey key) {
        if (settings.theme() == TankMenuSettings.Theme.PLAIN || settings.fluidItemPrefix().isEmpty()) return null;
        String texture = bridge.registry().find(key).flatMap(type -> type.texture()).orElse("");
        for (String identifier : fluidCandidates(settings.fluidItemPrefix(), texture, key)) {
            ItemStack item = resource(identifier);
            if (item != null) return item;
        }
        return null;
    }

    static List<String> fluidCandidates(String prefix, String texture, FluidKey key) {
        if (prefix.isEmpty()) return List.of();
        var identifiers = new LinkedHashSet<String>();
        if (texture != null && !texture.contains("..") && texture.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) {
            int separator = texture.indexOf(':');
            if (separator >= 0) {
                identifiers.add(prefix + texture.replace(':', '_').replace('/', '_'));
                identifiers.add(prefix + texture.substring(separator + 1).replace('/', '_'));
            } else identifiers.add(prefix + texture.replace('/', '_'));
        }
        identifiers.add(prefix + key.namespace() + "_" + key.value().replace('/', '_'));
        identifiers.add(prefix + key.value().replace('/', '_'));
        return List.copyOf(identifiers);
    }

    private ItemStack resource(String identifier) {
        if (settings.theme() == TankMenuSettings.Theme.PLAIN || identifier.isEmpty()) return null;
        var definition = CraftEngineItems.byId(identifier);
        if (definition == null) return null;
        ItemStack item = definition.buildBukkitItem();
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private static ItemStack icon(Material material, String name, UiTextStyle.Role role, List<Component> lore) {
        return decorate(new ItemStack(material), UiTextStyle.text(name, role), lore);
    }

    private static ItemStack decorate(ItemStack source, Component name, List<Component> lore) {
        ItemStack item = source.clone();
        item.editMeta(meta -> {
            meta.displayName(UiTextStyle.upright(name));
            if (meta.hasItemName()) meta.itemName(UiTextStyle.upright(meta.itemName()));
            meta.lore(lore.stream().map(UiTextStyle::upright).toList());
        });
        return item;
    }
}
