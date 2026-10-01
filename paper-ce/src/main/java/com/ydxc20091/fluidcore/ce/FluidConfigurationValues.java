/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Strict scalar conversion avoids truncating fractional or overflowing configuration values. */
final class FluidConfigurationValues {
    private FluidConfigurationValues() {}

    static long longValue(ConfigSection section, String key, long fallback) {
        if (!section.containsKey(key)) return fallback;
        Object value = section.get(key);
        if (!(value instanceof Number) && !(value instanceof String))
            throw new IllegalArgumentException(key + " must be an integer");
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (NumberFormatException | ArithmeticException failure) {
            throw new IllegalArgumentException(key + " must be an integer within the signed long range", failure);
        }
    }

    static int intValue(ConfigSection section, String key, int fallback) {
        try { return Math.toIntExact(longValue(section, key, fallback)); }
        catch (ArithmeticException failure) { throw new IllegalArgumentException(key + " must fit an int", failure); }
    }

    static String string(ConfigSection section, String key) {
        if (!section.containsKey(key)) return null;
        Object value = section.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " must be a nonblank string");
        return text;
    }

    static List<String> strings(ConfigSection section, String key) {
        if (!section.containsKey(key)) return List.of();
        Object value = section.get(key);
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(key + " must be a list of identifiers");
        List<String> result = new ArrayList<>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " contains an invalid identifier");
            result.add(text);
        }
        return List.copyOf(result);
    }
}
