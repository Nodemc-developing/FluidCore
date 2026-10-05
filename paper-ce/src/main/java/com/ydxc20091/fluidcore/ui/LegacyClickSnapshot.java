/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import org.bukkit.inventory.ItemStack;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/** Stops a deferred native click from consuming an item replaced after the event. */
final class LegacyClickSnapshot {
    private final ItemStack cursor;
    private final Map<Integer, ItemStack> sources;
    private final ItemStack displayedInput, displayedOutput;
    private LegacyClickSnapshot(ItemStack cursor, Map<Integer, ItemStack> sources, ItemStack displayedInput, ItemStack displayedOutput) {
        this.cursor = cursor; this.sources = sources;
        this.displayedInput = displayedInput; this.displayedOutput = displayedOutput;
    }
    static LegacyClickSnapshot capture(ItemStack cursor, IntFunction<ItemStack> source, int... slots) {
        return capture(cursor, source, null, null, slots);
    }
    static LegacyClickSnapshot capture(ItemStack cursor, IntFunction<ItemStack> source, ItemStack displayedInput, ItemStack displayedOutput, int... slots) {
        Map<Integer, ItemStack> observed = new LinkedHashMap<>();
        for (int slot : slots) observed.put(slot, copy(source.apply(slot)));
        return new LegacyClickSnapshot(copy(cursor), observed, copy(displayedInput), copy(displayedOutput));
    }
    boolean matches(ItemStack currentCursor, IntFunction<ItemStack> source) {
        if (!Objects.equals(cursor, copy(currentCursor))) return false;
        for (var entry : sources.entrySet()) if (!Objects.equals(entry.getValue(), copy(source.apply(entry.getKey())))) return false;
        return true;
    }
    boolean matchesTank(ItemStack currentInput, ItemStack currentOutput) {
        return Objects.equals(displayedInput, copy(currentInput)) && Objects.equals(displayedOutput, copy(currentOutput));
    }
    private static ItemStack copy(ItemStack item) { return TankClickPlan.empty(item) ? null : item.clone(); }
}
