/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.core.FluidReadResult;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import com.ydxc20091.fluidcore.core.FluidStackCodec;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import java.io.IOException;
import java.util.Objects;

/** Public versioned item data access. Calls must run on the item's owning thread. */
public final class ItemFluidData {
    public static final NamespacedKey DATA_KEY = new NamespacedKey("fluidcore", "container_data");
    public static final NamespacedKey INITIALIZED_KEY = new NamespacedKey("fluidcore", "container_initialized");
    private final FluidStackCodec codec;
    public ItemFluidData(FluidRegistry registry) { codec = new FluidStackCodec(Objects.requireNonNull(registry)); }

    public ItemFluidReadResult read(ItemStack item) {
        Objects.requireNonNull(item, "item");
        if (ForeignFluidData.item(item)) return result(item, ItemFluidReadResult.Status.CONFLICT, FluidStack.EMPTY, new byte[0], "Foreign fluid data is preserved and requires an explicit migration");
        if (!item.hasItemMeta()) return result(item, ItemFluidReadResult.Status.ABSENT, FluidStack.EMPTY, new byte[0], "No item fluid record");
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (pdc.has(INITIALIZED_KEY) && !pdc.has(INITIALIZED_KEY, PersistentDataType.BYTE))
            return result(item, ItemFluidReadResult.Status.WRONG_TYPE, FluidStack.EMPTY, new byte[0], "Initialization marker has the wrong PDC type");
        if (pdc.has(INITIALIZED_KEY)) {
            Byte initialized = pdc.get(INITIALIZED_KEY, PersistentDataType.BYTE);
            if (initialized == null || initialized != 1)
                return result(item, ItemFluidReadResult.Status.INVALID, FluidStack.EMPTY, new byte[0], "Invalid initialization marker");
        }
        if (pdc.has(DATA_KEY) && pdc.has(ItemContainerTransfers.TANK_DATA_KEY))
            return result(item, ItemFluidReadResult.Status.CONFLICT, FluidStack.EMPTY, new byte[0], "Item carries both container and block-tank records");
        NamespacedKey recordKey = pdc.has(ItemContainerTransfers.TANK_DATA_KEY) ? ItemContainerTransfers.TANK_DATA_KEY : DATA_KEY;
        if (!pdc.has(recordKey)) return result(item, pdc.has(INITIALIZED_KEY) ? ItemFluidReadResult.Status.EMPTY : ItemFluidReadResult.Status.ABSENT,
                FluidStack.EMPTY, new byte[0], pdc.has(INITIALIZED_KEY) ? "Initialized empty container" : "No item fluid record");
        if (!pdc.has(recordKey, PersistentDataType.BYTE_ARRAY))
            return result(item, ItemFluidReadResult.Status.WRONG_TYPE, FluidStack.EMPTY, new byte[0], "Fluid record has the wrong PDC type");
        byte[] raw = pdc.get(recordKey, PersistentDataType.BYTE_ARRAY);
        if (raw == null) return result(item, ItemFluidReadResult.Status.WRONG_TYPE, FluidStack.EMPTY, new byte[0], "Fluid record is not readable as bytes");
        FluidReadResult decoded = codec.decode(raw);
        ItemFluidReadResult.Status status = switch (decoded.status()) {
            case EMPTY -> ItemFluidReadResult.Status.EMPTY;
            case PRESENT -> ItemFluidReadResult.Status.PRESENT;
            case UNKNOWN -> ItemFluidReadResult.Status.UNKNOWN;
            case INVALID -> ItemFluidReadResult.Status.INVALID;
        };
        return result(item, status, decoded.stack(), raw, decoded.message());
    }

    /**
     * Writes the versioned record on this item only. EMPTY removes DATA_KEY and records initialization,
     * so a configured initial fill cannot regenerate after draining. Unknown/malformed existing data
     * is rejected before any mutation; explicit recovery must preserve read(...).originalItem().
     */
    public void write(ItemStack item, FluidStack content) {
        Objects.requireNonNull(item, "item"); Objects.requireNonNull(content, "content");
        ItemFluidReadResult before = read(item);
        if (before.protectedData()) throw new ProtectedDataException(before);
        byte[] encoded = content.isEmpty() ? null : codec.encode(content);
        if (encoded != null && !codec.decode(encoded).usable())
            throw new IllegalArgumentException("Cannot write unregistered fluid or component data");
        ItemMeta meta = item.getItemMeta();
        if (meta == null) throw new IllegalArgumentException("Item does not support persistent data");
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(ItemContainerTransfers.TANK_DATA_KEY)) {
            pdc.set(ItemContainerTransfers.TANK_DATA_KEY, PersistentDataType.BYTE_ARRAY, encoded == null ? codec.encode(FluidStack.EMPTY) : encoded);
        } else if (encoded == null) pdc.remove(DATA_KEY); else pdc.set(DATA_KEY, PersistentDataType.BYTE_ARRAY, encoded);
        pdc.set(INITIALIZED_KEY, PersistentDataType.BYTE, (byte) 1);
        if (!item.setItemMeta(meta)) throw new IllegalArgumentException("Item rejected persistent metadata");
    }
    /** Copy-only form. This never mutates the caller's item, including on a rejected write. */
    public ItemStack withFluid(ItemStack item, FluidStack content) {
        ItemStack replacement = Objects.requireNonNull(item).clone(); write(replacement, content); return replacement;
    }
    ItemFluidReadResult conflict(ItemStack item, String message) {
        byte[] raw = ItemContainerTransfers.data(item, DATA_KEY);
        return result(item, ItemFluidReadResult.Status.CONFLICT, FluidStack.EMPTY, raw == null ? new byte[0] : raw, message);
    }
    private static ItemFluidReadResult result(ItemStack item, ItemFluidReadResult.Status status, FluidStack stack, byte[] raw, String message) {
        byte[] pdcSnapshot = new byte[0];
        if (item.hasItemMeta()) {
            try { pdcSnapshot = item.getItemMeta().getPersistentDataContainer().serializeToBytes(); }
            catch (IOException | UnsupportedOperationException ignored) { /* original item snapshot remains lossless. */ }
        }
        return new ItemFluidReadResult(status, stack, raw, pdcSnapshot, message, item);
    }
    public static final class ProtectedDataException extends IllegalStateException {
        private final ItemFluidReadResult readResult;
        ProtectedDataException(ItemFluidReadResult readResult) { super(readResult.message()); this.readResult = readResult; }
        public ItemFluidReadResult readResult() { return readResult; }
    }
}
