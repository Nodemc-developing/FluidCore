package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.api.StorageContext;
import com.ydxc20091.fluidcore.api.TransactionParticipant;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Arrays;
import java.util.Objects;

/** Atomic edits to a local inventory, with conflict-aware rollback. */
public final class InventoryParticipant implements TransactionParticipant {
    private final Inventory inventory;
    private final StorageContext context;
    private ItemStack[] expected;

    public InventoryParticipant(Inventory inventory, StorageContext context) {
        this.inventory = Objects.requireNonNull(inventory);
        this.context = Objects.requireNonNull(context);
    }

    public boolean takeOne(int slot, ItemStack original, FluidTransaction transaction) {
        return take(slot, original, 1, transaction);
    }

    public boolean take(int slot, ItemStack original, int amount, FluidTransaction transaction) {
        context.checkAccess();
        validate();
        if (slot < 0 || slot >= inventory.getSize() || amount < 1 || empty(original)
                || amount > original.getAmount()) return false;
        ItemStack[] before = copies(inventory.getContents());
        if (!Objects.equals(original, before[slot])) return false;
        ItemStack[] proposed = copies(before);
        ItemStack remainder = original.clone();
        remainder.setAmount(original.getAmount() - amount);
        proposed[slot] = empty(remainder) ? null : remainder;
        apply(before, proposed, transaction);
        return true;
    }

    public boolean putOne(ItemStack item, FluidTransaction transaction) {
        context.checkAccess();
        return !empty(item) && item.getAmount() == 1 && put(item, transaction);
    }

    /** Inserts the entire stack, or leaves every slot unchanged if it does not fit. */
    public boolean put(ItemStack item, FluidTransaction transaction) {
        context.checkAccess();
        validate();
        if (empty(item)) return false;
        ItemStack[] before = copies(inventory.getContents());
        ItemStack[] proposed = insertion(before, item);
        if (proposed == null) return false;
        apply(before, proposed, transaction);
        return true;
    }

    public boolean canPutOne(ItemStack item) {
        context.checkAccess();
        return !empty(item) && item.getAmount() == 1 && canPut(item);
    }

    public boolean canPut(ItemStack item) {
        context.checkAccess();
        validate();
        return !empty(item) && insertion(copies(inventory.getContents()), item) != null;
    }

    /** Inserts the entire stack into the selected storage slot without replacing another item. */
    public boolean putAt(int slot, ItemStack item, FluidTransaction transaction) {
        context.checkAccess();
        validate();
        if (slot < 0 || slot >= storageSize() || empty(item)) return false;
        ItemStack[] before = copies(inventory.getContents());
        ItemStack[] proposed = copies(before);
        ItemStack current = proposed[slot];
        if (!empty(current) && !current.isSimilar(item)) return false;
        long amount = (empty(current) ? 0L : current.getAmount()) + item.getAmount();
        int maximum = Math.min(inventory.getMaxStackSize(), item.getMaxStackSize());
        if (!empty(current)) maximum = Math.min(maximum, current.getMaxStackSize());
        if (amount > maximum) return false;
        ItemStack next = empty(current) ? item.clone() : current.clone();
        next.setAmount((int) amount);
        proposed[slot] = next;
        apply(before, proposed, transaction);
        return true;
    }

    private ItemStack[] insertion(ItemStack[] before, ItemStack item) {
        ItemStack[] proposed = copies(before);
        int remaining = item.getAmount();
        int maximum = Math.min(inventory.getMaxStackSize(), item.getMaxStackSize());
        if (maximum < 1) return null;
        // Keep the established first-available-slot order used by single-item transfers.
        for (int slot = 0; slot < storageSize(); slot++) {
            ItemStack current = proposed[slot];
            if (!empty(current) && !current.isSimilar(item)) continue;
            int limit = empty(current) ? maximum : Math.min(maximum, current.getMaxStackSize());
            int available = limit - (empty(current) ? 0 : current.getAmount());
            if (available <= 0) continue;
            int added = Math.min(remaining, available);
            ItemStack next = empty(current) ? item.clone() : current.clone();
            next.setAmount((empty(current) ? 0 : current.getAmount()) + added);
            proposed[slot] = next;
            remaining -= added;
            if (remaining == 0) return proposed;
        }
        return null;
    }

    private int storageSize() {
        return inventory instanceof PlayerInventory player
                ? player.getStorageContents().length : inventory.getSize();
    }

    private void apply(ItemStack[] before, ItemStack[] proposed, FluidTransaction transaction) {
        Objects.requireNonNull(transaction).enlist(this);
        validate();
        if (!Arrays.equals(before, inventory.getContents()))
            throw new StorageAccessException("Inventory changed while preparing the fluid transaction");
        // Record intended writes before calling Bukkit, so a partially failing write can roll back.
        expected = copies(proposed);
        for (int slot = 0; slot < proposed.length; slot++) {
            if (!Objects.equals(before[slot], proposed[slot])) inventory.setItem(slot, copy(proposed[slot]));
        }
        validate();
    }

    @Override public Object snapshot() {
        context.checkAccess();
        boolean first = expected == null;
        expected = copies(inventory.getContents());
        return new Snapshot(copies(expected), context.tick(), first);
    }

    @Override public void restore(Object value) {
        context.checkAccess();
        var old = (Snapshot) value;
        for (int slot = 0; slot < expected.length; slot++) {
            if (Objects.equals(expected[slot], inventory.getItem(slot)))
                inventory.setItem(slot, copy(old.items[slot]));
        }
        // A nested rollback must not adopt an external write as the parent's owned value.
        expected = old.first ? null : copies(old.items);
    }

    @Override public void validate() {
        context.checkAccess();
        if (expected != null && !Arrays.equals(expected, inventory.getContents()))
            throw new StorageAccessException("Inventory changed during the fluid transaction");
    }

    @Override public void validateSnapshot(Object value) {
        validate();
        if (((Snapshot) value).tick != context.tick())
            throw new StorageAccessException("Inventory transaction crossed a server tick");
    }

    @Override public void afterCommit() { expected = null; }
    private record Snapshot(ItemStack[] items, long tick, boolean first) {}
    private static boolean empty(ItemStack item) {
        return item == null || item.getAmount() < 1 || item.getType() == Material.AIR
                || item.getType() == Material.CAVE_AIR || item.getType() == Material.VOID_AIR;
    }
    private static ItemStack copy(ItemStack item) { return item == null ? null : item.clone(); }
    private static ItemStack[] copies(ItemStack[] items) {
        return Arrays.stream(items).map(InventoryParticipant::copy).toArray(ItemStack[]::new);
    }
}
