/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.bukkit.*;
import com.ydxc20091.fluidcore.core.*;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Passive storage: deliberately inherits the null ticker implementations from CE. */
public final class FluidTankController extends BlockEntityController {
    static final String DATA_KEY = "fluidcore:tank";
    private final CraftEngineBridge bridge;
    private final FluidTankBehavior behavior;
    private final FluidStackCodec codec;
    private final StorageContext context;
    private FluidTank tank;
    private volatile byte[] savedData;
    private volatile Tag malformedTag;
    private long registryGeneration = -1;
    private boolean active;
    private boolean protectedData;
    private FluidStack deferredContent = FluidStack.EMPTY;
    private FluidVariant validatedVariant;
    private long validatedRegistryGeneration = -1;

    FluidTankController(BlockEntity entity, FluidTankBehavior behavior, CraftEngineBridge bridge) {
        super(entity);
        this.behavior = behavior;
        this.bridge = bridge;
        codec = new FluidStackCodec(bridge.registry());
        savedData = codec.encode(FluidStack.EMPTY);
        context = new BukkitStorageContext(
                () -> blockEntity.world != null && Bukkit.isOwnedByCurrentRegion(location()),
                () -> bridge.running() && active && blockEntity.isValid());
    }

    public Location location() {
        if (blockEntity.world == null) throw new StorageAccessException("CE block entity has no world");
        return new Location((World) blockEntity.world.world().platformWorld(), blockEntity.pos.x(), blockEntity.pos.y(), blockEntity.pos.z());
    }

    private void ensureTank() {
        context.checkAccess();
        if (malformedTag == null && registryGeneration != bridge.registry().snapshot().generation()) {
            if (FluidTransaction.hasOpenTransaction()) throw new StorageAccessException("Resolve refreshed tank state before opening a transaction");
            read(savedData);
        }
        if (tank == null) {
            tank = new FluidTank(behavior.capacity(), context, behavior::accepts, this::committed);
            tank.restore(deferredContent);
        }
    }

    public FluidStorage storage() {
        ensureTank();
        long generation = bridge.generation();
        return new FluidStorage() {
            private final TransactionParticipant guard = new TransactionParticipant() {
                @Override public Object snapshot() { check(); return new GuardSnapshot(bridge.generation(), bridge.registry().snapshot().generation()); }
                @Override public void restore(Object snapshot) {}
                @Override public void validate() { check(); }
                @Override public void afterCommit() {}
                @Override public void validateSnapshot(Object snapshot) {
                    GuardSnapshot captured = (GuardSnapshot) snapshot;
                    check();
                    if (captured.bridgeGeneration != bridge.generation() || captured.registryGeneration != bridge.registry().snapshot().generation())
                        throw new StorageAccessException("Reload changed the tank registry during this transaction");
                }
            };
            private void check() {
                context.checkAccess();
                if (generation != bridge.generation()) throw new StorageAccessException("CE reload invalidated this provider; resolve it again");
                ensureTank();
            }
            @Override public StorageContext context() {
                return new StorageContext() {
                    @Override public void checkAccess() { check(); }
                    @Override public long tick() { check(); return context.tick(); }
                };
            }
            @Override public int tanks() { check(); return tank.tanks(); }
            @Override public FluidStack content(int index) { check(); return tank.content(index); }
            @Override public long capacity(int index) { check(); return tank.capacity(index); }
            @Override public long insert(FluidVariant variant, long amount, FluidTransaction transaction) {
                checkWritable();
                if (tank.content(0).amount() > behavior.capacity()) throw new StorageAccessException("Tank exceeds configured capacity; drain existing content first");
                FluidStack content = tank.content(0);
                if (!content.isEmpty() && !behavior.accepts(content.variant())) throw new StorageAccessException("Tank content no longer matches its filter; drain existing content first");
                validateVariant(variant);
                transaction.enlist(guard);
                return tank.insert(variant, amount, transaction);
            }
            @Override public long extract(FluidVariant variant, long amount, FluidTransaction transaction) {
                checkWritable(); transaction.enlist(guard); return tank.extract(variant, amount, transaction);
            }
            private void checkWritable() {
                check();
                if (protectedData) throw new StorageAccessException("Tank contains unknown or invalid preserved data");
            }
        };
    }

