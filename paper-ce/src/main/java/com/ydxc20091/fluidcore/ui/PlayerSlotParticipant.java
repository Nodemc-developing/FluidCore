/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.api.StorageContext;
import com.ydxc20091.fluidcore.api.TransactionParticipant;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/** Exact, owner-confined replacement for explicit storage and offhand swaps. */
final class PlayerSlotParticipant implements TransactionParticipant {
    private final Player player;
    private final int slot;
    private final StorageContext context;
    private boolean captured;
    private ItemStack expected;

    PlayerSlotParticipant(Player player, int slot, StorageContext context) {
        if (slot < 0 || (slot >= 36 && slot != 40)) throw new IllegalArgumentException("Not a storage or offhand slot");
        this.player = Objects.requireNonNull(player);
        this.slot = slot;
        this.context = Objects.requireNonNull(context);
    }

    ItemStack current() { context.checkAccess(); return copy(player.getInventory().getItem(slot)); }

    boolean replace(ItemStack original, ItemStack replacement, FluidTransaction transaction) {
        context.checkAccess();
        validate();
        if (!Objects.equals(copy(original), current())) return false;
        ItemStack proposed = copy(replacement);
        if (proposed != null && proposed.getAmount() > Math.min(player.getInventory().getMaxStackSize(), proposed.getMaxStackSize())) return false;
        transaction.enlist(this);
        validate();
        if (!Objects.equals(copy(original), current())) return false;
        expected = copy(proposed);
        player.getInventory().setItem(slot, copy(proposed));
        validate();
        return true;
    }

    @Override public Object snapshot() {
        context.checkAccess();
        boolean first = !captured;
        expected = current();
        captured = true;
        return new Snapshot(copy(expected), context.tick(), first);
    }

    @Override public void restore(Object value) {
        context.checkAccess();
        var old = (Snapshot) value;
        if (Objects.equals(expected, current())) player.getInventory().setItem(slot, copy(old.item));
        captured = !old.first;
        expected = captured ? copy(old.item) : null;
    }

    @Override public void validate() {
        context.checkAccess();
        if (captured && !Objects.equals(expected, current())) throw new StorageAccessException("Player slot changed during the tank menu transaction");
    }

    @Override public void validateSnapshot(Object value) {
        validate();
        if (((Snapshot) value).tick != context.tick()) throw new StorageAccessException("Player slot transaction crossed a server tick");
    }

    @Override public void afterCommit() { captured = false; expected = null; }
    private record Snapshot(ItemStack item, long tick, boolean first) { }
    private static ItemStack copy(ItemStack item) {
        return item == null || item.getAmount() < 1 || item.getType() == Material.AIR || item.getType() == Material.CAVE_AIR
                || item.getType() == Material.VOID_AIR ? null : item.clone();
    }
}
