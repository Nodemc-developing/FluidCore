package com.ydxc20091.fluidcore.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Styles detached window values without changing the storage items behind them. */
final class UiTextStyle {
    enum Role {
        HEADER(NamedTextColor.GOLD), NAME(NamedTextColor.WHITE), VALUE(NamedTextColor.AQUA),
        DESCRIPTION(NamedTextColor.GRAY), ACTION(NamedTextColor.GREEN),
        WARNING(NamedTextColor.YELLOW), ERROR(NamedTextColor.RED);
        final NamedTextColor color;
        Role(NamedTextColor color) { this.color = color; }
    }
    private UiTextStyle() { }

    static Component upright(Component component) {
        Component result = component.decoration(TextDecoration.ITALIC, false);
        if (!component.children().isEmpty()) result = result.children(component.children().stream().map(UiTextStyle::upright).toList());
        if (result instanceof TranslatableComponent translated && !translated.arguments().isEmpty()) {
            result = translated.arguments(translated.arguments().stream().map(argument ->
                    argument.value() instanceof ComponentLike value
                            ? TranslationArgument.component(upright(value.asComponent())) : argument).toList());
        }
        return result;
    }

    static Component styled(Component component, Role role) { return upright(component).colorIfAbsent(role.color); }
    static Component text(String value, Role role) { return styled(Component.text(value), role); }
    static Component title(Component component) {
        Component result = upright(component);
        return containsGlyph(component) ? result : result.colorIfAbsent(Role.HEADER.color);
    }
    private static boolean containsGlyph(Component component) {
        if (component.font() != null) return true;
        if (component instanceof TextComponent text && text.content().codePoints().anyMatch(code -> Character.getType(code) == Character.PRIVATE_USE)) return true;
        if (component.children().stream().anyMatch(UiTextStyle::containsGlyph)) return true;
        return component instanceof TranslatableComponent translated && translated.arguments().stream()
                .anyMatch(argument -> argument.value() instanceof ComponentLike value && containsGlyph(value.asComponent()));
    }

    static ItemStack displayCopy(ItemStack source, List<Component> extra) {
        ItemStack copy = source.clone();
        var meta = copy.getItemMeta();
        if (meta == null) return copy;
        if (meta.displayName() != null) meta.displayName(styled(meta.displayName(), Role.NAME));
        if (meta.hasItemName()) meta.itemName(styled(meta.itemName(), Role.NAME));
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) meta.lore().forEach(line -> lore.add(styled(line, Role.DESCRIPTION)));
        if (!extra.isEmpty()) {
            if (!lore.isEmpty()) lore.add(upright(Component.empty()));
            extra.forEach(line -> lore.add(upright(line)));
        }
        meta.lore(lore);
        copy.setItemMeta(meta);
        return copy;
    }
}
