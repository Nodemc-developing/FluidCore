/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.core.*;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.ydxc20091.fluidcore.bukkit.ItemContainerTransfersTest.DataStack;
import static org.junit.jupiter.api.Assertions.*;

class ItemFluidContainersTest {
    private static final FluidVariant WATER = FluidVariant.of("minecraft:water");
    private static final FluidVariant LAVA = FluidVariant.of("minecraft:lava");
    private final FluidRegistry registry = new FluidRegistry();
    private final ContainerDefinition empty = new ContainerDefinition(4000, Set.of(), FluidStack.EMPTY);
    private ItemContainerTransfers dynamic(ContainerDefinition definition) { return new ItemContainerTransfers(registry, item -> definition); }
    @Test void honeyBottlesTransferExactlyQuarterBucketAndNeverBecomeAnEmptyBucket() {
        var transfers = new ItemContainerTransfers(registry,item -> null);
        var honey = FluidVariant.of("minecraft:honey");
        var handler = transfers.resolve(new DataStack(Material.HONEY_BOTTLE,1)).orElseThrow();
        assertEquals(250,handler.capacity()); assertEquals(250,handler.content().amount());
        assertTrue(handler.drain(honey,249,FluidAction.EXECUTE).isEmpty());
        assertEquals(250,handler.drain(honey,250,FluidAction.EXECUTE).amount()); assertEquals(Material.GLASS_BOTTLE,handler.item().getType());
        assertEquals(0,handler.fill(FluidStack.of(honey,249),FluidAction.EXECUTE));
        assertEquals(250,handler.fill(FluidStack.of(honey,250),FluidAction.EXECUTE)); assertEquals(Material.HONEY_BOTTLE,handler.item().getType());
    }
    @Test void carriedTankUsesOneRecordAndEmptyingNeverRegeneratesOrErasesForeignData() {
        var transfers = dynamic(empty); ItemStack item = new DataStack(Material.STONE,1);
        ItemContainerTransfers.writeData(item,ItemContainerTransfers.TANK_DATA_KEY,new FluidStackCodec(registry).encode(FluidStack.of(WATER,1000)));
        var handler = transfers.resolve(item).orElseThrow(); assertEquals(1000,handler.content().amount());
        assertEquals(1000,handler.drain(WATER,1000,FluidAction.EXECUTE).amount());
        ItemStack replacement = handler.item();
        assertNotNull(ItemContainerTransfers.data(replacement,ItemContainerTransfers.TANK_DATA_KEY));
        assertNull(ItemContainerTransfers.data(replacement,ItemFluidData.DATA_KEY));
        assertTrue(transfers.resolve(replacement).orElseThrow().content().isEmpty());
        assertEquals(1000,transfers.resolve(item).orElseThrow().content().amount());
        replacement.getItemMeta().getPersistentDataContainer().set(new NamespacedKey("libuid","fluid"),PersistentDataType.STRING,"opaque");
        assertTrue(transfers.itemData().read(replacement).protectedData());
        assertThrows(ItemFluidData.ProtectedDataException.class,()->transfers.itemData().write(replacement,FluidStack.EMPTY));
    }

