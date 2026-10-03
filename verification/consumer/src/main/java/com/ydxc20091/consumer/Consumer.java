/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.consumer;

import com.ydxc20091.fluidcore.BukkitFluidCoreService;
import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.core.FluidTank;
import com.ydxc20091.fluidcore.core.FluidTransfers;
import com.ydxc20091.fluidcore.core.FluidDefinition;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import com.ydxc20091.fluidcore.core.FluidIngredient;
import com.ydxc20091.fluidcore.bukkit.ItemFluidContainer;
import com.ydxc20091.fluidcore.bukkit.ItemFluidReadResult;
import com.ydxc20091.fluidcore.bukkit.ItemContainerTransferResult;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import java.util.List;
import java.util.Map;

/** Compiles against locally published coordinates, without a project dependency or CE types. */
public final class Consumer {
    public static FluidStorage resolve(BukkitFluidCoreService service, Location location) {
        return service.bridge().resolver().resolve(location).orElse(null);
    }
    public static long transfer() {
        StorageContext owner = StorageContext.confinedToCurrentThread();
        FluidVariant water = FluidVariant.of("minecraft:water");
        FluidTank source = new FluidTank(8000, owner);
        FluidTank destination = new FluidTank(8000, owner);
        source.fill(FluidStack.of(water, 2000), FluidAction.EXECUTE);
        return FluidTransfers.move(source, destination, water, 1000, FluidAction.EXECUTE).amount();
    }

    public static ItemContainerTransferResult item(BukkitFluidCoreService service, ItemStack item, FluidStorage source) {
        ItemFluidContainer handler = service.containers().resolve(item).orElseThrow();
        ItemFluidReadResult record = service.itemData().read(item);
        if (record.protectedData()) throw new IllegalStateException(record.message());
        handler.fill(FluidStack.of(FluidVariant.of("minecraft:water"), 250), FluidAction.SIMULATE);
        return service.containerTransfers().tryFillContainer(item, source, 250, FluidAction.SIMULATE);
    }

    public static void standardCore() {
        FluidRegistry registry = new FluidRegistry();
        FluidProperties metadata = FluidProperties.builder().density(1040).viscosity(1200).temperature(300)
            .lightLevel(1).color(0xffe5a13b).texture("example:fluid/juice").rarity(FluidRarity.UNCOMMON)
            .bucketItem("example:juice_bucket").sound(FluidSound.CONTAINER_FILL, "minecraft:item.bucket.fill").build();
        FluidDefinition juice = FluidDefinition.builder("example:juice").displayName("Juice").properties(metadata).build();
        registry.register("example", juice);
        FluidKey tag = FluidKey.of("example:drinkable");
        registry.registerTag("example", tag, List.of(juice.key()));
        ComponentType<Integer> quality = new ComponentType<>(FluidKey.of("example:quality"), ComponentCodecs.INTEGER);
        registry.registerComponentCodec("example", quality);
        FluidVariant variant = FluidVariant.of(juice.key()).with(quality, 90);
        FluidStack stack = FluidStack.of(variant, 2000);
        if (stack.split(250).remainder().amount() != 1750 || variant.get(quality).orElseThrow() != 90)
            throw new IllegalStateException("Standard typed data operation failed");
        FluidIngredient ingredient = FluidIngredient.parseSized(Map.of("tag", tag.toString()), registry, 1000);
        if (!ingredient.matches(stack) || !registry.tagsOf(juice.key()).contains(tag))
            throw new IllegalStateException("Standard tag/ingredient operation failed");
        registry.replaceOwnedOverrides("configured", List.of(new FluidDefinition(juice.key(), "Configured juice", juice.tags(), metadata.toBuilder().temperature(350).build())));
        if (registry.find(juice.key()).orElseThrow().temperature() != 350 || registry.baseDefinition(juice.key()).orElseThrow().temperature() != 300)
            throw new IllegalStateException("Metadata overlay did not preserve the base");
        registry.unregisterOverrides("configured");
        if (registry.find(juice.key()).orElseThrow().temperature() != 300) throw new IllegalStateException("Overlay did not restore");
    }

    public static void main(String[] args) {
        standardCore();
        if (transfer() != 1000) throw new IllegalStateException("Native transfer failed");
        System.out.println("FLUIDCORE_MAVEN_CONSUMER PASS: public standard API, core, and Bukkit interface");
    }
}
