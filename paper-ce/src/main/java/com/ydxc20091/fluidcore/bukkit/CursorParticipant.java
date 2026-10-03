/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.api.StorageContext;
import com.ydxc20091.fluidcore.api.TransactionParticipant;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/** Same-owner edits to the actual player cursor, preserving complete stack metadata. */
public final class CursorParticipant implements TransactionParticipant {
    private final Player player;
    private final StorageContext context;
    private ItemStack expected;
    private boolean captured;

    public CursorParticipant(Player player, StorageContext context) {
        this.player = Objects.requireNonNull(player);
        this.context = Objects.requireNonNull(context);
    }

    public ItemStack current() {
        context.checkAccess();
        return copy(player.getItemOnCursor());
    }

    public boolean canPut(ItemStack item) {
        context.checkAccess();
        validate();
        return combined(copy(player.getItemOnCursor()), item) != null;
    }

    /** Adds the complete stack to an empty or compatible cursor, without partial insertion. */
    public boolean put(ItemStack item, FluidTransaction transaction) {
        context.checkAccess();
        validate();
        ItemStack before = copy(player.getItemOnCursor());
        ItemStack proposed = combined(before, item);
        if (proposed == null) return false;
        apply(before, proposed, transaction);
        return true;
    }

    public ItemStack take(int maximum, FluidTransaction transaction) {
        context.checkAccess();
        validate();
        ItemStack before = copy(player.getItemOnCursor());
        if (maximum < 1 || before == null) return null;
        ItemStack taken = before.clone();
        taken.setAmount(Math.min(maximum, before.getAmount()));
        ItemStack remainder = before.clone();
        remainder.setAmount(before.getAmount() - taken.getAmount());
        apply(before, copy(remainder), transaction);
        return taken;
    }

    /** Replaces only the exact cursor value observed by the caller. Empty values normalize to null. */
    public boolean replace(ItemStack original, ItemStack replacement, FluidTransaction transaction) {
        context.checkAccess();
        validate();
        ItemStack before = copy(player.getItemOnCursor());
        if (!Objects.equals(copy(original), before)) return false;
        ItemStack proposed = copy(replacement);
        if (proposed != null && proposed.getAmount() > proposed.getMaxStackSize()) return false;
        apply(before, proposed, transaction);
        return true;
    }

    private static ItemStack combined(ItemStack current, ItemStack item) {
        if (empty(item)) return null;
        if (current != null && !current.isSimilar(item)) return null;
        long amount = (current == null ? 0L : current.getAmount()) + item.getAmount();
        int maximum = current == null ? item.getMaxStackSize()
                : Math.min(current.getMaxStackSize(), item.getMaxStackSize());
        if (amount > maximum) return null;
        ItemStack proposed = current == null ? item.clone() : current.clone();
        proposed.setAmount((int) amount);
        return proposed;
    }

    private void apply(ItemStack before, ItemStack proposed, FluidTransaction transaction) {
        Objects.requireNonNull(transaction).enlist(this);
        validate();
        if (!Objects.equals(before, copy(player.getItemOnCursor())))
            throw new StorageAccessException("Cursor changed while preparing the fluid transaction");
        expected = copy(proposed);
        player.setItemOnCursor(copy(proposed));
        validate();
    }

    @Override public Object snapshot() {
        context.checkAccess();
        boolean first = !captured;
        expected = copy(player.getItemOnCursor());
        captured = true;
        return new Snapshot(copy(expected), context.tick(), first);
    }

    @Override public void restore(Object value) {
        context.checkAccess();
        var old = (Snapshot) value;
        if (Objects.equals(expected, copy(player.getItemOnCursor())))
            player.setItemOnCursor(copy(old.item));
        captured = !old.first;
        // Keep the parent's expectation so an external value remains protected on outer rollback.
        expected = captured ? copy(old.item) : null;
    }

    @Override public void validate() {
        context.checkAccess();
        if (captured && !Objects.equals(expected, copy(player.getItemOnCursor())))
            throw new StorageAccessException("Cursor changed during the fluid transaction");
    }

    @Override public void validateSnapshot(Object value) {
        validate();
        if (((Snapshot) value).tick != context.tick())
            throw new StorageAccessException("Cursor transaction crossed a server tick");
    }

    @Override public void afterCommit() { captured = false; expected = null; }
    private record Snapshot(ItemStack item, long tick, boolean first) {}
    private static boolean empty(ItemStack item) {
        return item == null || item.getAmount() < 1 || item.getType() == Material.AIR
                || item.getType() == Material.CAVE_AIR || item.getType() == Material.VOID_AIR;
    }
    private static ItemStack copy(ItemStack item) { return empty(item) ? null : item.clone(); }
}