    @Test void publicDataAbsentWritePresentThenEmptyRemovesRecordAndRetainsForeignData() {
        ItemFluidData data = new ItemFluidData(registry);
        ItemStack item = new DataStack(Material.GLASS_BOTTLE, 1);
        NamespacedKey foreign = new NamespacedKey("other", "quest");
        item.getItemMeta().getPersistentDataContainer().set(foreign, PersistentDataType.STRING, "keep");
        assertEquals(ItemFluidReadResult.Status.ABSENT, data.read(item).status());
        data.write(item, FluidStack.of(WATER, 456));
        assertEquals(FluidStack.of(WATER, 456), data.read(item).stack());
        data.write(item, FluidStack.EMPTY);
        assertFalse(item.getItemMeta().getPersistentDataContainer().has(ItemFluidData.DATA_KEY));
        assertEquals(ItemFluidReadResult.Status.EMPTY, data.read(item).status());
        assertEquals("keep", item.getItemMeta().getPersistentDataContainer().get(foreign, PersistentDataType.STRING));
    }
    @Test void emptyMarkerPreventsConfiguredInitialFluidFromRegenerating() {
        var transfers = dynamic(new ContainerDefinition(4000, Set.of(), FluidStack.of(WATER, 2000)));
        ItemStack original = new DataStack(Material.GLASS_BOTTLE, 1);
        ItemFluidContainer handler = transfers.resolve(original).orElseThrow();
        assertEquals(2000, handler.drain(Long.MAX_VALUE, FluidAction.EXECUTE).amount());
        ItemStack emptyItem = handler.item();
        assertNull(ItemContainerTransfers.data(emptyItem, ItemFluidData.DATA_KEY));
        assertTrue(transfers.resolve(emptyItem).orElseThrow().content().isEmpty());
        assertEquals(2000, transfers.resolve(original).orElseThrow().content().amount());
    }
    @Test void dynamicFillAndDrainArePartialIdentityAwareAndSimulateDoesNotMutate() {
        var transfers = dynamic(empty);
        ItemStack original = new DataStack(Material.GLASS_BOTTLE, 7);
        ItemFluidContainer handler = transfers.resolve(original).orElseThrow();
        assertEquals(1, handler.item().getAmount());
        assertEquals(125, handler.fill(FluidStack.of(WATER, 125), FluidAction.SIMULATE));
        assertTrue(handler.content().isEmpty());
        assertEquals(125, handler.fill(FluidStack.of(WATER, 125), FluidAction.EXECUTE));
        assertEquals(0, handler.fill(FluidStack.of(LAVA, 25), FluidAction.EXECUTE));
        assertTrue(handler.drain(LAVA, 100, FluidAction.EXECUTE).isEmpty());
        assertEquals(50, handler.drain(WATER, 50, FluidAction.SIMULATE).amount());
        assertEquals(125, handler.content().amount());
        assertEquals(50, handler.drain(WATER, 50, FluidAction.EXECUTE).amount());
        assertEquals(75, handler.content().amount());
        assertEquals(7, original.getAmount());
        assertFalse(original.hasItemMeta());
        ItemStack retrieved = handler.item(); retrieved.setAmount(10);
        assertEquals(1, handler.item().getAmount());
    }
    @Test void componentIdentityAndUnknownComponentsAreNotSilentlyCollapsed() {
        FluidKey component = FluidKey.of("example:batch");
        registry.registerComponentCodec("example", component, new ComponentCodec<Integer>() {
            @Override public byte[] encode(Integer value) { return new byte[]{value.byteValue()}; }
            @Override public Integer decode(byte[] bytes) { if (bytes.length != 1) throw new IllegalArgumentException("Bad component"); return (int) bytes[0]; }
        });
        FluidVariant batchOne = FluidVariant.of(WATER.fluid(), Map.of(component, ComponentValue.of(new byte[]{1})));
        FluidVariant batchTwo = FluidVariant.of(WATER.fluid(), Map.of(component, ComponentValue.of(new byte[]{2})));
        ItemFluidContainer handler = dynamic(empty).resolve(new DataStack(Material.GLASS_BOTTLE, 1)).orElseThrow();
        assertEquals(100, handler.fill(FluidStack.of(batchOne, 100), FluidAction.EXECUTE));
        assertEquals(0, handler.fill(FluidStack.of(batchTwo, 100), FluidAction.EXECUTE));
        assertTrue(handler.drain(WATER, 100, FluidAction.EXECUTE).isEmpty());
        assertEquals(100, handler.drain(batchOne, 100, FluidAction.EXECUTE).amount());
        FluidVariant unknown = FluidVariant.of(WATER.fluid(), Map.of(FluidKey.of("missing:component"), ComponentValue.of(new byte[]{1})));
        assertEquals(0, handler.fill(FluidStack.of(unknown, 100), FluidAction.SIMULATE));
        assertEquals(0, handler.fill(FluidStack.of(FluidVariant.of("missing:fluid"), 100), FluidAction.EXECUTE));
    }
    @Test void vanillaBucketsRequireFullThousandAndRetainNameAndExternalPdc() {
        var transfers = new ItemContainerTransfers(registry, item -> null);
        NamespacedKey foreign = new NamespacedKey("other", "value");
        for (Material filled : List.of(Material.WATER_BUCKET, Material.LAVA_BUCKET, Material.MILK_BUCKET)) {
            ItemStack original = new DataStack(filled, 1);
            original.getItemMeta().setDisplayName("Keep me");
            original.getItemMeta().getPersistentDataContainer().set(foreign, PersistentDataType.STRING, "external");
            ItemFluidContainer handler = transfers.resolve(original).orElseThrow();
            FluidVariant variant = handler.content().variant();
            assertTrue(handler.drain(variant, 999, FluidAction.EXECUTE).isEmpty());
            assertEquals(filled, handler.item().getType());
            assertEquals(1000, handler.drain(variant, 1000, FluidAction.SIMULATE).amount());
            assertEquals(filled, handler.item().getType());
            assertEquals(1000, handler.drain(variant, 1000, FluidAction.EXECUTE).amount());
            assertEquals(Material.BUCKET, handler.item().getType());
            assertEquals(0, handler.fill(FluidStack.of(variant, 999), FluidAction.EXECUTE));
            assertEquals(1000, handler.fill(FluidStack.of(variant, 1200), FluidAction.EXECUTE));
            assertEquals(filled, handler.item().getType());
            assertEquals("Keep me", handler.item().getItemMeta().getDisplayName());
            assertEquals("external", handler.item().getItemMeta().getPersistentDataContainer().get(foreign, PersistentDataType.STRING));
            assertEquals(filled, original.getType());
        }
    }
    @Test void typedKeyAndInvalidMarkerAreProtectedWithRecoverableOriginalItem() {
        ItemFluidData data = new ItemFluidData(registry);
        ItemStack wrong = new DataStack(Material.GLASS_BOTTLE, 1);
        wrong.getItemMeta().getPersistentDataContainer().set(ItemFluidData.DATA_KEY, PersistentDataType.STRING, "opaque record");
        ItemFluidReadResult read = data.read(wrong);
        assertEquals(ItemFluidReadResult.Status.WRONG_TYPE, read.status());
        assertThrows(ItemFluidData.ProtectedDataException.class, () -> data.write(wrong, FluidStack.EMPTY));
        assertEquals("opaque record", read.originalItem().getItemMeta().getPersistentDataContainer().get(ItemFluidData.DATA_KEY, PersistentDataType.STRING));
        assertEquals("opaque record", wrong.getItemMeta().getPersistentDataContainer().get(ItemFluidData.DATA_KEY, PersistentDataType.STRING));
        ItemStack marker = new DataStack(Material.GLASS_BOTTLE, 1);
        marker.getItemMeta().getPersistentDataContainer().set(ItemFluidData.INITIALIZED_KEY, PersistentDataType.BYTE, (byte) 0);
        assertEquals(ItemFluidReadResult.Status.INVALID, data.read(marker).status());
        assertThrows(ItemFluidData.ProtectedDataException.class, () -> data.write(marker, FluidStack.of(WATER, 5)));
    }
    @Test void vanillaNativeMarkersOfAnyPdcTypeCannotBypassProtectedRecordHandling() {
        var containers = new ItemFluidContainerRegistry(registry, item -> null, item -> false);
        for (NamespacedKey key : List.of(ItemFluidData.DATA_KEY, ItemContainerTransfers.TANK_DATA_KEY, ItemFluidData.INITIALIZED_KEY)) {
            var original = new DataStack(Material.WATER_BUCKET, 3);
            original.getItemMeta().getPersistentDataContainer().set(key, PersistentDataType.STRING, "wrong-type-preserved");
            ItemStack before = original.clone();
            ItemFluidContainer handler = containers.resolve(original).orElseThrow();
            assertTrue(handler.readResult().protectedData());
            assertEquals(0, handler.fill(FluidStack.of(WATER, 1000), FluidAction.EXECUTE));
            assertTrue(handler.drain(WATER, 1000, FluidAction.EXECUTE).isEmpty());
            assertEquals(before, original);
            assertEquals("wrong-type-preserved", handler.readResult().originalItem().getPersistentDataContainer().get(key, PersistentDataType.STRING));
        }
    }
    @Test void foreignFluidMarkersNeverResolveToMutableVanillaBucketsOrBottles() {
        var containers = new ItemFluidContainerRegistry(registry, item -> null, item -> false);
        for (Material material : List.of(Material.BUCKET, Material.WATER_BUCKET, Material.LAVA_BUCKET,
                Material.MILK_BUCKET, Material.GLASS_BOTTLE, Material.HONEY_BOTTLE)) {
            for (NamespacedKey key : List.of(new NamespacedKey("libuid", "saved_jug"),
                    new NamespacedKey("jug_color", "data"), new NamespacedKey("third_party", "opaque_fluid"),
                    new NamespacedKey("addon", "jug"), new NamespacedKey("another", "jug_contents"))) {
                var original = new DataStack(material, 3);
                original.getItemMeta().getPersistentDataContainer().set(key, PersistentDataType.STRING, "opaque-preserved");
                ItemStack before = original.clone();
                var handler = containers.resolve(original).orElseThrow();
                assertEquals(ItemFluidReadResult.Status.CONFLICT, handler.readResult().status());
                assertEquals(0, handler.fill(FluidStack.of(WATER, 1000), FluidAction.EXECUTE));
                assertTrue(handler.drain(WATER, 1000, FluidAction.EXECUTE).isEmpty());
                assertEquals(before, original);
                ItemStack countOne = before.clone(); countOne.setAmount(1);
                assertEquals(countOne, handler.item());
                assertEquals(countOne, handler.readResult().originalItem());
            }
        }
    }
    @Test void foreignPdcClassificationUsesTheReadonlyViewWithoutAnItemMetaSnapshot() {
        var plain = new DataStack(Material.BUCKET, 1);
        assertFalse(ForeignFluidData.item(plain));
        assertEquals(0, plain.metaReads());
        var legacy = new DataStack(Material.WATER_BUCKET, 1);
        legacy.getItemMeta().getPersistentDataContainer().set(new NamespacedKey("jug_color", "data"), PersistentDataType.INTEGER, 15);
        int previousReads = legacy.metaReads();
        assertTrue(ForeignFluidData.item(legacy));
        assertEquals(previousReads, legacy.metaReads());
        assertTrue(legacy.getPersistentDataContainer().has(new NamespacedKey("jug_color", "data"), PersistentDataType.INTEGER));
    }
    @Test void rejectedForeignVanillaTransfersPreserveBothInventoryItemAndTankContents() {
        var transfers = new ItemContainerTransfers(registry, item -> null);
        var source = new FluidTank(4000, StorageContext.confinedToCurrentThread());
        source.restore(FluidStack.of(WATER, 1000));
        var emptyBucket = new DataStack(Material.BUCKET, 2);
        emptyBucket.getItemMeta().getPersistentDataContainer().set(new NamespacedKey("libuid", "data"), PersistentDataType.BYTE_ARRAY, new byte[]{4, 7});
        ItemStack emptyBefore = emptyBucket.clone();
        assertEquals(ItemContainerTransferResult.Status.PROTECTED_DATA, transfers.tryFillContainer(emptyBucket, source, 1000, FluidAction.EXECUTE).status());
        assertEquals(emptyBefore, emptyBucket); assertEquals(1000, source.content(0).amount());
        var filledBucket = new DataStack(Material.WATER_BUCKET, 2);
        filledBucket.getItemMeta().getPersistentDataContainer().set(new NamespacedKey("jug_color", "data"), PersistentDataType.STRING, "opaque");
        ItemStack filledBefore = filledBucket.clone();
        var target = new FluidTank(4000, source.context());
        assertEquals(ItemContainerTransferResult.Status.PROTECTED_DATA, transfers.tryEmptyContainer(filledBucket, target, 1000, FluidAction.EXECUTE).status());
        assertEquals(filledBefore, filledBucket); assertTrue(target.content(0).isEmpty());
    }
    @Test void unknownAndMalformedRecordsRemainByteExactAndProtectedInBothApis() {
        FluidStackCodec codec = new FluidStackCodec(registry);
        for (byte[] record : List.of(codec.encode(FluidStack.of(FluidVariant.of("missing:fluid"), 11)), new byte[]{4, 5, 6})) {
            var transfers = dynamic(empty);
            ItemStack item = new DataStack(Material.GLASS_BOTTLE, 1);
            ItemContainerTransfers.writeData(item, ItemFluidData.DATA_KEY, record);
            ItemFluidReadResult read = transfers.itemData().read(item);
            assertTrue(read.protectedData());
            byte[] returned = read.raw(); returned[0] ^= 1;
            assertArrayEquals(record, read.raw());
            assertThrows(ItemFluidData.ProtectedDataException.class, () -> transfers.itemData().write(item, FluidStack.EMPTY));
            assertEquals(0, transfers.resolve(item).orElseThrow().fill(FluidStack.of(WATER, 10), FluidAction.EXECUTE));
            FluidTank target = new FluidTank(4000, StorageContext.confinedToCurrentThread());
            var result = transfers.tryEmptyContainer(item, target, Long.MAX_VALUE, FluidAction.EXECUTE);
            assertEquals(ItemContainerTransferResult.Status.PROTECTED_DATA, result.status());
            assertArrayEquals(record, ItemContainerTransfers.data(result.replacement(), ItemFluidData.DATA_KEY));
            assertTrue(target.content(0).isEmpty());
        }
    }
    @Test void detachedFillSimulationReturnsProjectionAndRollsBackSource() {
        var transfers = dynamic(empty);
        ItemStack original = new DataStack(Material.GLASS_BOTTLE, 4);
        FluidTank source = new FluidTank(8000, StorageContext.confinedToCurrentThread()); source.restore(FluidStack.of(WATER, 1500));
        var preview = transfers.tryFillContainer(original, source, 750, FluidAction.SIMULATE);
        assertTrue(preview.success()); assertEquals(750, preview.moved().amount());
        assertEquals(750, transfers.itemData().read(preview.replacement()).stack().amount());
        assertEquals(1500, source.content(0).amount()); assertFalse(original.hasItemMeta()); assertEquals(4, original.getAmount());
        var execute = transfers.tryFillContainer(original, source, 750, FluidAction.EXECUTE);
        assertTrue(execute.success()); assertEquals(750, source.content(0).amount());
        assertEquals(750, transfers.itemData().read(execute.replacement()).stack().amount());
    }
    @Test void detachedPartialFillCanTopUpExistingContentAndDrainingDoesNotTouchOriginal() {
        var transfers = dynamic(empty);
        ItemStack original = new DataStack(Material.GLASS_BOTTLE, 1);
        transfers.itemData().write(original, FluidStack.of(WATER, 3000));
        FluidTank source = new FluidTank(8000, StorageContext.confinedToCurrentThread()); source.restore(FluidStack.of(WATER, 2000));
        var filled = transfers.tryFillContainer(original, source, Long.MAX_VALUE, FluidAction.EXECUTE);
        assertEquals(1000, filled.moved().amount()); assertEquals(4000, transfers.itemData().read(filled.replacement()).stack().amount());
        assertEquals(3000, transfers.itemData().read(original).stack().amount());
        FluidTank target = new FluidTank(250, source.context());
        var drained = transfers.tryEmptyContainer(original, target, Long.MAX_VALUE, FluidAction.EXECUTE);
        assertEquals(250, drained.moved().amount()); assertEquals(2750, transfers.itemData().read(drained.replacement()).stack().amount());
        assertEquals(3000, transfers.itemData().read(original).stack().amount());
    }
    @Test void detachedFullBucketFailureRollsBackBothDirections() {
        var transfers = new ItemContainerTransfers(registry, item -> null);
        StorageContext context = StorageContext.confinedToCurrentThread();
        FluidTank source = new FluidTank(2000, context); source.restore(FluidStack.of(WATER, 500));
        assertEquals(ItemContainerTransferResult.Status.NO_TRANSFER, transfers.tryFillContainer(new DataStack(Material.BUCKET, 1), source, 1000, FluidAction.EXECUTE).status());
        assertEquals(500, source.content(0).amount());
        FluidTank target = new FluidTank(500, context);
        assertEquals(ItemContainerTransferResult.Status.NO_TRANSFER, transfers.tryEmptyContainer(new DataStack(Material.WATER_BUCKET, 1), target, 1000, FluidAction.EXECUTE).status());
        assertTrue(target.content(0).isEmpty());
    }
    @Test void customProvidersReceiveIsolatedCopyAndUnregistrationInvalidatesTheirHandlers() {
        var containers = new ItemFluidContainerRegistry(registry, item -> null, item -> false);
        var implementation = dynamic(empty);
        Object owner = new Object();
        var registration = containers.registerProvider(owner, item -> { assertEquals(1, item.getAmount()); return implementation.resolve(item).orElseThrow(); });
        ItemStack original = new DataStack(Material.STONE, 8);
        ItemFluidContainer handler = containers.resolve(original).orElseThrow();
        assertEquals(99, handler.fill(FluidStack.of(WATER, 99), FluidAction.EXECUTE));
        assertFalse(original.hasItemMeta()); assertEquals(8, original.getAmount());
        registration.close(); assertTrue(containers.resolve(original).isEmpty());
        assertThrows(StorageAccessException.class, handler::item);
        containers.registerProvider(owner, item -> implementation.resolve(item).orElseThrow());
        ItemFluidContainer ownerHandler = containers.resolve(original).orElseThrow();
        containers.unregisterOwner(owner); assertThrows(StorageAccessException.class, ownerHandler::content);
        containers.close(); assertThrows(IllegalStateException.class, () -> containers.resolve(original));
        assertThrows(IllegalStateException.class, () -> containers.registerProvider(owner, item -> null));
    }
    @Test void ceBucketWithoutSettingIsNotResolvedAsVanilla() {
        var containers = new ItemFluidContainerRegistry(registry, item -> null, item -> true);
        assertTrue(containers.resolve(new DataStack(Material.WATER_BUCKET, 1)).isEmpty());
    }
    @Test void registryShutdownAndWrongThreadInvalidateDetachedHandlers() throws Exception {
        var transfers = dynamic(empty);
        ItemFluidContainer handler = transfers.resolve(new DataStack(Material.GLASS_BOTTLE, 1)).orElseThrow();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(() -> { try { handler.content(); } catch (Throwable failure) { thrown.set(failure); } });
        thread.start(); thread.join(); assertInstanceOf(StorageAccessException.class, thrown.get());
        transfers.containers().close(); assertThrows(StorageAccessException.class, handler::content);
    }
    @Test void negativeDrainIsRejectedEvenForEmptyContainer() {
        ItemFluidContainer handler = dynamic(empty).resolve(new DataStack(Material.GLASS_BOTTLE, 1)).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> handler.drain(-1, FluidAction.SIMULATE));
        assertThrows(NullPointerException.class, () -> handler.drain(1, null));
    }
    @Test void configurationReloadInvalidatesResolvedCopiesWithoutLosingProviders() {
        var transfers = dynamic(empty);
        ItemStack item = new DataStack(Material.GLASS_BOTTLE, 1);
        ItemFluidContainer before = transfers.resolve(item).orElseThrow();
        transfers.containers().invalidate();
        assertThrows(StorageAccessException.class, before::content);
        assertTrue(transfers.resolve(item).orElseThrow().content().isEmpty());
    }
    @Test void customProviderCannotMutateItsCopyDuringSimulation() {
        var transfers = new ItemContainerTransfers(registry, item -> null);
        var delegateFactory = dynamic(empty);
        transfers.containers().registerProvider("extension", item -> faultyProvider(delegateFactory.resolve(item).orElseThrow(), true));
        FluidTank source = new FluidTank(4000, StorageContext.confinedToCurrentThread()); source.restore(FluidStack.of(WATER, 2000));
        ItemStack original = new DataStack(Material.GLASS_BOTTLE, 1);
        assertThrows(StorageAccessException.class, () -> transfers.tryFillContainer(original, source, 500, FluidAction.EXECUTE));
        assertEquals(2000, source.content(0).amount()); assertFalse(original.hasItemMeta());
        ItemFluidContainer handler = transfers.resolve(original).orElseThrow();
        assertThrows(StorageAccessException.class, () -> handler.fill(FluidStack.of(WATER, 500), FluidAction.SIMULATE));
        assertThrows(StorageAccessException.class, handler::item);
    }
    @Test void customProviderCannotDuplicateCountAndSourceRollsBackIfItTries() {
        var transfers = new ItemContainerTransfers(registry, item -> null);
        var delegateFactory = dynamic(empty);
        transfers.containers().registerProvider("extension", item -> faultyProvider(delegateFactory.resolve(item).orElseThrow(), false));
        FluidTank source = new FluidTank(4000, StorageContext.confinedToCurrentThread()); source.restore(FluidStack.of(WATER, 2000));
        ItemStack original = new DataStack(Material.GLASS_BOTTLE, 1);
        assertThrows(StorageAccessException.class, () -> transfers.tryFillContainer(original, source, 500, FluidAction.EXECUTE));
        assertEquals(2000, source.content(0).amount()); assertEquals(1, original.getAmount()); assertFalse(original.hasItemMeta());
    }
    private static ItemFluidContainer faultyProvider(ItemFluidContainer delegate, boolean mutateSimulation) {
        return new ItemFluidContainer() {
            private boolean duplicate;
            @Override public ItemStack item() { ItemStack item = delegate.item(); if (duplicate) item.setAmount(2); return item; }
            @Override public FluidStack content() { return delegate.content(); }
            @Override public long capacity() { return delegate.capacity(); }
            @Override public boolean accepts(FluidVariant variant) { return delegate.accepts(variant); }
            @Override public ItemFluidReadResult readResult() { return delegate.readResult(); }
            @Override public long fill(FluidStack offered, FluidAction action) {
                long moved = delegate.fill(offered, mutateSimulation ? FluidAction.EXECUTE : action);
                if (!mutateSimulation && action == FluidAction.EXECUTE) duplicate = true;
                return moved;
            }
            @Override public FluidStack drain(FluidVariant variant, long maximum, FluidAction action) { return delegate.drain(variant, maximum, action); }
        };
    }
}
