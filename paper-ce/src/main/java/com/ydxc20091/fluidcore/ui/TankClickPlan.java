/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Detached item arithmetic; it never reads a live inventory or applies an edit. */
final class TankClickPlan {
    record Change(ItemStack slot, ItemStack cursor) { }
    record Drag(List<ItemStack> slots, ItemStack cursor, int moved) { }
    private TankClickPlan() { }

    static Change normal(ItemStack value, ItemStack carried, boolean right, int slotMaximum) {
        ItemStack slot = copy(value), cursor = copy(carried);
        if (cursor == null) {
            if (slot == null) return null;
            int taken = right ? slot.getAmount() / 2 + slot.getAmount() % 2 : slot.getAmount();
            if (taken > slot.getMaxStackSize()) return null;
            return new Change(amount(slot, slot.getAmount() - taken), amount(slot, taken));
        }
        if (slot == null || slot.isSimilar(cursor)) {
            int space = Math.min(slotMaximum, cursor.getMaxStackSize()) - (slot == null ? 0 : slot.getAmount());
            if (slot != null) space = Math.min(space, slot.getMaxStackSize() - slot.getAmount());
            int moved = Math.min(space, right ? 1 : cursor.getAmount());
            if (moved < 1) return null;
            return new Change(amount(slot == null ? cursor : slot, (slot == null ? 0 : slot.getAmount()) + moved),
                    amount(cursor, cursor.getAmount() - moved));
        }
        return swap(slot, cursor, slotMaximum, Integer.MAX_VALUE);
    }

    static Change swap(ItemStack value, ItemStack incoming, int slotMaximum, int otherMaximum) {
        ItemStack slot = copy(value), other = copy(incoming);
        if (Objects.equals(slot, other)) return null;
        if (other != null && other.getAmount() > Math.min(slotMaximum, other.getMaxStackSize())) return null;
        if (slot != null && slot.getAmount() > Math.min(otherMaximum, slot.getMaxStackSize())) return null;
        return new Change(other, slot);
    }

    static int inputFit(ItemStack input, ItemStack incoming) {
        if (empty(incoming) || (!empty(input) && !input.isSimilar(incoming))) return 0;
        int maximum = empty(input) ? incoming.getMaxStackSize() : Math.min(input.getMaxStackSize(), incoming.getMaxStackSize());
        return Math.max(0, Math.min(incoming.getAmount(), maximum - (empty(input) ? 0 : input.getAmount())));
    }

    static int storageFit(ItemStack incoming, ItemStack[] contents, int maximum) {
        if (empty(incoming)) return 0;
        long space = 0;
        for (ItemStack item : contents) {
            if (!empty(item) && !item.isSimilar(incoming)) continue;
            int limit = Math.min(maximum, incoming.getMaxStackSize());
            if (!empty(item)) limit = Math.min(limit, item.getMaxStackSize());
            space += Math.max(0, limit - (empty(item) ? 0 : item.getAmount()));
            if (space >= incoming.getAmount()) return incoming.getAmount();
        }
        return (int) space;
    }

    /** Normal drag distributes equal shares; right drag deposits at most one per compatible slot. */
    static Drag drag(ItemStack carried, List<ItemStack> values, List<Integer> maximums, boolean single) {
        if (empty(carried) || values.size() != maximums.size()) return null;
        List<Integer> spaces = new ArrayList<>(values.size());
        int eligible = 0;
        for (int i = 0; i < values.size(); i++) {
            ItemStack item = values.get(i);
            int space = empty(item) || item.isSimilar(carried)
                    ? Math.max(0, Math.min(maximums.get(i), carried.getMaxStackSize()) - (empty(item) ? 0 : item.getAmount())) : 0;
            if (!empty(item)) space = Math.min(space, Math.max(0, item.getMaxStackSize() - item.getAmount()));
            spaces.add(space);
            if (space > 0) eligible++;
        }
        if (eligible == 0) return null;
        int share = single ? 1 : carried.getAmount() / eligible;
        if (share < 1) return null;
        List<ItemStack> proposed = new ArrayList<>(values.size());
        int remaining = carried.getAmount();
        for (int i = 0; i < values.size(); i++) {
            ItemStack old = copy(values.get(i));
            int added = Math.min(remaining, Math.min(share, spaces.get(i)));
            proposed.add(added == 0 ? old : amount(old == null ? carried : old, (old == null ? 0 : old.getAmount()) + added));
            remaining -= added;
        }
        return new Drag(java.util.Collections.unmodifiableList(proposed), amount(carried, remaining), carried.getAmount() - remaining);
    }

    static ItemStack amount(ItemStack item, int amount) {
        if (amount < 1 || empty(item)) return null;
        ItemStack result = item.clone();
        result.setAmount(amount);
        return result;
    }
    static boolean empty(ItemStack item) {
        return item == null || item.getAmount() < 1 || item.getType() == Material.AIR
                || item.getType() == Material.CAVE_AIR || item.getType() == Material.VOID_AIR;
    }
    static ItemStack copy(ItemStack item) { return empty(item) ? null : item.clone(); }
}