    private void validateVariant(FluidVariant variant) {
        long current = bridge.registry().snapshot().generation();
        if (current == validatedRegistryGeneration && variant.equals(validatedVariant)) return;
        if (!codec.decode(codec.encode(FluidStack.of(variant, 1))).usable())
            throw new StorageAccessException("Insert requires a registered fluid and valid component codecs");
        validatedVariant = variant;
        validatedRegistryGeneration = current;
    }

    private record GuardSnapshot(long bridgeGeneration, long registryGeneration) {}

    public boolean hasProtectedData() { context.checkAccess(); return protectedData; }
    public byte[] savedData() { return savedData.clone(); }

    private void committed() {
        savedData = codec.encode(tank.content(0));
        registryGeneration = bridge.registry().snapshot().generation();
        blockEntity.world.blockEntityChanged(blockEntity.pos);
        Bukkit.getPluginManager().callEvent(new FluidStorageCommitEvent(location(), tank.version()));
    }

    @Override public void saveCustomData(CompoundTag tag) {
        // CE may save from a worker: export the last committed immutable blob without touching live state.
        Tag malformed = malformedTag;
        if (malformed != null) tag.put(DATA_KEY, malformed.deepClone());
        else tag.putByteArray(DATA_KEY, savedData.clone());
    }

    @Override public void loadCustomData(CompoundTag tag) {
        byte[] bytes = tag.getByteArray(DATA_KEY);
        if (bytes != null) read(bytes);
        else if (tag.containsKey(DATA_KEY)) {
            malformedTag = tag.get(DATA_KEY).deepClone();
            savedData = preserveMalformed(malformedTag);
            protectedData = true;
        }
    }

    @Override public void loadCustomDataFromItem(Item item) {
        if (item.platformItem() instanceof ItemStack stack && stack.hasItemMeta()) {
            var pdc = stack.getItemMeta().getPersistentDataContainer();
            byte[] bytes = pdc.has(ItemContainerTransfers.TANK_DATA_KEY, PersistentDataType.BYTE_ARRAY)
                    ? pdc.get(ItemContainerTransfers.TANK_DATA_KEY, PersistentDataType.BYTE_ARRAY) : null;
            if (bytes != null) read(bytes);
            else if (pdc.has(ItemContainerTransfers.TANK_DATA_KEY)) {
                try {
                    savedData = preserveMalformedPdc(pdc.serializeToBytes());
                } catch (IOException failure) { throw new UncheckedIOException("Cannot preserve malformed tank item data", failure); }
                malformedTag = null;
                registryGeneration = bridge.registry().snapshot().generation();
                protectedData = true;
                deferredContent = FluidStack.EMPTY;
            }
        }
    }

    private void read(byte[] bytes) {
        FluidReadResult decoded = codec.decode(bytes);
        malformedTag = null;
        registryGeneration = bridge.registry().snapshot().generation();
        savedData = bytes.clone();
        protectedData = !decoded.usable();
        deferredContent = decoded.stack();
        if (tank != null && active && !protectedData && !tank.content(0).equals(deferredContent)) tank.restore(deferredContent);
    }

    @Override public void onLoad() { active = true; }
    @Override public void onUnload() { active = false; }
    @Override public void onRemove() {
        if (blockEntity.world != null) bridge.preserveRemoval(location(), savedData);
        active = false;
    }

    private static byte[] preserveMalformed(Tag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(0x46434D4C); // Distinct archival envelope, deliberately never accepted as live fluid state.
            output.writeByte(tag.getId());
            tag.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private static byte[] preserveMalformedPdc(byte[] serialized) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(0x46435044); // FCPD: archival item PDC, never accepted as live fluid state.
            output.writeByte(1);
            output.writeInt(serialized.length);
            output.write(serialized);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
