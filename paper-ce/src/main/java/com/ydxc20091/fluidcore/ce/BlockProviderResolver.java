/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidStorage;
import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.api.FluidVariant;
import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.api.StorageContext;
import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.bukkit.BukkitStorageContext;
import com.ydxc20091.fluidcore.bukkit.BlockStorageProvider;
import com.ydxc20091.fluidcore.bukkit.BlockFluidSnapshot;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Resolves the current controller rather than retaining chunk or entity objects in a cache. */
public final class BlockProviderResolver {
    private final CraftEngineBridge bridge;
    private final CopyOnWriteArrayList<Registration> providers = new CopyOnWriteArrayList<>();
    BlockProviderResolver(CraftEngineBridge bridge) { this.bridge = bridge; }

    public Optional<FluidStorage> resolve(Location location) {
        var builtin = controller(location).map(FluidTankController::storage);
        if (builtin.isPresent() || !bridge.running()) return builtin;
        for (Registration registration : providers) {
            if (!registration.owner.isEnabled()) { providers.remove(registration); continue; }
            Optional<FluidStorage> storage = Objects.requireNonNull(registration.provider.resolve(location.clone()), "Provider returned null instead of Optional");
            if (storage.isPresent()) return Optional.of(guard(location, registration.owner, storage.get()));
        }
        return Optional.empty();
    }

    /** Built-in CE tanks have priority; external providers are queried in registration order. */
    public AutoCloseable register(Plugin owner, BlockStorageProvider provider) {
        if (!bridge.running()) throw new IllegalStateException("FluidCore is stopped");
        if (!owner.isEnabled()) throw new IllegalArgumentException("Provider owner must be enabled");
        Registration registration = new Registration(Objects.requireNonNull(owner), Objects.requireNonNull(provider));
        providers.add(registration);
        return () -> providers.remove(registration);
    }

    void unregisterOwner(Plugin owner) { providers.removeIf(registration -> registration.owner == owner); }
    void close() { providers.clear(); }

    /** The returned provider remains thread-confined. Prefer executeScheduled or querySnapshotScheduled. */

    public CompletableFuture<Optional<FluidStorage>> resolveScheduled(Location location) {
        return bridge.schedule(location, () -> resolve(location));
    }

    public <T> CompletableFuture<Optional<T>> executeScheduled(Location location, Function<FluidStorage, T> operation) {
        Objects.requireNonNull(operation);
        return bridge.schedule(location, () -> resolve(location).map(storage -> operation.apply(storage)));
    }

    public CompletableFuture<Optional<BlockFluidSnapshot>> querySnapshotScheduled(Location location) {
        Location position = location.clone();
        return executeScheduled(position, storage -> {
            var contents = new ArrayList<FluidStack>();
            var capacities = new ArrayList<Long>();
            for (int index = 0; index < storage.tanks(); index++) {
                contents.add(storage.content(index)); capacities.add(storage.capacity(index));
            }
            return new BlockFluidSnapshot(position.getWorld().getUID(), position.getBlockX(), position.getBlockY(), position.getBlockZ(),
                    contents, capacities, bridge.generation());
        });
    }

    private FluidStorage guard(Location location, Plugin owner, FluidStorage storage) {
        Location position = location.clone();
        long generation = bridge.generation();
        StorageContext context = BukkitStorageContext.block(position, () -> owner.isEnabled() && bridge.running()
                && generation == bridge.generation());
        StorageContext delegated = storage.context();
        context.checkSameContext(delegated);
        return new FluidStorage() {
            private void check() { context.checkSameContext(delegated); }
            @Override public StorageContext context() { return new StorageContext() {
                @Override public void checkAccess() { check(); }
                @Override public long tick() { check(); return context.tick(); }
            }; }
            @Override public boolean supportsTransactions() { check(); return storage.supportsTransactions(); }
            @Override public int tanks() { check(); return storage.tanks(); }
            @Override public FluidStack content(int index) { check(); return storage.content(index); }
            @Override public long capacity(int index) { check(); return storage.capacity(index); }
            @Override public long insert(FluidVariant fluid, long amount, FluidTransaction transaction) { check(); return storage.insert(fluid, amount, transaction); }
            @Override public long extract(FluidVariant fluid, long amount, FluidTransaction transaction) { check(); return storage.extract(fluid, amount, transaction); }
        };
    }

    private record Registration(Plugin owner, BlockStorageProvider provider) {}

    public Optional<FluidTankController> controller(Location location) {
        if (!Bukkit.isOwnedByCurrentRegion(location)) throw new StorageAccessException("Resolve a block provider on its owning region thread");
        if (!bridge.running()) return Optional.empty();
        if (location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return Optional.empty();
        BlockEntity entity = BukkitAdaptor.adapt(location.getWorld()).storageWorld()
                .getBlockEntityAtIfLoaded(new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        if (entity == null || !entity.isValid()) return Optional.empty();
        FluidTankController[] found = new FluidTankController[1];
        entity.controller.let(FluidTankController.class, controller -> {
            if (found[0] != null) throw new StorageAccessException("A CE block may contain only one fluidcore:tank behavior; use a multi-tank external provider for multiple tanks");
            found[0] = controller;
        });
        return Optional.ofNullable(found[0]);
    }
}
