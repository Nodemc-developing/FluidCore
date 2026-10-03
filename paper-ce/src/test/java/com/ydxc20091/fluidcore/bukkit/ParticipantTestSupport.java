/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.api.StorageContext;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Proxy;
import java.util.Arrays;

final class ParticipantTestSupport {
    static final NamespacedKey FOREIGN = new NamespacedKey("quest", "payload");
    static final NamespacedKey IDENTIFIER = new NamespacedKey("custom", "identifier");

    private ParticipantTestSupport() { }

    static ItemContainerTransfersTest.DataStack stack(int amount) {
        var item = new ItemContainerTransfersTest.DataStack(Material.STONE, amount);
        item.getItemMeta().setDisplayName("§6Named container");
        item.getItemMeta().getPersistentDataContainer().set(FOREIGN, PersistentDataType.BYTE_ARRAY, new byte[]{1, 7, -2});
        item.getItemMeta().getPersistentDataContainer().set(IDENTIFIER, PersistentDataType.STRING, "farmersdelight:input");
        return item;
    }

    static final class Owner implements StorageContext {
        final Thread thread = Thread.currentThread();
        boolean available = true;
        long tick = 7;
        @Override public void checkAccess() {
            if (!available || Thread.currentThread() != thread) throw new StorageAccessException("Wrong owner");
        }
        @Override public long tick() { checkAccess(); return tick; }
    }

    static final class InventoryFixture {
        final Owner owner = new Owner();
        final ItemStack[] contents = new ItemStack[41];
        int writes;
        int maximum = 64;
        int failAfterWriting = -1;
        final PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getSize" -> contents.length;
                    case "getContents" -> contents.clone();
                    case "getStorageContents" -> Arrays.copyOf(contents, 36);
                    case "getItem" -> contents[(int) args[0]];
                    case "getMaxStackSize" -> maximum;
                    case "setItem" -> {
                        int slot = (int) args[0];
                        contents[slot] = (ItemStack) args[1];
                        writes++;
                        if (slot == failAfterWriting) {
                            failAfterWriting = -1;
                            throw new IllegalStateException("Injected write failure");
                        }
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final InventoryParticipant access = new InventoryParticipant(inventory, owner);
        void fillStorage() {
            for (int slot = 0; slot < 36; slot++) contents[slot] = new ItemContainerTransfersTest.DataStack(Material.DIRT, 64);
        }
    }

    static final class CursorFixture {
        final Owner owner = new Owner();
        ItemStack cursor;
        int reads;
        int writes;
        boolean failAfterWrite;
        final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getItemOnCursor" -> { reads++; yield cursor; }
                    case "setItemOnCursor" -> {
                        cursor = (ItemStack) args[0];
                        writes++;
                        if (failAfterWrite) {
                            failAfterWrite = false;
                            throw new IllegalStateException("Injected cursor write failure");
                        }
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final CursorParticipant access = new CursorParticipant(player, owner);
    }
}
