/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Copy-only helpers and a distinct real-slot transaction entry point. */
public final class ItemContainerTransfers {
    private static final Logger LOGGER = Logger.getLogger("FluidCore");
    public static final NamespacedKey DATA_KEY = ItemFluidData.DATA_KEY;
    public static final NamespacedKey TANK_DATA_KEY = new NamespacedKey("fluidcore", "tank_data");
    private final ItemFluidContainerRegistry containers;

    public ItemContainerTransfers(FluidRegistry registry, Function<ItemStack, ContainerDefinition> customDefinition) {
        this(registry, customDefinition, item -> false);
    }
    public ItemContainerTransfers(FluidRegistry registry, Function<ItemStack, ContainerDefinition> customDefinition,
                                 Predicate<ItemStack> customItem) {
        containers = new ItemFluidContainerRegistry(registry, customDefinition, customItem);
    }
    public ItemFluidContainerRegistry containers() { return containers; }
    public ItemFluidData itemData() { return containers.itemData(); }
    public Optional<ItemFluidContainer> resolve(ItemStack item) { return containers.resolve(item); }
    public enum Result { SUCCESS, NOT_A_CONTAINER, PROTECTED_DATA, NO_TRANSFER, NO_INVENTORY_SPACE, ATOMIC_TRANSFER_UNSUPPORTED }

    /**
     * Fills an isolated count-one item and returns its replacement. SIMULATE rolls back the source,
     * while returning the projected replacement. EXECUTE commits the source only; the caller must
     * install the replacement exactly once. For actual inventory atomicity use transfer instead.
     */
    public ItemContainerTransferResult tryFillContainer(ItemStack item, FluidStorage source, long maximum, FluidAction action) {
        return detached(item, source, maximum, action, true, null);
    }
    /** Same ownership and copy-only contract as tryFillContainer, moving fluid into the target. */
    public ItemContainerTransferResult tryEmptyContainer(ItemStack item, FluidStorage target, long maximum, FluidAction action) {
        return detached(item, target, maximum, action, false, null);
    }
    /** Joins the supplied owner transaction; the caller must install the replacement before committing it. */
    public ItemContainerTransferResult tryFillContainer(ItemStack item, FluidStorage source, long maximum, FluidAction action, FluidTransaction parent) {
        return detached(item, source, maximum, action, true, Objects.requireNonNull(parent, "parent"));
    }
    /** Like the matching fill overload, a successful child remains rollbackable by the parent. */
    public ItemContainerTransferResult tryEmptyContainer(ItemStack item, FluidStorage target, long maximum, FluidAction action, FluidTransaction parent) {
        return detached(item, target, maximum, action, false, Objects.requireNonNull(parent, "parent"));
    }
    private ItemContainerTransferResult detached(ItemStack input, FluidStorage storage, long maximum, FluidAction action, boolean fill, FluidTransaction parent) {
        Objects.requireNonNull(input, "item"); Objects.requireNonNull(storage, "storage"); Objects.requireNonNull(action, "action");
        if (maximum < 0) throw new IllegalArgumentException("Negative transfer maximum");
        storage.context().checkAccess();
        Optional<ItemFluidContainer> resolved = resolve(input);
        ItemStack original = input.clone(); if (original.getAmount() > 0) original.setAmount(1);
        if (resolved.isEmpty()) return result(ItemContainerTransferResult.Status.NOT_A_CONTAINER, original, FluidStack.EMPTY, null);
        ItemFluidContainer handler = resolved.get();
        if (handler.readResult().protectedData()) return result(ItemContainerTransferResult.Status.PROTECTED_DATA, original, FluidStack.EMPTY, handler.readResult());
        if (!storage.supportsTransactions()) return result(ItemContainerTransferResult.Status.ATOMIC_TRANSFER_UNSUPPORTED, original, FluidStack.EMPTY, handler.readResult());
        try (FluidTransaction transaction = parent == null ? FluidTransaction.open() : parent.openNested()) {
            FluidStack moved = fill ? fillContainer(handler, storage, maximum, transaction) : emptyContainer(handler, storage, maximum, transaction);
            if (moved.isEmpty()) return result(ItemContainerTransferResult.Status.NO_TRANSFER, original, moved, handler.readResult());
            if (action == FluidAction.EXECUTE) commit(transaction);
            return result(ItemContainerTransferResult.Status.SUCCESS, handler.item(), moved, handler.readResult());
        }
    }
    private static ItemContainerTransferResult result(ItemContainerTransferResult.Status status, ItemStack replacement,
                                                      FluidStack moved, ItemFluidReadResult read) {
        return new ItemContainerTransferResult(status, replacement, moved, read);
    }

