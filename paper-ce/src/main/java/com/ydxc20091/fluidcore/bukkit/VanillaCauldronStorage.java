/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.*;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.util.VersionHelper;
import net.momirealms.craftengine.proxy.minecraft.world.level.BlockGetterProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelAccessorProxy;
import net.momirealms.craftengine.proxy.minecraft.world.level.LevelProxy;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Exact whole-bucket access to vanilla cauldrons. A vanilla water layer is a third of
 * a bucket, not the library's 250 mB bottle: partial layers are deliberately unsupported.
 */
public final class VanillaCauldronStorage implements FluidStorage, TransactionParticipant {
    public static final long BUCKET = 1000;
    private static final FluidVariant WATER = FluidVariant.of("minecraft:water");
    private static final FluidVariant LAVA = FluidVariant.of("minecraft:lava");
    private final Block block;
    private final StorageContext context;
    private final Function<Material, BlockData> dataFactory;
    private final Runnable physics;
    private BlockData expected;
    private BlockData rootOriginal;

    public static Optional<FluidStorage> resolve(Block block, StorageContext context) {
        Objects.requireNonNull(context).checkAccess();
        if (!representable(Objects.requireNonNull(block).getBlockData())) return Optional.empty();
        return Optional.of(new VanillaCauldronStorage(block, context, Material::createBlockData,
                () -> notifyNeighbors(block)));
    }

    private static void notifyNeighbors(Block block) {
        // Rewriting an unchanged BlockData does not reliably notify comparators. Use the
        // same explicit, version-adapted neighbour hooks CE uses after committed changes.
        Object level = BukkitAdaptor.adapt(block.getWorld()).minecraftWorld();
        Object position = LocationUtils.toBlockPos(block.getX(), block.getY(), block.getZ());
        Object owner = BlockStateUtils.getBlockOwner(BlockGetterProxy.INSTANCE.getBlockState(level, position));
        if (VersionHelper.isOrAbove1_21_5) LevelAccessorProxy.INSTANCE.updateNeighborsAt(level, position, owner);
        else LevelProxy.INSTANCE.updateNeighborsAt(level, position, owner);
        LevelProxy.INSTANCE.updateNeighbourForOutputSignal(level, position, owner);
    }

    VanillaCauldronStorage(Block block, StorageContext context, Function<Material, BlockData> dataFactory, Runnable physics) {
        this.block = Objects.requireNonNull(block); this.context = Objects.requireNonNull(context);
        this.dataFactory = Objects.requireNonNull(dataFactory); this.physics = Objects.requireNonNull(physics);
    }

    @Override public StorageContext context() { return context; }
    @Override public int tanks() { validate(); return 1; }
    @Override public long capacity(int tank) { index(tank); return BUCKET; }
    @Override public FluidStack content(int tank) {
        index(tank); Material material = block.getBlockData().getMaterial();
        return material == Material.CAULDRON ? FluidStack.EMPTY : FluidStack.of(material == Material.WATER_CAULDRON ? WATER : LAVA, BUCKET);
    }
    @Override public boolean isFluidValid(int tank, FluidVariant variant) {
        index(tank); return known(Objects.requireNonNull(variant));
    }
    @Override public long insert(FluidVariant variant, long amount, FluidTransaction transaction) {
        arguments(variant, amount, transaction);
        if (amount < BUCKET || !known(variant) || block.getBlockData().getMaterial() != Material.CAULDRON) return 0;
        transaction.enlist(this);
        BlockData filled = dataFactory.apply(variant.equals(WATER) ? Material.WATER_CAULDRON : Material.LAVA_CAULDRON);
        if (filled instanceof Levelled level) level.setLevel(level.getMaximumLevel());
        write(filled); return BUCKET;
    }
    @Override public long extract(FluidVariant variant, long amount, FluidTransaction transaction) {
        arguments(variant, amount, transaction);
        if (amount < BUCKET || !known(variant) || !content(0).equals(FluidStack.of(variant, BUCKET))) return 0;
        transaction.enlist(this); write(dataFactory.apply(Material.CAULDRON)); return BUCKET;
    }

    private void arguments(FluidVariant variant, long amount, FluidTransaction transaction) {
        validate(); Objects.requireNonNull(variant); Objects.requireNonNull(transaction).checkOpen();
        if (amount < 0) throw new IllegalArgumentException("Negative amount");
    }
    private void index(int tank) { validate(); if (tank != 0) throw new IndexOutOfBoundsException(tank); }
    private static boolean known(FluidVariant fluid) { return WATER.equals(fluid) || LAVA.equals(fluid); }
    private static boolean representable(BlockData state) {
        return state.getMaterial() == Material.CAULDRON || state.getMaterial() == Material.LAVA_CAULDRON
                || state.getMaterial() == Material.WATER_CAULDRON && state instanceof Levelled level && level.getLevel() == level.getMaximumLevel();
    }
    private static boolean same(BlockData first, BlockData second) { return first.getAsString().equals(second.getAsString()); }
    private void write(BlockData state) {
        // Remember the intended state before a platform write, allowing partial failures to roll back.
        expected = state.clone(); block.setBlockData(state.clone(), false); validate();
    }
    @Override public Object snapshot() {
        validate(); BlockData current = block.getBlockData().clone(); boolean first = expected == null;
        if (first) rootOriginal = current.clone(); expected = current.clone();
        return new Saved(current, context.tick(), first);
    }
    @Override public void restore(Object value) {
        context.checkAccess(); Saved saved = (Saved) value;
        // An external write is not owned by this transaction and must not be overwritten.
        if (expected != null && same(expected, block.getBlockData())) block.setBlockData(saved.state.clone(), false);
        expected = saved.first ? null : saved.state.clone();
        if (saved.first) rootOriginal = null;
    }
    @Override public void validate() {
        context.checkAccess(); BlockData state = block.getBlockData();
        if (!representable(state)) throw new StorageAccessException("Cauldron was removed or has fractional water layers; whole buckets only");
        if (expected != null && !same(expected, state)) throw new StorageAccessException("Cauldron changed during the fluid transaction");
    }
    @Override public void validateSnapshot(Object value) {
        validate(); if (((Saved) value).tick != context.tick()) throw new StorageAccessException("Cauldron transaction crossed a tick");
    }
    @Override public void afterCommit() {
        validate(); boolean changed = rootOriginal != null && expected != null && !same(rootOriginal, expected);
        expected = null; rootOriginal = null;
        // No physics or externally visible callbacks are invoked for simulations or rollbacks.
        if (changed) physics.run();
    }
    private record Saved(BlockData state, long tick, boolean first) { }
}
