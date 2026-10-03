/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ItemSlotAccessTest {
    @Test void stackedContainerSplitsIntoARealStorageSlotAndRollsBack() {
        InventoryDouble inventory = new InventoryDouble();
        inventory.contents[0] = new BareStack(Material.BUCKET, 3);
        ItemSlotAccess slot = new ItemSlotAccess(inventory.inventory, 0, StorageContext.confinedToCurrentThread());
        try (FluidTransaction transaction = FluidTransaction.open()) {
            assertTrue(slot.replaceOne(new BareStack(Material.WATER_BUCKET, 1), transaction));
            assertEquals(2, inventory.contents[0].getAmount());
            assertEquals(Material.WATER_BUCKET, inventory.contents[1].getType());
        }
        assertEquals(3, inventory.contents[0].getAmount());
        assertNull(inventory.contents[1]);
    }

    @Test void fullStorageRejectsSplitEvenWhenArmorSlotsAreEmpty() {
        InventoryDouble inventory = new InventoryDouble();
        Arrays.fill(inventory.contents, 0, 36, new BareStack(Material.STONE, 64));
        inventory.contents[0] = new BareStack(Material.BUCKET, 2);
        ItemSlotAccess slot = new ItemSlotAccess(inventory.inventory, 0, StorageContext.confinedToCurrentThread());
        try (FluidTransaction transaction = FluidTransaction.open()) {
            assertFalse(slot.replaceOne(new BareStack(Material.LAVA_BUCKET, 1), transaction));
            transaction.commit();
        }
        assertEquals(2, inventory.contents[0].getAmount());
        assertNull(inventory.contents[36]);
    }

    @Test void commitReplacesTheOriginalSingleItem() {
        InventoryDouble inventory = new InventoryDouble();
        inventory.contents[40] = new BareStack(Material.WATER_BUCKET, 1);
        ItemSlotAccess slot = new ItemSlotAccess(inventory.inventory, 40, StorageContext.confinedToCurrentThread());
        try (FluidTransaction transaction = FluidTransaction.open()) {
            assertTrue(slot.replaceOne(new BareStack(Material.BUCKET, 1), transaction));
            transaction.commit();
        }
        assertEquals(Material.BUCKET, inventory.contents[40].getType());
    }

    @Test void externalInventoryChangeFailsValidationAndSurvivesRollback() {
        InventoryDouble inventory = new InventoryDouble();
        inventory.contents[0] = new BareStack(Material.WATER_BUCKET, 1);
        ItemSlotAccess slot = new ItemSlotAccess(inventory.inventory, 0, StorageContext.confinedToCurrentThread());
        try (FluidTransaction transaction = FluidTransaction.open()) {
            assertTrue(slot.replaceOne(new BareStack(Material.BUCKET, 1), transaction));
            inventory.contents[0] = new BareStack(Material.DIAMOND, 2);
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(Material.DIAMOND, inventory.contents[0].getType());
        assertEquals(2, inventory.contents[0].getAmount());
    }

    @Test void nestedRollbackRestoresParentThenOuterRollbackRestoresOriginal() {
        InventoryDouble inventory = new InventoryDouble();
        inventory.contents[0] = new BareStack(Material.WATER_BUCKET, 1);
        ItemSlotAccess slot = new ItemSlotAccess(inventory.inventory, 0, StorageContext.confinedToCurrentThread());
        try (FluidTransaction outer = FluidTransaction.open()) {
            slot.replaceOne(new BareStack(Material.BUCKET, 1), outer);
            try (FluidTransaction nested = outer.openNested()) {
                slot.replaceOne(new BareStack(Material.LAVA_BUCKET, 1), nested);
            }
            assertEquals(Material.BUCKET, inventory.contents[0].getType());
        }
        assertEquals(Material.WATER_BUCKET, inventory.contents[0].getType());
    }

    @Test void crossingTickFailsAndRestoresInventory() {
        AtomicLong tick = new AtomicLong(1);
        StorageContext context = new StorageContext() {
            @Override public void checkAccess() {}
            @Override public long tick() { return tick.get(); }
        };
        InventoryDouble inventory = new InventoryDouble();
        inventory.contents[0] = new BareStack(Material.WATER_BUCKET, 1);
        ItemSlotAccess slot = new ItemSlotAccess(inventory.inventory, 0, context);
        try (FluidTransaction transaction = FluidTransaction.open()) {
            slot.replaceOne(new BareStack(Material.BUCKET, 1), transaction);
            tick.incrementAndGet();
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(Material.WATER_BUCKET, inventory.contents[0].getType());
    }

    private static final class InventoryDouble {
        ItemStack[] contents = new ItemStack[41];
        final PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getSize" -> contents.length;
                    case "getContents" -> contents.clone();
                    case "getStorageContents" -> Arrays.copyOf(contents, 36);
                    case "getItem" -> contents[(int) args[0]];
                    case "setItem" -> { contents[(int) args[0]] = (ItemStack) args[1]; yield null; }
                    case "setContents" -> { contents = ((ItemStack[]) args[0]).clone(); yield null; }
                    case "getMaxStackSize" -> 64;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A metadata-free stack double; no server-wide Bukkit singleton is installed by these tests. */
    private static final class BareStack extends ItemStack {
        private Material material;
        private int amount;
        BareStack(Material material, int amount) { super(); this.material = material; this.amount = amount; }
        @Override public Material getType() { return material; }
        @Override public void setType(Material value) { material = value; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int value) { amount = value; }
        @Override public BareStack clone() { return new BareStack(getType(), getAmount()); }
        @Override public int getMaxStackSize() { return getType() == Material.BUCKET ? 16 : getType().name().endsWith("_BUCKET") ? 1 : 64; }
        @Override public boolean isSimilar(ItemStack other) { return other != null && getType() == other.getType(); }
        @Override public boolean equals(Object other) { return other instanceof ItemStack item && isSimilar(item) && getAmount() == item.getAmount(); }
        @Override public int hashCode() { return getType().hashCode() * 31 + getAmount(); }
    }
}
