/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.api.TransactionParticipant;
import com.ydxc20091.fluidcore.bukkit.BukkitStorageContext;
import com.ydxc20091.fluidcore.bukkit.CursorParticipant;
import com.ydxc20091.fluidcore.bukkit.InventoryParticipant;
import com.ydxc20091.fluidcore.ce.CraftEngineBridge;
import com.ydxc20091.fluidcore.ce.FluidTankController;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Mouse actions over actual player and tank storage; display inventories are never written back. */
public final class TankMenuActions {
    public record Context(Player player, FluidTankController tank, Location position, BooleanSupplier active) {
        public Context {
            Objects.requireNonNull(player); Objects.requireNonNull(tank); Objects.requireNonNull(active);
            position = Objects.requireNonNull(position).clone();
            Objects.requireNonNull(position.getWorld());
        }
        @Override public Location position() { return position.clone(); }
    }

    public enum Kind { TANK_CLICK, PLAYER_CLICK, SHIFT_TO_INPUT, DRAG }
    public record Request(Kind kind, boolean output, int storageSlot, ClickType click, int hotbarButton,
                          boolean single, List<Integer> playerStorageSlots, boolean includesInput) {
        public Request {
            Objects.requireNonNull(kind); Objects.requireNonNull(click);
            playerStorageSlots = List.copyOf(playerStorageSlots);
        }
    }

