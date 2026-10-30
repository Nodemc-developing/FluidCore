/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.render;

import org.bukkit.inventory.ItemStack;

/** Detached GUI items and their click-selection layout. Reading items never exposes mutable cached stacks. */
public final class FluidRenderGrid {
    private final FluidRenderLayout layout;
    private final ItemStack[][] items;
    FluidRenderGrid(FluidRenderLayout layout, ItemStack[][] items) { this.layout = layout; this.items = copy(items); }
    public FluidRenderLayout layout() { return layout; }
    public int layerAt(int row, int column) { return layout.layerAt(row, column); }
    public ItemStack item(int row, int column) { return items[row][column].clone(); }
    public ItemStack[][] items() { return copy(items); }
    private static ItemStack[][] copy(ItemStack[][] source) {
        ItemStack[][] result = new ItemStack[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = new ItemStack[source[row].length];
            for (int col = 0; col < source[row].length; col++) result[row][col] = source[row][col].clone();
        }
        return result;
    }
}
