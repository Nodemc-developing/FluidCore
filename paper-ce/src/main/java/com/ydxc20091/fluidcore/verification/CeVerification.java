/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.verification;

import com.ydxc20091.fluidcore.FluidCorePlugin;
import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.bukkit.ItemContainerTransfers;
import com.ydxc20091.fluidcore.bukkit.ItemSlotAccess;
import com.ydxc20091.fluidcore.bukkit.ItemFluidReadResult;
import com.ydxc20091.fluidcore.bukkit.ItemFluidData;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.Arrays;

/** Explicitly gated destructive verification, restricted to the disposable test world. */
public final class CeVerification {
    private static final String WORLD = "fluidcore-verification";
    private CeVerification() {}

    private static World world(FluidCorePlugin plugin) {
        if (!plugin.getDataFolder().toPath().resolve("verification.enabled").toFile().exists())
            throw new IllegalStateException("Verification is disabled; use the isolated verification runner");
        World world = Bukkit.getWorld(WORLD);
        if (world == null) throw new IllegalStateException("Disposable verification world not found");
        return world;
    }

    public static CompletableFuture<String> run(FluidCorePlugin plugin, boolean persistedOnly) {
        World world = world(plugin);
        Location tankPosition = new Location(world, 0, 100, 0);
        CompletableFuture<String> result = new CompletableFuture<>();
        result.whenComplete((message, failure) -> {
            if (plugin.isEnabled()) Bukkit.getRegionScheduler().run(plugin, tankPosition,
                    task -> world.removePluginChunkTicket(0, 0, plugin));
        });
        world.getChunkAtAsync(0, 0).thenCompose(chunk -> plugin.bridge().schedule(tankPosition, () -> {
            world.addPluginChunkTicket(0, 0, plugin);
            if (persistedOnly) {
                var storage = plugin.storageAt(tankPosition).orElseThrow(() -> new IllegalStateException("Saved CE tank is missing"));
                require(storage.content(0).equals(FluidStack.of(FluidVariant.of("minecraft:water"), 3000)), "Disk persistence changed fluid");
                result.complete("FLUIDCORE_PERSISTENCE PASS: CE chunk save/restart/load preserved 3000 mB water");
                return null;
            }
            require(CraftEngineBlocks.byId(Key.of("fluidcoreexample:tank")) != null, "Example CE pack failed to load");
            verifyStandardApi(plugin);
            if (CraftEngineBlocks.isCustomBlock(world.getBlockAt(tankPosition))) CraftEngineBlocks.remove(world.getBlockAt(tankPosition), false);
            require(CraftEngineBlocks.place(tankPosition, Key.of("fluidcoreexample:tank"), true), "CE placement failed");
            var controller = plugin.bridge().resolver().controller(tankPosition).orElseThrow();
            var storage = controller.storage();
            var water = FluidVariant.of("minecraft:water");
            require(storage.fill(FluidStack.of(water, 4000), FluidAction.EXECUTE) == 4000, "CE fill failed");
            require(storage.drain(water, 1000, FluidAction.SIMULATE).amount() == 1000, "CE simulation failed");
            require(storage.content(0).amount() == 4000, "CE simulation mutated state");
            require(storage.drain(water, 1000, FluidAction.EXECUTE).amount() == 1000, "CE drain failed");
            var tag = new CompoundTag();
            controller.saveCustomData(tag);
            storage.drain(water, 1000, FluidAction.EXECUTE);
            controller.loadCustomData(tag);
            require(controller.storage().content(0).amount() == 3000, "CE controller save/load lost fluid");
            var entity = controller.blockEntity();
            require(controller.createBlockEntityTicker(entity.world, CraftEngineBlocks.getCustomBlockState(world.getBlockAt(tankPosition))) != null,
                    "Tank native sleeping ticker is missing");
            verifyRealItemStacks(plugin);
            com.ydxc20091.fluidcore.ce.verification.DropVerification.verify(plugin.bridge(), tankPosition);
            com.ydxc20091.fluidcore.ce.verification.DropVerification.verifyMalformedItemPlacement(plugin.bridge(), tankPosition.clone().add(4, 0, 0));
            Location heaterPosition = new Location(world, 2, 100, 0);
            if (CraftEngineBlocks.isCustomBlock(world.getBlockAt(heaterPosition))) CraftEngineBlocks.remove(world.getBlockAt(heaterPosition), false);
            require(CraftEngineBlocks.place(heaterPosition, Key.of("fluidcoreexample:heater"), true), "CE heater placement failed");
            Bukkit.getRegionScheduler().runDelayed(plugin, heaterPosition, task -> {
                try {
                    var passive = plugin.bridge().resolver().controller(tankPosition).orElseThrow();
                    var passiveEntity = passive.blockEntity();
                    var passiveTicker = passive.createBlockEntityTicker(passiveEntity.world,
                            CraftEngineBlocks.getCustomBlockState(world.getBlockAt(tankPosition)));
                    require(passiveTicker != null && passive.tickerSleeping(),
                            "Idle tank did not sleep after native ticks");
                    var expectedTankContent = FluidStack.of(water, 3000);
                    require(passive.storage().content(0).equals(expectedTankContent),
                            "Idle native tank changed its preserved content");
                    require(passive.storage().fill(FluidStack.of(water, 1), FluidAction.EXECUTE) == 1,
                            "Tank wake-up commit failed");
                    require(!passive.tickerSleeping(), "A successful tank commit did not wake its ticker");
                    require(passive.storage().drain(water, 1, FluidAction.EXECUTE).amount() == 1,
                            "Tank wake-up fixture did not restore its preserved content");
                    var heater = plugin.bridge().resolver().controller(heaterPosition).orElseThrow();
                    BlockEntityController[] ticking = new BlockEntityController[1];
                    heater.blockEntity().controller.let(BlockEntityController.class, candidate -> {
                        if (candidate.getClass().getName().endsWith(".HeatingController")) ticking[0] = candidate;
                    });
                    require(ticking[0] != null, "Example sleep controller missing");
                    long count = tickCount(ticking[0]);
                    require(sleeping(ticking[0]), "Empty heater did not sleep");
                    Bukkit.getRegionScheduler().runDelayed(plugin, heaterPosition, task2 -> {
                        try {
                            require(passive.tickerSleeping(), "Idle tank did not return to sleep after its commit");
                            require(passive.storage().content(0).equals(expectedTankContent),
                                    "Sleeping tank changed fluid after its commit");
                            require(tickCount(ticking[0]) == count, "Sleeping heater kept executing");
                            heater.storage().fill(FluidStack.of(water, 1000), FluidAction.EXECUTE);
                            Bukkit.getRegionScheduler().runDelayed(plugin, heaterPosition, task3 -> {
                                try {
                                    var content = heater.storage().content(0);
                                    require(content.amount() == 1000 && content.variant().fluid().toString().equals("fluidcoreexample:heated_water"),
                                            "Commit did not wake and process heater");
                                    require(sleeping(ticking[0]), "Processed heater did not return to sleep");
                                    com.ydxc20091.fluidcore.ce.verification.CrossRegionVerification
                                            .verifyCrossRegion(plugin.bridge(), plugin, world).whenComplete((ignored, failure) -> {
                                                if (failure != null) result.completeExceptionally(failure);
                                                else result.complete("FLUIDCORE_CE_VERIFY PASS: standard properties/tags/item API, placement, controller persistence, real ItemStack/PDC, bucket slots, loot/replacement, native idle tank sleep/commit wake, heater sleep/wake, region ownership");
                                            });
                                } catch (Throwable failure) { result.completeExceptionally(failure); }
                            }, 10);
                        } catch (Throwable failure) { result.completeExceptionally(failure); }
                    }, 10);
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            }, 10);
            return null;
        })).exceptionally(failure -> { result.completeExceptionally(failure); return null; });
        return result;
    }

    private static long tickCount(BlockEntityController controller) throws ReflectiveOperationException {
        return (long) controller.getClass().getMethod("tickCount").invoke(controller);
    }
    private static boolean sleeping(BlockEntityController controller) throws ReflectiveOperationException {
        return (boolean) controller.getClass().getMethod("sleeping").invoke(controller);
    }

    /** Verifies the public service on real CE items; every operation stays in this owning task. */
    private static void verifyStandardApi(FluidCorePlugin plugin) {
        var registry = plugin.registry();
        var waterKey = FluidKey.of("minecraft:water");
        require(registry.find(waterKey).orElseThrow().temperature() == 301
                && registry.baseDefinition(waterKey).orElseThrow().temperature() == 300,
                "Explicit CE metadata overlay changed its base definition or failed to publish");
        require(registry.find(waterKey).orElseThrow().bucketItem().orElseThrow().equals(FluidKey.of("minecraft:water_bucket")),
                "CE metadata overlay lost inherited bucket metadata");
        var heatedKey = FluidKey.of("fluidcoreexample:heated_water");
        var heated = registry.find(heatedKey).orElseThrow(() -> new IllegalStateException("Standard fluid config was not published"));
        require(heated.density() == 990 && heated.viscosity() == 550 && heated.temperature() == 350
                && heated.lightLevel() == 0 && heated.rarity() == FluidRarity.UNCOMMON, "Configured standard properties changed");
        require(heated.color().orElseThrow() == 0xff4aaeff, "Configured ARGB color changed");
        require(heated.texture().orElseThrow().equals("minecraft:block/water_still"), "Configured texture changed");
        require(heated.sound(FluidSound.CONTAINER_FILL).orElseThrow().equals(FluidKey.of("minecraft:item.bucket.fill")), "Configured fluid sound changed");
        require(registry.hasTag(heatedKey, FluidKey.of("fluidcoreexample:processed_drinks")), "Nested CE tag failed to resolve");
        require(registry.hasTag(FluidKey.of("minecraft:water"), FluidKey.of("fluidcoreexample:processed_drinks")), "CE tag lost an external fluid member");

        var definition = CraftEngineItems.byId(Key.of("fluidcoreexample:canteen"));
        require(definition != null, "CE canteen definition is missing");
        ItemStack canteen = definition.buildBukkitItem();
        var handler = plugin.containers().resolve(canteen).orElseThrow();
        var water = FluidVariant.of("minecraft:water");
        require(handler.capacity() == 4000 && handler.accepts(water) && !handler.accepts(FluidVariant.of("minecraft:lava")),
                "CE item tag filter did not reach the public handler");
        StorageContext owner = StorageContext.confinedToCurrentThread();
        var source = new com.ydxc20091.fluidcore.core.FluidTank(8000, owner);
        source.fill(FluidStack.of(water, 2000), FluidAction.EXECUTE);
        var transfers = plugin.containerTransfers();
        var simulated = transfers.tryFillContainer(canteen, source, 375, FluidAction.SIMULATE);
        require(simulated.success() && simulated.moved().amount() == 375 && source.content(0).amount() == 2000,
                "Detached container simulation mutated the source or lost partial transfer");
        require(plugin.itemData().read(canteen).status() == ItemFluidReadResult.Status.ABSENT,
                "Detached simulation wrote into the caller's CE item");
        var filled = transfers.tryFillContainer(canteen, source, 375, FluidAction.EXECUTE);
        require(filled.success() && source.content(0).amount() == 1625 && plugin.itemData().read(filled.replacement()).stack().amount() == 375,
                "Public partial container filling lost resources");
        require(plugin.itemData().read(canteen).status() == ItemFluidReadResult.Status.ABSENT,
                "Detached execution changed the input instead of returning a replacement");
        var emptied = transfers.tryEmptyContainer(filled.replacement(), source, 125, FluidAction.EXECUTE);
        require(emptied.success() && source.content(0).amount() == 1750 && plugin.itemData().read(emptied.replacement()).stack().amount() == 250,
                "Public partial container emptying lost resources");
        ItemStack exhausted = emptied.replacement();
        plugin.itemData().write(exhausted, FluidStack.EMPTY);
        require(!exhausted.getItemMeta().getPersistentDataContainer().has(ItemFluidData.DATA_KEY)
                && plugin.itemData().read(exhausted).status() == ItemFluidReadResult.Status.EMPTY,
                "Writing EMPTY did not remove the fluid record or preserve initialization");

        ItemStack corrupt = definition.buildBukkitItem();
        var meta = corrupt.getItemMeta();
        meta.getPersistentDataContainer().set(ItemFluidData.DATA_KEY, PersistentDataType.STRING, "original undecodable value");
        corrupt.setItemMeta(meta);
        require(plugin.itemData().read(corrupt).status() == ItemFluidReadResult.Status.WRONG_TYPE, "Wrong PDC type became empty");
        try {
            plugin.itemData().write(corrupt, FluidStack.EMPTY);
            throw new IllegalStateException("Protected public item write unexpectedly succeeded");
        } catch (ItemFluidData.ProtectedDataException expected) {
            require(expected.readResult().originalItem().getItemMeta().getPersistentDataContainer()
                    .get(ItemFluidData.DATA_KEY, PersistentDataType.STRING).equals("original undecodable value"),
                    "Rejected item write lost the diagnostic backup");
        }
        require(corrupt.getItemMeta().getPersistentDataContainer().get(ItemFluidData.DATA_KEY, PersistentDataType.STRING)
                .equals("original undecodable value"), "Rejected public item write changed the original data");
        plugin.getLogger().info("FLUIDCORE_STANDARD_API PASS: standard properties, nested tags, CE container filters, detached partial transfers, protected item data");
    }

    private static void verifyRealItemStacks(FluidCorePlugin plugin) {
        ItemStack[] items = new ItemStack[41];
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(), new Class<?>[]{PlayerInventory.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSize" -> 41;
                    case "getMaxStackSize" -> 64;
                    case "getContents" -> items.clone();
                    case "getStorageContents" -> Arrays.copyOf(items, 36);
                    case "getItem" -> items[(int) args[0]];
                    case "setItem" -> { items[(int) args[0]] = (ItemStack) args[1]; yield null; }
                    case "setContents" -> { System.arraycopy((ItemStack[]) args[0], 0, items, 0, 41); yield null; }
                    case "toString" -> "FluidCore verification slot inventory";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        StorageContext context = StorageContext.confinedToCurrentThread();
        var tank = new com.ydxc20091.fluidcore.core.FluidTank(4000, context);
        tank.fill(FluidStack.of(FluidVariant.of("minecraft:water"), 2000), FluidAction.EXECUTE);
        items[0] = new ItemStack(Material.BUCKET, 2);
        var slot = new ItemSlotAccess(inventory, 0, context);
        require(plugin.bridge().containers().transfer(slot, GameMode.SURVIVAL, tank, 1000) == ItemContainerTransfers.Result.SUCCESS,
                "Stacked bucket transfer failed");
        require(items[0].getAmount() == 1 && items[1].getType() == Material.WATER_BUCKET && tank.content(0).amount() == 1000,
                "Stacked bucket lost or duplicated resources");
        for (int index = 1; index < 36; index++) items[index] = new ItemStack(Material.STONE, 64);
        items[0] = new ItemStack(Material.BUCKET, 2);
        require(plugin.bridge().containers().transfer(slot, GameMode.SURVIVAL, tank, 1000) == ItemContainerTransfers.Result.NO_INVENTORY_SPACE,
                "A full inventory accepted a replacement");
        require(items[0].getAmount() == 2 && tank.content(0).amount() == 1000, "Full inventory failed to roll back");
        var metaItem = new ItemStack(Material.GLASS_BOTTLE);
        ItemContainerTransfers.writeData(metaItem, ItemContainerTransfers.DATA_KEY, new byte[]{1, 2, 3});
        require(Arrays.equals(ItemContainerTransfers.data(metaItem, ItemContainerTransfers.DATA_KEY), new byte[]{1, 2, 3}), "PDC round trip failed");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