    private final CraftEngineBridge bridge;
    private final TankMenuTransfers transfers;
    public TankMenuActions(CraftEngineBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge);
        this.transfers = new TankMenuTransfers(bridge);
    }

    public CompletableFuture<Boolean> click(Context context, boolean output, ClickType click, int hotbarButton) {
        return execute(context, new Request(Kind.TANK_CLICK, output, -1, click, hotbarButton, false, List.of(), false));
    }

    public CompletableFuture<Boolean> playerClick(Context context, int storageSlot, ClickType click, int hotbarButton) {
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) return shiftToInput(context, storageSlot);
        return execute(context, new Request(Kind.PLAYER_CLICK, false, storageSlot, click, hotbarButton, false, List.of(), false));
    }

    public CompletableFuture<Boolean> shiftToInput(Context context, int playerStorageSlot) {
        return execute(context, new Request(Kind.SHIFT_TO_INPUT, false, playerStorageSlot,
                ClickType.SHIFT_LEFT, -1, false, List.of(), false));
    }

    public CompletableFuture<Boolean> drag(Context context, boolean single, List<Integer> playerStorageSlots, boolean includesInput) {
        return execute(context, new Request(Kind.DRAG, false, -1, ClickType.LEFT, -1, single, playerStorageSlots, includesInput));
    }

    /** Native collection sees display slots too; collect only authoritative player storage. */
    public CompletableFuture<Boolean> collectPlayer(Context context) {
        return completed(() -> {
            if (!validPlayer(context)) return false;
            var cursor = cursor(context);
            ItemStack original = cursor.current();
            if (TankClickPlan.empty(original) || original.getAmount() >= original.getMaxStackSize()) return false;
            var inventory = context.player().getInventory();
            var source = new InventoryParticipant(inventory, BukkitStorageContext.entity(context.player()));
            return transaction(context, false, tx -> {
                int amount = original.getAmount();
                for (int slot = 0; slot < 36 && amount < original.getMaxStackSize(); slot++) {
                    ItemStack item = inventory.getItem(slot);
                    if (TankClickPlan.empty(item) || !original.isSimilar(item)) continue;
                    int take = Math.min(item.getAmount(), original.getMaxStackSize() - amount);
                    if (!source.take(slot, item.clone(), take, tx)) return false;
                    amount += take;
                }
                if (amount == original.getAmount()) return false;
                return cursor.replace(original, TankClickPlan.amount(original, amount), tx);
            });
        });
    }

    private CompletableFuture<Boolean> execute(Context context, Request request) {
        try {
            if (!validPlayer(context) || (request.kind() != Kind.DRAG && !supported(request.click())))
                return CompletableFuture.completedFuture(false);
            boolean needsTank = request.kind() != Kind.PLAYER_CLICK && (request.kind() != Kind.DRAG || request.includesInput());
            if (needsTank && !Bukkit.isOwnedByCurrentRegion(context.position())) return transfers.execute(context, request);
            return completed(() -> switch (request.kind()) {
                case TANK_CLICK -> tankClick(context, request.output(), request.click(), request.hotbarButton());
                case PLAYER_CLICK -> playerClickLocal(context, request.storageSlot(), request.click(), request.hotbarButton());
                case SHIFT_TO_INPUT -> shiftToInputLocal(context, request.storageSlot());
                case DRAG -> dragLocal(context, request.single(), request.playerStorageSlots(), request.includesInput());
            });
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }

    private boolean tankClick(Context context, boolean output, ClickType click, int hotbar) {
        if (!validTank(context)) return false;
        if (output) return extractOutput(context);
        return switch (click) {
            case LEFT, RIGHT -> {
                var cursor = cursor(context);
                ItemStack before = context.tank().inventory().input(), carried = cursor.current();
                var plan = TankClickPlan.normal(before, carried, click == ClickType.RIGHT, Integer.MAX_VALUE);
                yield plan != null && transaction(context, true, tx -> context.tank().inventory().replaceInput(before, plan.slot(), tx)
                        && cursor.replace(carried, plan.cursor(), tx));
            }
            case SHIFT_LEFT, SHIFT_RIGHT -> inputToStorage(context);
            case NUMBER_KEY, SWAP_OFFHAND -> swapInput(context, selectedSlot(click, hotbar));
            case DROP, CONTROL_DROP -> dropInput(context, click == ClickType.CONTROL_DROP);
            default -> false;
        };
    }

    private boolean extractOutput(Context context) {
        var cursor = cursor(context);
        if (cursor.current() != null) return false;
        ItemStack original = context.tank().inventory().output();
        if (TankClickPlan.empty(original) || !cursor.canPut(original)) return false;
        return transaction(context, true, tx -> {
            ItemStack item = context.tank().inventory().extractOutput(original.getAmount(), tx);
            return Objects.equals(original, item) && cursor.put(item, tx);
        });
    }

    private boolean inputToStorage(Context context) {
        ItemStack original = context.tank().inventory().input();
        var playerInventory = context.player().getInventory();
        int count = TankClickPlan.storageFit(original, playerInventory.getStorageContents(), playerInventory.getMaxStackSize());
        if (count < 1) return false;
        var destination = new InventoryParticipant(playerInventory, BukkitStorageContext.entity(context.player()));
        return transaction(context, true, tx -> context.tank().inventory().replaceInput(original,
                TankClickPlan.amount(original, original.getAmount() - count), tx)
                && destination.put(TankClickPlan.amount(original, count), tx));
    }

    private boolean shiftToInputLocal(Context context, int storageSlot) {
        if (!storageSlot(storageSlot) || !validTank(context)) return false;
        var source = playerSlot(context, storageSlot);
        ItemStack original = source.current(), input = context.tank().inventory().input();
        int count = TankClickPlan.inputFit(input, original);
        if (count < 1) return false;
        ItemStack nextInput = TankClickPlan.amount(input == null ? original : input, (input == null ? 0 : input.getAmount()) + count);
        return transaction(context, true, tx -> context.tank().inventory().replaceInput(input, nextInput, tx)
                && source.replace(original, TankClickPlan.amount(original, original.getAmount() - count), tx));
    }

    private boolean swapInput(Context context, int selectedSlot) {
        if (selectedSlot < 0) return false;
        var selected = playerSlot(context, selectedSlot);
        ItemStack original = context.tank().inventory().input(), held = selected.current();
        var plan = TankClickPlan.swap(original, held, Integer.MAX_VALUE, context.player().getInventory().getMaxStackSize());
        return plan != null && transaction(context, true, tx -> context.tank().inventory().replaceInput(original, plan.slot(), tx)
                && selected.replace(held, plan.cursor(), tx));
    }

    private boolean playerClickLocal(Context context, int storageSlot, ClickType click, int hotbar) {
        if (!storageSlot(storageSlot)) return false;
        var source = playerSlot(context, storageSlot);
        ItemStack original = source.current();
        return switch (click) {
            case LEFT, RIGHT -> {
                var cursor = cursor(context);
                ItemStack carried = cursor.current();
                var plan = TankClickPlan.normal(original, carried, click == ClickType.RIGHT, context.player().getInventory().getMaxStackSize());
                yield plan != null && transaction(context, false, tx -> source.replace(original, plan.slot(), tx)
                        && cursor.replace(carried, plan.cursor(), tx));
            }
            case NUMBER_KEY, SWAP_OFFHAND -> {
                int selectedSlot = selectedSlot(click, hotbar);
                if (selectedSlot < 0 || selectedSlot == storageSlot) yield false;
                var selected = playerSlot(context, selectedSlot);
                ItemStack held = selected.current();
                int maximum = context.player().getInventory().getMaxStackSize();
                var plan = TankClickPlan.swap(original, held, maximum, maximum);
                yield plan != null && transaction(context, false, tx -> source.replace(original, plan.slot(), tx)
                        && selected.replace(held, plan.cursor(), tx));
            }
            case DROP, CONTROL_DROP -> drop(context, original, click == ClickType.CONTROL_DROP, false,
                    (remaining, tx) -> source.replace(original, remaining, tx));
            default -> false;
        };
    }

    private boolean dropInput(Context context, boolean all) {
        ItemStack original = context.tank().inventory().input();
        return drop(context, original, all, true, (remaining, tx) -> context.tank().inventory().replaceInput(original, remaining, tx));
    }

    private boolean drop(Context context, ItemStack original, boolean all, boolean needsTank, Replacement replacement) {
        if (TankClickPlan.empty(original) || cursor(context).current() != null) return false;
        int count = all ? original.getAmount() : 1;
        ItemStack droppedStack = TankClickPlan.amount(original, count);
        Item[] spawned = new Item[1];
        boolean[] committed = {false};
        try {
            boolean result = transaction(context, needsTank, tx -> {
                if (!replacement.apply(TankClickPlan.amount(original, original.getAmount() - count), tx)) return false;
                Location location = context.player().getEyeLocation().subtract(0, .3, 0);
                Item item = location.getWorld().dropItem(location, droppedStack.clone());
                spawned[0] = item;
                item.setVelocity(location.getDirection().multiply(.3));
                var event = new PlayerDropItemEvent(context.player(), item);
                Bukkit.getPluginManager().callEvent(event);
                return !event.isCancelled() && item.isValid() && Objects.equals(droppedStack, item.getItemStack());
            });
            committed[0] = result;
            return result;
        } finally {
            if (!committed[0] && spawned[0] != null) spawned[0].remove();
        }
    }

    private boolean dragLocal(Context context, boolean single, List<Integer> storageSlots, boolean includesInput) {
        if (includesInput && !validTank(context)) return false;
        var distinct = new LinkedHashSet<>(storageSlots);
        if (distinct.stream().anyMatch(slot -> slot == null || !storageSlot(slot))) return false;
        var cursor = cursor(context);
        ItemStack carried = cursor.current();
        if (carried == null) return false;
        List<ItemStack> originals = new ArrayList<>();
        List<Integer> maximums = new ArrayList<>();
        List<PlayerSlotParticipant> destinations = new ArrayList<>();
        if (includesInput) { originals.add(context.tank().inventory().input()); maximums.add(Integer.MAX_VALUE); }
        for (int slot : distinct) {
            var destination = playerSlot(context, slot);
            destinations.add(destination); originals.add(destination.current()); maximums.add(context.player().getInventory().getMaxStackSize());
        }
        var plan = TankClickPlan.drag(carried, originals, maximums, single);
        if (plan == null || plan.moved() < 1) return false;
        return transaction(context, includesInput, tx -> {
            int offset = includesInput ? 1 : 0;
            if (includesInput && !Objects.equals(originals.getFirst(), plan.slots().getFirst())
                    && !context.tank().inventory().replaceInput(originals.getFirst(), plan.slots().getFirst(), tx)) return false;
            for (int i = 0; i < destinations.size(); i++) {
                int index = i + offset;
                if (!Objects.equals(originals.get(index), plan.slots().get(index))
                        && !destinations.get(i).replace(originals.get(index), plan.slots().get(index), tx)) return false;
            }
            return cursor.replace(carried, plan.cursor(), tx);
        });
    }

    private boolean transaction(Context context, boolean needsTank, Edit edit) {
        long generation = bridge.generation(), activation = context.tank().activationVersion();
        if (!validPlayer(context) || (needsTank && !validTank(context))) return false;
        try (var transaction = FluidTransaction.open()) {
            transaction.enlist(new TransactionParticipant() {
                @Override public Object snapshot() { validate(); return null; }
                @Override public void restore(Object value) { }
                @Override public void afterCommit() { }
                @Override public void validate() {
                    if (generation != bridge.generation() || !validPlayer(context)
                            || (needsTank && (!context.tank().sameActivation(activation) || !validTank(context))))
                        throw new StorageAccessException("Tank menu action was invalidated");
                }
            });
            if (!edit.apply(transaction)) return false;
            try { transaction.commit(); }
            catch (RuntimeException notification) { if (!transaction.isCommitted()) throw notification; }
            return true;
        }
    }

    private boolean validPlayer(Context context) {
        Player player = context.player();
        return bridge.running() && Bukkit.isOwnedByCurrentRegion(player) && context.active().getAsBoolean()
                && player.isOnline() && player.isValid() && !player.isDead();
    }

    private boolean validTank(Context context) {
        Location position = context.position();
        return validPlayer(context) && Bukkit.isOwnedByCurrentRegion(position) && position.getWorld().isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4)
                && bridge.isLoadedTank(context.tank()) && bridge.resolver().controller(position).orElse(null) == context.tank()
                && !context.tank().hasProtectedData();
    }

    private static boolean supported(ClickType click) {
        return switch (click) { case LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT, NUMBER_KEY, SWAP_OFFHAND, DROP, CONTROL_DROP -> true; default -> false; };
    }
    private static int selectedSlot(ClickType click, int hotbar) { return click == ClickType.SWAP_OFFHAND ? 40 : hotbar >= 0 && hotbar < 9 ? hotbar : -1; }
    private static boolean storageSlot(int slot) { return slot >= 0 && slot < 36; }
    private static CursorParticipant cursor(Context context) { return new CursorParticipant(context.player(), BukkitStorageContext.entity(context.player())); }
    private static PlayerSlotParticipant playerSlot(Context context, int slot) { return new PlayerSlotParticipant(context.player(), slot, BukkitStorageContext.entity(context.player())); }
    private static CompletableFuture<Boolean> completed(Supplier<Boolean> action) {
        try { return CompletableFuture.completedFuture(action.get()); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    @FunctionalInterface private interface Edit { boolean apply(FluidTransaction transaction); }
    @FunctionalInterface private interface Replacement { boolean apply(ItemStack replacement, FluidTransaction transaction); }
}
