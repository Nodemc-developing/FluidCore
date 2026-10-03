/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Objects;

/** Isolated opaque-metadata stack double; real CraftItemStack coverage belongs to native checks. */
final class UiActionTestStack extends ItemStack {
    private final Material material;
    private final int maximum;
    final String name;
    final byte[] payload;
    private int amount;
    UiActionTestStack(int amount) { this(Material.STONE, amount, 64, "Named input", new byte[]{1, 7, -2}); }
    UiActionTestStack(Material material, int amount, int maximum, String name, byte[] payload) {
        super(); this.material = material; this.amount = amount; this.maximum = maximum; this.name = name; this.payload = payload.clone();
    }
    @Override public Material getType() { return material; }
    @Override public int getAmount() { return amount; }
    @Override public void setAmount(int amount) { this.amount = amount; }
    @Override public int getMaxStackSize() { return maximum; }
    @Override public UiActionTestStack clone() { return new UiActionTestStack(material, amount, maximum, name, payload); }
    @Override public boolean isSimilar(ItemStack value) {
        return value instanceof UiActionTestStack other && material == other.material && maximum == other.maximum
                && Objects.equals(name, other.name) && Arrays.equals(payload, other.payload);
    }
    @Override public boolean equals(Object value) { return value instanceof ItemStack other && amount == other.getAmount() && isSimilar(other); }
    @Override public int hashCode() { return Objects.hash(material, amount, maximum, name, Arrays.hashCode(payload)); }
}
