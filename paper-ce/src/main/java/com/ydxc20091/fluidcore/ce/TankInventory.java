package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.*;
import org.bukkit.inventory.ItemStack;
import java.util.Objects;

/** Two owner-confined real slots. Display items never participate in storage transactions. */
public final class TankInventory implements TransactionParticipant {
    private final StorageContext context;
    private final Runnable changed;
    private ItemStack input, output;
    private ItemStack expectedInput, expectedOutput;
    private long version;
    TankInventory(StorageContext context, Runnable changed) { this.context = context; this.changed = changed; }
    public long version() { context.checkAccess(); return version; }
    public ItemStack input() { context.checkAccess(); return copy(input); }
    public ItemStack output() { context.checkAccess(); return copy(output); }
    public boolean canAcceptInput(ItemStack item) { context.checkAccess(); return fits(input, item); }
    public boolean canAcceptOutput(ItemStack item) { context.checkAccess(); return fits(output, item); }
    public boolean insertInput(ItemStack item, FluidTransaction transaction) {
        context.checkAccess(); if (!fits(input, item)) return false;
        transaction.enlist(this); validate(); input = merge(input, item); expect(); return true;
    }
    public boolean insertOutput(ItemStack item, FluidTransaction transaction) {
        context.checkAccess(); if (!fits(output, item)) return false;
        transaction.enlist(this); validate(); output = merge(output, item); expect(); return true;
    }
    /** Swaps the authoritative input only while its complete observed value still matches. */
    public boolean replaceInput(ItemStack original, ItemStack replacement, FluidTransaction transaction) {
        context.checkAccess();
        if (!Objects.equals(copy(original), copy(input))
                || (!empty(replacement) && replacement.getAmount() > replacement.getMaxStackSize())) return false;
        transaction.enlist(this); validate(); input = copy(replacement); expect(); return true;
    }
    public boolean replaceOutput(ItemStack original, ItemStack replacement, FluidTransaction transaction) {
        context.checkAccess();
        if (!Objects.equals(copy(original), copy(output))
                || (!empty(replacement) && replacement.getAmount() > replacement.getMaxStackSize())) return false;
        transaction.enlist(this); validate(); output = copy(replacement); expect(); return true;
    }
    public ItemStack extractOutput(int maximum, FluidTransaction transaction) {
        context.checkAccess(); if (maximum < 1 || empty(output)) return null;
        transaction.enlist(this); validate(); ItemStack result = output.clone();
        result.setAmount(Math.min(maximum, output.getAmount())); output.setAmount(output.getAmount() - result.getAmount());
        if (empty(output)) output = null; expect(); return result;
    }
    public ItemStack extractInput(int maximum, FluidTransaction transaction) {
        context.checkAccess(); if (maximum < 1 || empty(input)) return null;
        transaction.enlist(this); validate(); ItemStack result = input.clone();
        result.setAmount(Math.min(maximum, input.getAmount())); input.setAmount(input.getAmount() - result.getAmount());
        if (empty(input)) input = null; expect(); return result;
    }
    public boolean completeOne(ItemStack original, ItemStack result, FluidTransaction transaction) {
        context.checkAccess(); if (empty(input) || !input.equals(original) || !fits(output, result)) return false;
        transaction.enlist(this); validate(); input.setAmount(input.getAmount() - 1); if (empty(input)) input = null;
        output = merge(output, result); expect(); return true;
    }
    void load(ItemStack input, ItemStack output) { this.input = copy(input); this.output = copy(output); expect(); }
    @Override public Object snapshot() { context.checkAccess(); expect(); return new Snapshot(copy(input), copy(output), context.tick()); }
    @Override public void restore(Object snapshot) {
        context.checkAccess(); Snapshot old = (Snapshot) snapshot;
        if (Objects.equals(input, expectedInput)) input = copy(old.input);
        if (Objects.equals(output, expectedOutput)) output = copy(old.output);
        expect();
    }
    @Override public void validate() {
        context.checkAccess(); if (!Objects.equals(input, expectedInput) || !Objects.equals(output, expectedOutput)) throw new StorageAccessException("Tank items changed during transaction");
    }
    @Override public void validateSnapshot(Object snapshot) { validate(); if (((Snapshot) snapshot).tick != context.tick()) throw new StorageAccessException("Tank item transaction crossed a tick"); }
    @Override public void afterCommit() { version++; changed.run(); }
    private void expect() { expectedInput = copy(input); expectedOutput = copy(output); }
    private record Snapshot(ItemStack input, ItemStack output, long tick) {}
    private static boolean fits(ItemStack existing, ItemStack incoming) {
        if (empty(incoming)) return false;
        return empty(existing) ? incoming.getAmount() <= incoming.getMaxStackSize()
                : existing.isSimilar(incoming) && (long) existing.getAmount() + incoming.getAmount() <= existing.getMaxStackSize();
    }
    private static ItemStack merge(ItemStack existing, ItemStack incoming) { ItemStack item = empty(existing) ? incoming.clone() : existing.clone(); if (!empty(existing)) item.setAmount(item.getAmount() + incoming.getAmount()); return item; }
    private static boolean empty(ItemStack item) { return item == null || (item.getType() == org.bukkit.Material.AIR || item.getType() == org.bukkit.Material.CAVE_AIR || item.getType() == org.bukkit.Material.VOID_AIR) || item.getAmount() <= 0; }
    private static ItemStack copy(ItemStack item) { return empty(item) ? null : item.clone(); }
}
