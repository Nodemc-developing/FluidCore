/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.core.*;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ItemContainerTransfersTest {
    private static final FluidVariant WATER = FluidVariant.of("minecraft:water");
    private static final ContainerDefinition FILLED = new ContainerDefinition(4000, Set.of(), FluidStack.of(WATER, 2000));
    private static final ContainerDefinition EMPTY = new ContainerDefinition(4000, Set.of(), FluidStack.EMPTY);
    private final FluidRegistry registry = new FluidRegistry();
    private final FluidStackCodec codec = new FluidStackCodec(registry);

    @Test void detachedChildExecutionCanBeRolledBackWithItsUnfinishedSlotTransaction() {
        Fixture fixture = new Fixture(new DataStack(Material.GLASS_BOTTLE, 1));
        var notifications = new java.util.concurrent.atomic.AtomicInteger();
        FluidTank tank = new FluidTank(8000, fixture.context, variant -> true, notifications::incrementAndGet);
        var transfer = new ItemContainerTransfers(registry, item -> FILLED);
        try (var parent = FluidTransaction.open()) {
            var converted = transfer.tryEmptyContainer(fixture.items[0], tank, 500, FluidAction.EXECUTE, parent);
            assertTrue(converted.success()); assertEquals(500, tank.content(0).amount());
            assertEquals(0, notifications.get()); assertTrue(parent.isOpen());
            assertNull(ItemContainerTransfers.data(fixture.items[0], ItemContainerTransfers.DATA_KEY));
        }
        assertTrue(tank.content(0).isEmpty()); assertEquals(0, notifications.get());
    }
    @Test void childFillDoesNotPublishBeforeTheCallerCommitsItsParent() {
        Fixture fixture = new Fixture(new DataStack(Material.GLASS_BOTTLE, 1));
        var notifications = new java.util.concurrent.atomic.AtomicInteger();
        FluidTank tank = new FluidTank(8000, fixture.context, variant -> true, notifications::incrementAndGet);
        tank.restore(FluidStack.of(WATER, 750));
        var transfer = new ItemContainerTransfers(registry, item -> EMPTY);
        try (var parent = FluidTransaction.open()) {
            var converted = transfer.tryFillContainer(fixture.items[0], tank, 375, FluidAction.EXECUTE, parent);
            assertTrue(converted.success()); assertEquals(375, converted.moved().amount());
            assertEquals(375, tank.content(0).amount()); assertEquals(0, notifications.get());
            parent.commit();
        }
        assertEquals(375, tank.content(0).amount()); assertEquals(1, notifications.get());
    }
    @Test void childPreviewReturnsDetachedMetadataWhileKeepingTheParentOpenAndFluidUnchanged() {
        Fixture fixture = new Fixture(new DataStack(Material.GLASS_BOTTLE, 1));
        FluidTank tank = new FluidTank(8000, fixture.context);
        var transfer = new ItemContainerTransfers(registry, item -> FILLED);
        try (var parent = FluidTransaction.open()) {
            var preview = transfer.tryEmptyContainer(fixture.items[0], tank, 500, FluidAction.SIMULATE, parent);
            assertTrue(preview.success()); assertEquals(500, preview.moved().amount());
            assertTrue(tank.content(0).isEmpty()); assertTrue(parent.isOpen());
            assertEquals(1500, content(preview.replacement()).amount());
            parent.commit();
        }
        assertTrue(tank.content(0).isEmpty());
    }

    @Test void partiallyDrainsCustomContainerAndConservesFluid() {
        Fixture fixture = new Fixture(new DataStack(Material.GLASS_BOTTLE, 1));
        FluidTank tank = new FluidTank(8000, fixture.context);
        var transfer = new ItemContainerTransfers(registry, item -> FILLED);
        assertEquals(ItemContainerTransfers.Result.SUCCESS, transfer.transfer(fixture.slot, GameMode.SURVIVAL, tank, 500));
        assertEquals(500, tank.content(0).amount());
        assertEquals(1500, content(fixture.items[0]).amount());
    }

    @Test void committedNotificationFailureStillReportsSuccessAndCannotTriggerAVanillaRetry() {
        Fixture fixture = new Fixture(new DataStack(Material.GLASS_BOTTLE, 1));
        FluidTank tank = new FluidTank(8000, fixture.context, variant -> true, () -> { throw new IllegalStateException("Listener failed"); });
        var transfer = new ItemContainerTransfers(registry, item -> FILLED);
        assertEquals(ItemContainerTransfers.Result.SUCCESS, transfer.transfer(fixture.slot, GameMode.SURVIVAL, tank, 500));
        assertEquals(500, tank.content(0).amount());
        assertEquals(1500, content(fixture.items[0]).amount());
    }

    @Test void stackedCustomContainersSplitWithoutChangingTheOthers() {
        Fixture fixture = new Fixture(new DataStack(Material.GLASS_BOTTLE, 3));
        FluidTank tank = new FluidTank(8000, fixture.context);
        var transfer = new ItemContainerTransfers(registry, item -> FILLED);
        assertEquals(ItemContainerTransfers.Result.SUCCESS, transfer.transfer(fixture.slot, GameMode.SURVIVAL, tank, 1000));
        assertEquals(2, fixture.items[0].getAmount());
        assertNull(ItemContainerTransfers.data(fixture.items[0], ItemContainerTransfers.DATA_KEY));
        assertEquals(1000, content(fixture.items[1]).amount());
        assertEquals(1000, tank.content(0).amount());
        assertEquals(6000, fixture.items[0].getAmount() * FILLED.initialContent().amount() + content(fixture.items[1]).amount() + tank.content(0).amount());
    }

    @Test void fullBackpackRollsBackFluidWhenSplitReplacementCannotFit() {
        Fixture fixture = new Fixture(new DataStack(Material.GLASS_BOTTLE, 2));
        for (int i = 1; i < 36; i++) fixture.items[i] = new DataStack(Material.STONE, 64);
        FluidTank tank = new FluidTank(8000, fixture.context);
        var transfer = new ItemContainerTransfers(registry, item -> FILLED);
        assertEquals(ItemContainerTransfers.Result.NO_INVENTORY_SPACE, transfer.transfer(fixture.slot, GameMode.SURVIVAL, tank, 1000));
        assertTrue(tank.content(0).isEmpty());
        assertEquals(2, fixture.items[0].getAmount());
        assertNull(ItemContainerTransfers.data(fixture.items[0], ItemContainerTransfers.DATA_KEY));
    }

    @Test void emptyCustomContainerCanAcceptLessThanOneBucket() {
        Fixture fixture = new Fixture(new DataStack(Material.GLASS_BOTTLE, 1));
        FluidTank tank = new FluidTank(8000, fixture.context);
        tank.restore(FluidStack.of(WATER, 375));
        var transfer = new ItemContainerTransfers(registry, item -> EMPTY);
        assertEquals(ItemContainerTransfers.Result.SUCCESS, transfer.transfer(fixture.slot, GameMode.SURVIVAL, tank, Long.MAX_VALUE));
        assertTrue(tank.content(0).isEmpty());
        assertEquals(375, content(fixture.items[0]).amount());
    }

    @Test void creativeRetainsTheOriginalContainerWhileFillingStorage() {
        Fixture fixture = new Fixture(new DataStack(Material.WATER_BUCKET, 1));
        FluidTank tank = new FluidTank(8000, fixture.context);
        var transfer = new ItemContainerTransfers(registry, item -> null);
        assertEquals(ItemContainerTransfers.Result.SUCCESS, transfer.transfer(fixture.slot, GameMode.CREATIVE, tank, Long.MAX_VALUE));
        assertEquals(1000, tank.content(0).amount());
        assertEquals(Material.WATER_BUCKET, fixture.items[0].getType());
        assertEquals(1, fixture.items[0].getAmount());
    }

    @Test void creativeExtractsIntoARealBucketForEverySupportedVanillaFluid() {
        Map<FluidVariant, Material> fluids = Map.of(
                WATER, Material.WATER_BUCKET,
                FluidVariant.of("minecraft:milk"), Material.MILK_BUCKET,
                FluidVariant.of("minecraft:lava"), Material.LAVA_BUCKET);
        for (var fluid : fluids.entrySet()) {
            Fixture fixture = new Fixture(new DataStack(Material.BUCKET, 1));
            FluidTank tank = new FluidTank(8000, fixture.context);
            tank.restore(FluidStack.of(fluid.getKey(), 1000));
            var transfer = new ItemContainerTransfers(registry, item -> null);
            assertEquals(ItemContainerTransfers.Result.SUCCESS,
                    transfer.transfer(fixture.slot, GameMode.CREATIVE, tank, Long.MAX_VALUE));
            assertTrue(tank.content(0).isEmpty());
            assertEquals(fluid.getValue(), fixture.items[0].getType());
            assertEquals(1, fixture.items[0].getAmount());
        }
    }

    @Test void creativeExtractionWithNoOutputSpacePreservesFluidAndStackedEmptyBuckets() {
        Fixture fixture = new Fixture(new DataStack(Material.BUCKET, 2));
        for (int i = 1; i < 36; i++) fixture.items[i] = new DataStack(Material.STONE, 64);
        FluidTank tank = new FluidTank(8000, fixture.context);
        tank.restore(FluidStack.of(WATER, 2000));
        var transfer = new ItemContainerTransfers(registry, item -> null);
        assertEquals(ItemContainerTransfers.Result.NO_INVENTORY_SPACE,
                transfer.transfer(fixture.slot, GameMode.CREATIVE, tank, Long.MAX_VALUE));
        assertEquals(FluidStack.of(WATER, 2000), tank.content(0));
        assertEquals(Material.BUCKET, fixture.items[0].getType());
        assertEquals(2, fixture.items[0].getAmount());
    }

    @Test void creativePartiallyFillsACustomContainerWithoutLosingFluid() {
        Fixture fixture = new Fixture(new DataStack(Material.GLASS_BOTTLE, 1));
        FluidTank tank = new FluidTank(8000, fixture.context);
        tank.restore(FluidStack.of(WATER, 375));
        var transfer = new ItemContainerTransfers(registry, item -> EMPTY);
        assertEquals(ItemContainerTransfers.Result.SUCCESS,
                transfer.transfer(fixture.slot, GameMode.CREATIVE, tank, Long.MAX_VALUE));
        assertTrue(tank.content(0).isEmpty());
        assertEquals(375, content(fixture.items[0]).amount());
    }

    @Test void ceBucketWithoutFluidSettingRetainsItsIdentityAndCannotBecomeAVanillaBucket() {
        NamespacedKey ceId = new NamespacedKey("craftengine", "id");
        Fixture fixture = new Fixture(new DataStack(Material.WATER_BUCKET, 1));
        fixture.items[0].getItemMeta().getPersistentDataContainer().set(ceId, PersistentDataType.STRING, "example:decorative_bucket");
        FluidTank tank = new FluidTank(8000, fixture.context);
        var transfer = new ItemContainerTransfers(registry, item -> null,
                item -> item.getItemMeta().getPersistentDataContainer().has(ceId));
        assertEquals(ItemContainerTransfers.Result.NOT_A_CONTAINER, transfer.transfer(fixture.slot, GameMode.SURVIVAL, tank, Long.MAX_VALUE));
        assertTrue(tank.content(0).isEmpty());
        assertEquals(Material.WATER_BUCKET, fixture.items[0].getType());
        assertEquals("example:decorative_bucket", fixture.items[0].getItemMeta().getPersistentDataContainer().get(ceId, PersistentDataType.STRING));
    }

    @Test void vanillaBucketConversionPreservesNameAndAnotherPluginsPersistentData() {
        NamespacedKey foreign = new NamespacedKey("anotherplugin", "quest_id");
        Fixture fixture = new Fixture(new DataStack(Material.WATER_BUCKET, 1));
        fixture.items[0].getItemMeta().setDisplayName("Named Water Bucket");
        fixture.items[0].getItemMeta().getPersistentDataContainer().set(foreign, PersistentDataType.STRING, "quest-17");
        FluidTank tank = new FluidTank(8000, fixture.context);
        var transfer = new ItemContainerTransfers(registry, item -> null);
        assertEquals(ItemContainerTransfers.Result.SUCCESS, transfer.transfer(fixture.slot, GameMode.SURVIVAL, tank, Long.MAX_VALUE));
        assertEquals(1000, tank.content(0).amount());
        assertEquals(Material.BUCKET, fixture.items[0].getType());
        assertEquals("Named Water Bucket", fixture.items[0].getItemMeta().getDisplayName());
        assertEquals("quest-17", fixture.items[0].getItemMeta().getPersistentDataContainer().get(foreign, PersistentDataType.STRING));
    }

    @Test void vanillaBucketWithUnresolvedFluidCoreDataCannotSilentlyIgnoreIt() {
        for (NamespacedKey key : List.of(ItemContainerTransfers.DATA_KEY, ItemContainerTransfers.TANK_DATA_KEY)) {
            Fixture fixture = new Fixture(new DataStack(Material.WATER_BUCKET, 1));
            byte[] unknown = new byte[]{4, 9, 1};
            ItemContainerTransfers.writeData(fixture.items[0], key, unknown);
            FluidTank tank = new FluidTank(8000, fixture.context);
            var transfer = new ItemContainerTransfers(registry, item -> null);
            assertEquals(ItemContainerTransfers.Result.PROTECTED_DATA, transfer.transfer(fixture.slot, GameMode.SURVIVAL, tank, Long.MAX_VALUE));
            assertTrue(tank.content(0).isEmpty());
            assertArrayEquals(unknown, ItemContainerTransfers.data(fixture.items[0], key));
            assertEquals(Material.WATER_BUCKET, fixture.items[0].getType());
        }
    }

    @Test void vanillaBucketCannotCommitAPartialFill() {
        Fixture fixture = new Fixture(new DataStack(Material.WATER_BUCKET, 1));
        FluidTank tank = new FluidTank(500, fixture.context);
        var transfer = new ItemContainerTransfers(registry, item -> null);
        assertEquals(ItemContainerTransfers.Result.NO_TRANSFER, transfer.transfer(fixture.slot, GameMode.SURVIVAL, tank, Long.MAX_VALUE));
        assertTrue(tank.content(0).isEmpty());
        assertEquals(Material.WATER_BUCKET, fixture.items[0].getType());
    }

    @Test void vanillaBucketRejectsAComponentBearingVariant() {
        Fixture fixture = new Fixture(new DataStack(Material.BUCKET, 1));
        FluidTank tank = new FluidTank(8000, fixture.context);
        var colored = FluidVariant.of(WATER.fluid(), Map.of(FluidKey.of("example:color"), ComponentValue.of(new byte[]{1})));
        tank.restore(FluidStack.of(colored, 1000));
        var transfer = new ItemContainerTransfers(registry, item -> null);
        assertEquals(ItemContainerTransfers.Result.NO_TRANSFER, transfer.transfer(fixture.slot, GameMode.SURVIVAL, tank, Long.MAX_VALUE));
        assertEquals(FluidStack.of(colored, 1000), tank.content(0));
    }

    @Test void unknownAndMalformedInstanceDataArePreserved() {
        byte[] unknown = codec.encode(FluidStack.of(FluidVariant.of("missing:fluid"), 1000));
        for (byte[] raw : List.of(unknown, new byte[]{9, 2, 1})) {
            Fixture fixture = new Fixture(new DataStack(Material.GLASS_BOTTLE, 1));
            ItemContainerTransfers.writeData(fixture.items[0], ItemContainerTransfers.DATA_KEY, raw);
            FluidTank tank = new FluidTank(8000, fixture.context);
            var transfer = new ItemContainerTransfers(registry, item -> EMPTY);
            assertEquals(ItemContainerTransfers.Result.PROTECTED_DATA, transfer.transfer(fixture.slot, GameMode.SURVIVAL, tank, Long.MAX_VALUE));
            assertArrayEquals(raw, ItemContainerTransfers.data(fixture.items[0], ItemContainerTransfers.DATA_KEY));
            assertTrue(tank.content(0).isEmpty());
        }
    }

    @Test void knownOversizedContainerRemainsDrainableAfterConfigChange() {
        Fixture fixture = new Fixture(new DataStack(Material.GLASS_BOTTLE, 1));
        ItemContainerTransfers.writeData(fixture.items[0], ItemContainerTransfers.DATA_KEY, codec.encode(FluidStack.of(WATER, 2000)));
        var reduced = new ContainerDefinition(500, Set.of(FluidKey.of("minecraft:lava")), FluidStack.EMPTY);
        FluidTank tank = new FluidTank(8000, fixture.context);
        var transfer = new ItemContainerTransfers(registry, item -> reduced);
        assertEquals(ItemContainerTransfers.Result.SUCCESS, transfer.transfer(fixture.slot, GameMode.SURVIVAL, tank, 1000));
        assertEquals(1000, tank.content(0).amount());
        assertEquals(1000, content(fixture.items[0]).amount());
    }

    private FluidStack content(ItemStack item) { return codec.decode(ItemContainerTransfers.data(item, ItemContainerTransfers.DATA_KEY)).stack(); }

    static final class Fixture {
        ItemStack[] items = new ItemStack[41];
        final StorageContext context = StorageContext.confinedToCurrentThread();
        final PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getSize" -> items.length;
                    case "getContents" -> items.clone();
                    case "getStorageContents" -> Arrays.copyOf(items, 36);
                    case "getItem" -> items[(int) args[0]];
                    case "setItem" -> { items[(int) args[0]] = (ItemStack) args[1]; yield null; }
                    case "setContents" -> { items = ((ItemStack[]) args[0]).clone(); yield null; }
                    case "getMaxStackSize" -> 64;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final ItemSlotAccess slot = new ItemSlotAccess(inventory, 0, context);
        Fixture(ItemStack initial) { items[0] = initial; }
    }

    /** An isolated metadata/PDC double with Bukkit interfaces and lossless byte-array cloning. */
    static final class DataStack extends ItemStack {
        private Material material;
        private int amount;
        private String displayName;
        private int metaReads;
        private final Map<NamespacedKey, Object> data = new HashMap<>();
        DataStack(Material material, int amount) { super(); this.material = material; this.amount = amount; }
        @Override public Material getType() { return material; }
        @Override public void setType(Material value) { material = value; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int value) { amount = value; }
        @Override public int getMaxStackSize() { return material == Material.STONE ? 64 : 16; }
        @Override public boolean hasItemMeta() { return !data.isEmpty() || displayName != null; }
        @Override public io.papermc.paper.persistence.PersistentDataContainerView getPersistentDataContainer() { return persistentData(); }
        private PersistentDataContainer persistentData() {
            return (PersistentDataContainer) Proxy.newProxyInstance(PersistentDataContainer.class.getClassLoader(),
                    new Class<?>[]{PersistentDataContainer.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "get" -> {
                            Object stored = data.get(args[0]);
                            if (stored == null || !((PersistentDataType<?, ?>) args[1]).getPrimitiveType().isInstance(stored)) yield null;
                            yield stored instanceof byte[] bytes ? bytes.clone() : stored;
                        }
                        case "set" -> { Object value = args[2]; data.put((NamespacedKey) args[0], value instanceof byte[] bytes ? bytes.clone() : value); yield null; }
                        case "has" -> data.containsKey(args[0]) && (args.length == 1
                                || ((PersistentDataType<?, ?>) args[1]).getPrimitiveType().isInstance(data.get(args[0])));
                        case "serializeToBytes" -> throw new UnsupportedOperationException("PDC serialization is supplied by the server");
                        case "remove" -> { data.remove(args[0]); yield null; }
                        case "isEmpty" -> data.isEmpty();
                        case "getKeys" -> Set.copyOf(data.keySet());
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
        @Override public ItemMeta getItemMeta() {
            metaReads++;
            PersistentDataContainer container = persistentData();
            return (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(), new Class<?>[]{ItemMeta.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getPersistentDataContainer" -> container;
                        case "getDisplayName" -> displayName;
                        case "hasDisplayName" -> displayName != null;
                        case "setDisplayName" -> { displayName = (String) args[0]; yield null; }
                        default -> null;
                    });
        }
        @Override public boolean setItemMeta(ItemMeta meta) { return true; }
        int metaReads() { return metaReads; }
        @Override public DataStack clone() {
            DataStack copy = new DataStack(material, amount);
            copy.displayName = displayName;
            data.forEach((key, value) -> copy.data.put(key, value instanceof byte[] bytes ? bytes.clone() : value));
            return copy;
        }
        @Override public boolean isSimilar(ItemStack other) {
            if (!(other instanceof DataStack stack) || material != stack.material || !Objects.equals(displayName, stack.displayName)
                    || !data.keySet().equals(stack.data.keySet())) return false;
            for (var entry : data.entrySet()) {
                Object value = entry.getValue(); Object otherValue = stack.data.get(entry.getKey());
                if (value instanceof byte[] bytes ? !(otherValue instanceof byte[] otherBytes) || !Arrays.equals(bytes, otherBytes) : !Objects.equals(value, otherValue)) return false;
            }
            return true;
        }
        @Override public boolean equals(Object other) { return other instanceof ItemStack stack && isSimilar(stack) && amount == stack.getAmount(); }
        @Override public int hashCode() { return material.hashCode() * 31 + amount; }
    }
}
