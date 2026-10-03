package com.ydxc20091.fluidcore.ui;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UiTextStyleTest {
    @Test void tankLabelsUseReadableUprightDefaults() {
        Component name = UiTextStyle.text("水", UiTextStyle.Role.NAME);
        assertEquals(NamedTextColor.WHITE, name.color());
        assertEquals(TextDecoration.State.FALSE, name.decoration(TextDecoration.ITALIC));
    }

    @Test void capacityProgressHintsAndWarningAreSeparate() {
        assertEquals(NamedTextColor.GOLD, UiTextStyle.text("储罐", UiTextStyle.Role.HEADER).color());
        assertEquals(NamedTextColor.AQUA, UiTextStyle.text("1000 / 4000 mB", UiTextStyle.Role.VALUE).color());
        assertEquals(NamedTextColor.GREEN, UiTextStyle.text("50%", UiTextStyle.Role.ACTION).color());
        assertEquals(NamedTextColor.GRAY, UiTextStyle.text("说明", UiTextStyle.Role.DESCRIPTION).color());
        assertEquals(NamedTextColor.YELLOW, UiTextStyle.text("数据受保护", UiTextStyle.Role.WARNING).color());
        assertEquals(NamedTextColor.RED, UiTextStyle.text("关闭", UiTextStyle.Role.ERROR).color());
    }

    @Test void previewKeepsExplicitItemColorAndOriginalComponent() {
        Component original = Component.text("自定义水桶", NamedTextColor.BLUE).decorate(TextDecoration.ITALIC);
        Component result = UiTextStyle.styled(original, UiTextStyle.Role.NAME);
        assertEquals(NamedTextColor.BLUE, result.color());
        assertEquals(TextDecoration.State.FALSE, result.decoration(TextDecoration.ITALIC));
        assertEquals(TextDecoration.State.TRUE, original.decoration(TextDecoration.ITALIC));
    }

    @Test void nestedTooltipComponentsAreUpright() {
        Component result = UiTextStyle.upright(Component.text("提示").append(Component.text("点击").decorate(TextDecoration.ITALIC)));
        assertEquals(TextDecoration.State.FALSE, result.children().getFirst().decoration(TextDecoration.ITALIC));
    }

    @Test void translatedTypedArgumentsRemainTyped() {
        TranslatableComponent original = Component.translatable("tank.capacity", TranslationArgument.numeric(4000), TranslationArgument.bool(false),
                Component.text("mB", NamedTextColor.AQUA).decorate(TextDecoration.ITALIC));
        TranslatableComponent result = (TranslatableComponent) UiTextStyle.upright(original);
        assertEquals(original.key(), result.key());
        assertEquals(original.arguments().get(0).value(), result.arguments().get(0).value());
        assertEquals(original.arguments().get(1).value(), result.arguments().get(1).value());
        assertEquals(TextDecoration.State.FALSE, result.arguments().get(2).asComponent().decoration(TextDecoration.ITALIC));
    }

    @Test void resourceFontAndGlyphArePreserved() {
        Component original = Component.text("\ue123", NamedTextColor.WHITE).font(Key.key("fluidcore", "gui"));
        Component result = UiTextStyle.upright(original);
        assertEquals(original.font(), result.font());
        assertEquals(original.color(), result.color());
        assertEquals(((net.kyori.adventure.text.TextComponent) original).content(), ((net.kyori.adventure.text.TextComponent) result).content());
    }

    @Test void glyphTitleDoesNotAcquireGoldTint() {
        Component glyph = Component.text("\ue123");
        assertNull(UiTextStyle.title(glyph).color());
    }

    @Test void ordinaryTitleIsGoldAndUpright() {
        Component title = UiTextStyle.title(Component.text("流体储罐"));
        assertEquals(NamedTextColor.GOLD, title.color());
        assertEquals(TextDecoration.State.FALSE, title.decoration(TextDecoration.ITALIC));
    }
}