    /** Creative players retain a filled input when filling storage; extraction delivers its output. */
    public Result transfer(Player player, int inventorySlot, FluidStorage storage, long maximum) {
        ItemSlotAccess slot = new ItemSlotAccess(player.getInventory(), inventorySlot, BukkitStorageContext.entity(player));
        return transfer(slot, player.getGameMode(), storage, maximum);
    }
    /**
     * Both actual slot replacement and fluid mutation enlist in this single synchronous native
     * transaction. Storage and slot must share the effective ownership context. Never crosses a
     * tick or awaits asynchronously. Creative mode retains filled inputs when filling storage,
     * but extracting from storage must replace the empty container with the transferred fluid.
     */
    public Result transfer(ItemSlotAccess slot, GameMode mode, FluidStorage storage, long maximum) {
        Objects.requireNonNull(slot, "slot"); Objects.requireNonNull(mode, "mode"); Objects.requireNonNull(storage, "storage");
        if (maximum < 0) throw new IllegalArgumentException("Negative transfer maximum");
        storage.context().checkSameContext(slot.context());
        if (!storage.supportsTransactions()) return Result.ATOMIC_TRANSFER_UNSUPPORTED;
        if (mode == GameMode.SPECTATOR) return Result.NO_TRANSFER;
        ItemStack input = slot.item();
        if (input == null) return Result.NOT_A_CONTAINER;
        Optional<ItemFluidContainer> resolved = resolve(input);
        if (resolved.isEmpty()) return Result.NOT_A_CONTAINER;
        ItemFluidContainer handler = resolved.get();
        if (handler.readResult().protectedData()) return Result.PROTECTED_DATA;
        try (FluidTransaction transaction = FluidTransaction.open()) {
            transaction.enlist(slot);
            boolean fillingContainer = handler.content().isEmpty();
            FluidStack moved = fillingContainer ? fillContainer(handler, storage, maximum, transaction)
                    : emptyContainer(handler, storage, maximum, transaction);
            if (moved.isEmpty()) return Result.NO_TRANSFER;
            if ((mode != GameMode.CREATIVE || fillingContainer)
                    && !slot.replaceOne(handler.item(), transaction)) return Result.NO_INVENTORY_SPACE;
            commit(transaction);
            return Result.SUCCESS;
        }
    }

    private static FluidStack fillContainer(ItemFluidContainer container, FluidStorage source, long maximum, FluidTransaction transaction) {
        if (maximum == 0) return FluidStack.EMPTY;
        FluidStack current = container.content();
        for (int tank = 0; tank < source.tanks(); tank++) {
            FluidStack available = source.content(tank);
            if (available.isEmpty() || !current.isEmpty() && !current.variant().equals(available.variant()) || !container.accepts(available.variant())) continue;
            long proposed = container.fill(FluidStack.of(available.variant(), maximum), FluidAction.SIMULATE);
            if (proposed == 0) continue;
            long extracted = source.extract(available.variant(), proposed, transaction);
            if (extracted == 0) continue;
            long accepted = container.fill(FluidStack.of(available.variant(), extracted), FluidAction.EXECUTE);
            // Full buckets cannot accept a partial extraction. Closing the transaction restores it.
            if (accepted != extracted) return FluidStack.EMPTY;
            return FluidStack.of(available.variant(), accepted);
        }
        return FluidStack.EMPTY;
    }
    private static FluidStack emptyContainer(ItemFluidContainer container, FluidStorage target, long maximum, FluidTransaction transaction) {
        FluidStack offered = container.drain(maximum, FluidAction.SIMULATE);
        if (offered.isEmpty()) return FluidStack.EMPTY;
        long inserted = target.insert(offered.variant(), offered.amount(), transaction);
        if (inserted == 0) return FluidStack.EMPTY;
        FluidStack removed = container.drain(offered.variant(), inserted, FluidAction.EXECUTE);
        if (removed.amount() != inserted) return FluidStack.EMPTY;
        return removed;
    }
    private static void commit(FluidTransaction transaction) {
        try { transaction.commit(); }
        catch (FluidTransaction.CommitNotificationException notificationFailure) {
            // Resources already committed; reporting failure to retry would duplicate the operation.
            LOGGER.log(Level.WARNING, "Fluid transfer committed, but a post-commit notification failed", notificationFailure);
        }
    }
    /** Low-level raw access for migration/diagnostics; ordinary integrations should use itemData(). */
    public static byte[] data(ItemStack item, NamespacedKey key) {
        if (!item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY);
    }
    /** Explicit raw replacement, intended for migrations and tank drop serialization. */
    public static void writeData(ItemStack item, NamespacedKey key, byte[] bytes) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) throw new IllegalArgumentException("Item does not support persistent data");
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE_ARRAY, bytes.clone());
        item.setItemMeta(meta);
    }
}
