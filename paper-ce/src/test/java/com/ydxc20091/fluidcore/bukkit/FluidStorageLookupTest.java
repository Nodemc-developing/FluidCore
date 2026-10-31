package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import com.ydxc20091.fluidcore.core.FluidTank;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class FluidStorageLookupTest {
    static final FluidVariant WATER = FluidVariant.of("minecraft:water");

    @Test void combinesIndependentExternalStoragesAndAvoidsDuplicateCapacity() {
        var fixture = new Fixture(); var first = new FluidTank(1000, fixture.owner); var second = new FluidTank(2000, fixture.owner);
        fixture.lookup.registerBlock(fixture.plugin, (position, side) -> Optional.of(first));
        fixture.lookup.registerBlock(fixture.plugin, (position, side) -> Optional.of(second));
        fixture.lookup.registerBlock(fixture.plugin, (position, side) -> Optional.of(first));
        var all = fixture.lookup.block(fixture.position, BlockFace.NORTH).orElseThrow();
        assertEquals(2, all.tanks()); assertEquals(3000, all.capacity(0) + all.capacity(1));
        assertEquals(2500, all.fill(FluidStack.of(WATER, 2500), FluidAction.SIMULATE));
        assertTrue(first.isEmpty()); assertTrue(second.isEmpty());
        assertEquals(2500, all.fill(FluidStack.of(WATER, 2500), FluidAction.EXECUTE));
        assertEquals(1000, first.amount()); assertEquals(1500, second.amount());
    }

    @Test void closingAProviderOrChangingConfigurationInvalidatesPreviouslyResolvedViews() {
        var fixture = new Fixture(); var tank = new FluidTank(1000, fixture.owner);
        var registration = fixture.lookup.registerBlock(fixture.plugin, (position, side) -> Optional.of(tank));
        var old = fixture.lookup.block(fixture.position).orElseThrow(); registration.close();
        assertThrows(StorageAccessException.class, old::tanks);
        fixture.lookup.registerBlock(fixture.plugin, (position, side) -> Optional.of(tank));
        var configured = fixture.lookup.block(fixture.position).orElseThrow(); fixture.generation.incrementAndGet();
        assertThrows(StorageAccessException.class, () -> configured.content(0));
        var fresh = fixture.lookup.block(fixture.position).orElseThrow(); fixture.lookup.invalidate();
        assertThrows(StorageAccessException.class, fresh::tanks);
        var current = fixture.lookup.block(fixture.position).orElseThrow(); fixture.lookup.close();
        assertThrows(StorageAccessException.class, current::tanks);
    }

    @Test void providerRetirementDuringTransactionRejectsCommitAndRollsBackResources() {
        var fixture = new Fixture(); var tank = new FluidTank(1000, fixture.owner);
        var registration = fixture.lookup.registerBlock(fixture.plugin, (position, side) -> Optional.of(tank));
        var storage = fixture.lookup.block(fixture.position).orElseThrow();
        try (var transaction = FluidTransaction.open()) {
            assertEquals(1000, storage.insert(WATER, 1000, transaction)); registration.close();
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertTrue(tank.isEmpty());
    }

    @Test void entityAndFurnitureProvidersRetainSideOwnershipAndLifecycle() throws Exception {
        var fixture = new Fixture(); var tank = new FluidTank(1000, fixture.owner);
        var calls = new AtomicInteger();
        fixture.lookup.registerEntity(fixture.plugin, (entity, side) -> { calls.incrementAndGet(); assertEquals(BlockFace.DOWN, side); return Optional.of(tank); });
        Entity entity = (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class}, (proxy, method, args) -> {
            throw new UnsupportedOperationException(method.getName());
        });
        var entityStorage = fixture.lookup.entity(entity, BlockFace.DOWN).orElseThrow(); assertEquals(1, calls.get());
        AtomicBoolean rejected = new AtomicBoolean(); Thread worker = new Thread(() -> {
            try { fixture.lookup.entity(entity, BlockFace.DOWN); } catch (StorageAccessException expected) { rejected.set(true); }
        }); worker.start(); worker.join(); assertTrue(rejected.get()); assertEquals(1, calls.get());
        AtomicBoolean present = new AtomicBoolean(true);
        var furniture = new FluidStorageLookup.FurnitureTarget(UUID.randomUUID(), fixture.position, present::get);
        fixture.lookup.registerFurniture(fixture.plugin, (target, side) -> Optional.of(tank));
        var furnitureStorage = fixture.lookup.furniture(furniture).orElseThrow();
        furniture.location().setX(400); assertEquals(fixture.position.getX(), furniture.location().getX());
        present.set(false); assertThrows(StorageAccessException.class, furnitureStorage::tanks);
        fixture.enabled.set(false); assertThrows(StorageAccessException.class, entityStorage::tanks);
    }

    @Test void unloadedTargetsAndForeignOwnedStorageAreRejectedBeforeResourceAccess() {
        var fixture = new Fixture(); var calls = new AtomicInteger();
        fixture.lookup.registerBlock(fixture.plugin, (position, side) -> { calls.incrementAndGet(); return Optional.of(new FluidTank(1000, fixture.owner)); });
        fixture.loaded.set(false); assertThrows(StorageAccessException.class, () -> fixture.lookup.block(fixture.position)); assertEquals(0, calls.get());
        fixture.loaded.set(true); var foreign = new ParticipantTestSupport.Owner(); foreign.available = false;
        var other = new Fixture(); other.lookup.registerBlock(other.plugin, (position, side) -> Optional.of(new FluidTank(1000, foreign)));
        assertThrows(StorageAccessException.class, () -> other.lookup.block(other.position));
    }

    @Test void itemLookupIsDetachedAndUsesExistingProviderClosureAndRealSlotOwnership() {
        var fixture = new Fixture(); var inventory = new ParticipantTestSupport.InventoryFixture();
        inventory.contents[0] = new ItemContainerTransfersTest.DataStack(Material.BUCKET, 1);
        var registration = fixture.lookup.registerItem(fixture.plugin, one -> new ItemFluidContainer() {
            FluidStack content = FluidStack.EMPTY;
            public ItemStack item() { return one.clone(); }
            public FluidStack content() { return content; }
            public long capacity() { return 1000; }
            public boolean accepts(FluidVariant fluid) { return WATER.equals(fluid); }
            public ItemFluidReadResult readResult() { return new ItemFluidReadResult(ItemFluidReadResult.Status.ABSENT, content, new byte[0], new byte[0], "", one); }
            public long fill(FluidStack stack, FluidAction action) { if (action.execute()) content = stack; return stack.amount(); }
            public FluidStack drain(FluidVariant fluid, long max, FluidAction action) { return FluidStack.EMPTY; }
        });
        var slot = new ItemSlotAccess(inventory.inventory, 0, inventory.owner);
        var detached = fixture.lookup.item(slot).orElseThrow();
        assertEquals(1000, detached.fill(FluidStack.of(WATER, 1000), FluidAction.EXECUTE));
        assertEquals(Material.BUCKET, inventory.contents[0].getType()); assertEquals(0, inventory.writes);
        registration.close(); assertThrows(StorageAccessException.class, detached::capacity);
    }

    static final class Fixture {
        final ParticipantTestSupport.Owner owner = new ParticipantTestSupport.Owner();
        final AtomicBoolean enabled = new AtomicBoolean(true), loaded = new AtomicBoolean(true);
        final AtomicLong generation = new AtomicLong();
        final Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "isEnabled" -> enabled.get();
            case "getName" -> "lookup-fixture";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
        final World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUID" -> UUID.fromString("77b72c25-5cb5-4d20-ac37-faa7d47467ee");
            case "isChunkLoaded" -> loaded.get();
            case "getName" -> "lookup-fixture";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        });
        final Location position = new Location(world, 1, 80, 1);
        final ItemFluidContainerRegistry containers = new ItemFluidContainerRegistry(new FluidRegistry(), item -> null, item -> false);
        final FluidStorageLookup lookup = new FluidStorageLookup(plugin, ignored -> Optional.empty(), containers, () -> true,
                generation::get, new FluidStorageLookup.ContextFactory() {
                    public StorageContext block(Location location, java.util.function.BooleanSupplier valid) { return guarded(valid); }
                    public StorageContext entity(Entity entity, java.util.function.BooleanSupplier valid) { return guarded(valid); }
                    private StorageContext guarded(java.util.function.BooleanSupplier valid) {
                        return new StorageContext() {
                            public void checkAccess() { owner.checkAccess(); if (!loaded.get() || !valid.getAsBoolean()) throw new StorageAccessException("Target unavailable"); }
                            public long tick() { checkAccess(); return owner.tick(); }
                        };
                    }
                });
    }
}
