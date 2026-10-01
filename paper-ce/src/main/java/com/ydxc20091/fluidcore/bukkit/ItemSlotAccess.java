/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.api.StorageContext;
import com.ydxc20091.fluidcore.api.TransactionParticipant;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.Material;

import java.util.Arrays;
import java.util.Objects;

/** Transactional access to a real player slot and any slots receiving its replacement. */
public final class ItemSlotAccess implements TransactionParticipant {
    private final PlayerInventory inventory;
    private final int slot;
    private final StorageContext context;
    private ItemStack[] expected;

    public ItemSlotAccess(PlayerInventory inventory, int slot, StorageContext context) {
        this.inventory = Objects.requireNonNull(inventory);
        if (slot < 0 || slot >= inventory.getSize()) throw new IllegalArgumentException("Invalid player slot");
        this.slot = slot;
        this.context = Objects.requireNonNull(context);
    }

    public StorageContext context() { return context; }
    public ItemStack item() { context.checkAccess(); return copy(inventory.getItem(slot)); }

    /** Returns false before mutation when the transformed single item cannot be accommodated. */
    public boolean replaceOne(ItemStack replacement, FluidTransaction transaction) {
        context.checkAccess();
        ItemStack current = inventory.getItem(slot);
        if (empty(current)) return false;
        ItemStack[] proposed = copy(inventory.getContents());
        if (current.getAmount() == 1) proposed[slot] = copy(replacement);
        else {
            proposed[slot].setAmount(current.getAmount() - 1);
            if (!putReplacement(proposed, replacement)) return false;
        }
        transaction.enlist(this);
        // Validate before writing, so another extension cannot overwrite the inventory unnoticed.
        validate();
        inventory.setContents(copy(proposed));
        expected = proposed;
        return true;
    }

    private boolean putReplacement(ItemStack[] contents, ItemStack replacement) {
        if (empty(replacement)) return true;
        int remaining = replacement.getAmount();
        int storageSize = inventory.getStorageContents().length;
        for (int i = 0; i < storageSize; i++) {
            ItemStack item = contents[i];
            if (empty(item) || !item.isSimilar(replacement)) continue;
            int added = Math.min(remaining, Math.min(inventory.getMaxStackSize(), item.getMaxStackSize()) - item.getAmount());
            if (added <= 0) continue;
            item.setAmount(item.getAmount() + added);
            remaining -= added;
            if (remaining == 0) return true;
        }
        for (int i = 0; i < storageSize; i++) {
            if (!empty(contents[i])) continue;
            ItemStack item = replacement.clone();
            int added = Math.min(remaining, Math.min(inventory.getMaxStackSize(), item.getMaxStackSize()));
            item.setAmount(added);
            contents[i] = item;
            remaining -= added;
            if (remaining == 0) return true;
        }
        return false;
    }

    @Override public Object snapshot() {
        context.checkAccess();
        ItemStack[] actual = copy(inventory.getContents());
        expected = copy(actual);
        return new Snapshot(actual, context.tick());
    }

    @Override public void restore(Object snapshot) {
        context.checkAccess();
        ItemStack[] original = ((Snapshot) snapshot).items;
        ItemStack[] live = inventory.getContents();
        // Do not destroy an external change detected during validation.
        for (int i = 0; i < live.length; i++) {
            if (Objects.equals(live[i], expected[i])) inventory.setItem(i, copy(original[i]));
        }
        expected = copy(inventory.getContents());
    }

    @Override public void validate() {
        context.checkAccess();
        if (expected != null && !Arrays.equals(expected, inventory.getContents()))
            throw new StorageAccessException("Player inventory changed during the fluid transaction");
    }

    @Override public void validateSnapshot(Object snapshot) {
        validate();
        if (((Snapshot) snapshot).tick != context.tick()) throw new StorageAccessException("Slot transaction crossed a server tick");
    }

    @Override public void afterCommit() { expected = null; }
    private record Snapshot(ItemStack[] items, long tick) {}
    private static boolean empty(ItemStack item) {
        return item == null || item.getAmount() == 0 || item.getType() == Material.AIR
                || item.getType() == Material.CAVE_AIR || item.getType() == Material.VOID_AIR;
    }
    private static ItemStack copy(ItemStack item) { return item == null ? null : item.clone(); }
    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] result = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) result[i] = copy(items[i]);
        return result;
    }
}
