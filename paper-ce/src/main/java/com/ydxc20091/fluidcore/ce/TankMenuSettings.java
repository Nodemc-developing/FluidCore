/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.core.plugin.config.ConfigSection;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable presentation settings. Resource identifiers resolve only against loaded content. */
public record TankMenuSettings(Theme theme, String backgroundImage, int backgroundOffset, int titleOffset,
                               String titleKey, String emptyItem, String borderItem, String bucketItem,
                               String bottleItem, String progressItemPrefix, String fluidItemPrefix) {
    public enum Theme { AUTO, PLAIN }
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern TRANSLATION = Pattern.compile("[a-zA-Z0-9_.:/-]+");
    private static final TankMenuSettings DEFAULTS = new TankMenuSettings(Theme.AUTO, "", -8, -98, "", "", "", "", "", "", "");

    public TankMenuSettings {
        Objects.requireNonNull(theme, "theme");
        backgroundImage = identifier(backgroundImage, "background-image");
        titleKey = translation(titleKey);
        emptyItem = identifier(emptyItem, "empty-item");
        borderItem = identifier(borderItem, "border-item");
        bucketItem = identifier(bucketItem, "bucket-item");
        bottleItem = identifier(bottleItem, "bottle-item");
        progressItemPrefix = identifier(progressItemPrefix, "progress-item-prefix");
        fluidItemPrefix = identifier(fluidItemPrefix, "fluid-item-prefix");
        checkOffset(backgroundOffset, "background-offset");
        checkOffset(titleOffset, "title-offset");
    }

    public static TankMenuSettings defaults() { return DEFAULTS; }

    static TankMenuSettings parse(ConfigSection behavior) {
        if (!behavior.containsKey("menu")) return DEFAULTS;
        Object value = behavior.get("menu");
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(behavior.assemblePath("menu") + " must be a map");
        try { return fromMap(map); }
        catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(behavior.assemblePath("menu") + ": " + failure.getMessage(), failure);
        }
    }

    /** Unknown presentation metadata remains available to content adapters without granting storage access. */
    public static TankMenuSettings fromMap(Map<?, ?> values) {
        if (!text(values, "layout", "jug").equals("jug")) throw new IllegalArgumentException("layout must be jug");
        Theme theme = switch (text(values, "theme", "auto")) {
            case "auto" -> Theme.AUTO;
            case "plain" -> Theme.PLAIN;
            default -> throw new IllegalArgumentException("theme must be auto or plain");
        };
        return new TankMenuSettings(theme, text(values, "background-image", ""), integer(values, "background-offset", -8),
                integer(values, "title-offset", -98), text(values, "title-key", ""), text(values, "empty-item", ""),
                text(values, "border-item", ""), text(values, "bucket-item", ""), text(values, "bottle-item", ""),
                text(values, "progress-item-prefix", ""), text(values, "fluid-item-prefix", ""));
    }

    private static String text(Map<?, ?> values, String key, String fallback) {
        if (!values.containsKey(key)) return fallback;
        if (!(values.get(key) instanceof String value)) throw new IllegalArgumentException(key + " must be a string");
        return value;
    }

    private static int integer(Map<?, ?> values, String key, int fallback) {
        if (!values.containsKey(key)) return fallback;
        Object value = values.get(key);
        if (!(value instanceof Number) && !(value instanceof String)) throw new IllegalArgumentException(key + " must be an integer");
        try { return new BigDecimal(value.toString()).intValueExact(); }
        catch (NumberFormatException | ArithmeticException failure) { throw new IllegalArgumentException(key + " must be an exact integer", failure); }
    }

    private static String identifier(String value, String key) {
        Objects.requireNonNull(value, key);
        if (value.startsWith("ce:")) value = value.substring(3);
        if (!value.isEmpty() && !IDENTIFIER.matcher(value).matches()) throw new IllegalArgumentException(key + " must be a namespaced resource identifier or empty");
        return value;
    }

    private static String translation(String value) {
        Objects.requireNonNull(value, "title-key");
        if (!value.isEmpty() && !TRANSLATION.matcher(value).matches()) throw new IllegalArgumentException("title-key must be a translation key or empty");
        return value;
    }

    private static void checkOffset(int value, String key) {
        if (value < -4096 || value > 4096) throw new IllegalArgumentException(key + " must be within -4096..4096");
    }
}
