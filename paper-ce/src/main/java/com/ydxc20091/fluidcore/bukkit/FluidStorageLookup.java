/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.ce.BlockProviderResolver;
import com.ydxc20091.fluidcore.core.MultiTankStorage;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Owner-confined discovery for blocks, entities, furniture and detached item containers. */
public final class FluidStorageLookup implements AutoCloseable {
    @FunctionalInterface public interface BlockProvider {
        Optional<FluidStorage> resolve(Location location, BlockFace side);
    }
    @FunctionalInterface public interface EntityProvider {
        Optional<FluidStorage> resolve(Entity entity, BlockFace side);
    }
    @FunctionalInterface public interface FurnitureProvider {
        Optional<FluidStorage> resolve(FurnitureTarget furniture, BlockFace side);
    }
    public interface Registration extends AutoCloseable { @Override void close(); }

    /** A current furniture instance: validity must also reject a move away from this location. */
    public static final class FurnitureTarget {
        private final UUID id;
        private final Location position;
        private final BooleanSupplier valid;
        public FurnitureTarget(UUID id, Location position, BooleanSupplier valid) {
            this.id = Objects.requireNonNull(id, "id");
            this.position = position(position);
            this.valid = Objects.requireNonNull(valid, "valid");
        }
        public UUID id() { return id; }
        public Location location() { return position.clone(); }
    }

    /** Contains no world, inventory, entity or storage objects and may cross an async boundary. */
    public record Snapshot(List<FluidStack> contents, List<Long> capacities, long generation) {
        public Snapshot { contents = List.copyOf(contents); capacities = List.copyOf(capacities); }
    }

    interface ContextFactory {
        StorageContext block(Location position, BooleanSupplier valid);
        StorageContext entity(Entity entity, BooleanSupplier valid);
    }
    private static final ContextFactory BUKKIT = new ContextFactory() {
        public StorageContext block(Location position, BooleanSupplier valid) {
            return BukkitStorageContext.block(position, valid);
        }
        public StorageContext entity(Entity entity, BooleanSupplier valid) {
            StorageContext owner = BukkitStorageContext.entity(entity);
            return new StorageContext() {
                public void checkAccess() { owner.checkAccess(); if (!valid.getAsBoolean()) throw unavailable(); }
                public long tick() { checkAccess(); return owner.tick(); }
            };
        }
    };

    private final Plugin plugin;
    private final Function<Location, Optional<FluidStorage>> blocks;
    private final ItemFluidContainerRegistry items;
    private final BooleanSupplier running;
    private final LongSupplier generation;
    private final ContextFactory contexts;
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CopyOnWriteArrayList<Entry<BlockProvider>> blockProviders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Entry<EntityProvider>> entityProviders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Entry<FurnitureProvider>> furnitureProviders = new CopyOnWriteArrayList<>();
    private final Set<ItemRegistration> itemRegistrations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> scheduled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Both keys and values are weak: queried chunks are not kept alive by this facade.
    private final Map<org.bukkit.block.Block, java.lang.ref.WeakReference<FluidStorage>> cauldrons = Collections.synchronizedMap(new WeakHashMap<>());

    public FluidStorageLookup(Plugin plugin, BlockProviderResolver blocks, ItemFluidContainerRegistry items,
                              BooleanSupplier running, LongSupplier generation) {
        this(plugin, Objects.requireNonNull(blocks)::resolve, items, running, generation, BUKKIT);
    }

    FluidStorageLookup(Plugin plugin, Function<Location, Optional<FluidStorage>> blocks,
                       ItemFluidContainerRegistry items, BooleanSupplier running,
                       LongSupplier generation, ContextFactory contexts) {
        this.plugin = Objects.requireNonNull(plugin); this.blocks = Objects.requireNonNull(blocks);
        this.items = Objects.requireNonNull(items); this.running = Objects.requireNonNull(running);
        this.generation = Objects.requireNonNull(generation); this.contexts = Objects.requireNonNull(contexts);
    }

    public Registration registerBlock(Plugin owner, BlockProvider provider) { return register(owner, provider, blockProviders); }
    public Registration registerEntity(Plugin owner, EntityProvider provider) { return register(owner, provider, entityProviders); }
    public Registration registerFurniture(Plugin owner, FurnitureProvider provider) { return register(owner, provider, furnitureProviders); }

    /** Reuses the existing item registry; returned containers are copies, not live inventory slots. */
    public Registration registerItem(Plugin owner, ItemFluidContainerRegistry.Provider provider) {
        checkOwner(owner); Objects.requireNonNull(provider);
        AtomicBoolean active = new AtomicBoolean(true);
        var registered = items.registerProvider(owner, one -> {
            if (!active.get() || !owner.isEnabled()) return null;
            ItemFluidContainer resolved = provider.resolve(one);
            return resolved == null ? null : new ContainerGuard(resolved, () -> {
                check();
                if (!active.get() || !owner.isEnabled()) throw unavailable();
            });
        });
        ItemRegistration result = new ItemRegistration(owner, active, registered);
        itemRegistrations.add(result);
        if (closed.get()) result.close();
        return result;
    }

    /** Null side means an unsided view. Providers must not expose the same backing tank twice. */
    public Optional<FluidStorage> block(Location location) { return block(location, null); }
    public Optional<FluidStorage> block(Location location, BlockFace side) {
        check(); Location position = position(location);
        StorageContext target = contexts.block(position, this::available); target.checkAccess();
        List<FluidStorage> found = new ArrayList<>();
        var legacy = Objects.requireNonNull(blocks.apply(position.clone()));
        legacy.ifPresent(storage -> found.add(guard(storage, target, () -> true)));
        for (var entry : blockProviders) {
            if (!entry.live()) continue;
            var resolved = Objects.requireNonNull(entry.provider.resolve(position.clone(), side), "Provider returned null Optional");
            resolved.ifPresent(storage -> add(found, storage, target, entry::live));
        }
        if (found.isEmpty()) {
            var block = position.getBlock();
            FluidStorage cached;
            synchronized (cauldrons) {
                var reference = cauldrons.get(block); cached = reference == null ? null : reference.get();
            }
            // Resolve the material again before using an adapter retained by another caller.
            var vanilla = VanillaCauldronStorage.resolve(block, target);
            if (vanilla.isPresent()) {
                if (cached != null) vanilla = Optional.of(cached);
                else synchronized (cauldrons) { cauldrons.put(block, new java.lang.ref.WeakReference<>(vanilla.get())); }
            }
            vanilla.ifPresent(storage -> found.add(guard(storage, target, () -> true)));
        }
        return combine(found);
    }

    public Optional<FluidStorage> entity(Entity entity) { return entity(entity, null); }
    public Optional<FluidStorage> entity(Entity entity, BlockFace side) {
        check(); Objects.requireNonNull(entity);
        StorageContext target = contexts.entity(entity, this::available); target.checkAccess();
        List<FluidStorage> found = new ArrayList<>();
        for (var entry : entityProviders) {
            if (!entry.live()) continue;
            var resolved = Objects.requireNonNull(entry.provider.resolve(entity, side), "Provider returned null Optional");
            resolved.ifPresent(storage -> add(found, storage, target, entry::live));
        }
        return combine(found);
    }

    public Optional<FluidStorage> furniture(FurnitureTarget furniture) { return furniture(furniture, null); }
    public Optional<FluidStorage> furniture(FurnitureTarget furniture, BlockFace side) {
        check(); Objects.requireNonNull(furniture);
        StorageContext target = contexts.block(furniture.location(), () -> available() && furniture.valid.getAsBoolean());
        target.checkAccess();
        List<FluidStorage> found = new ArrayList<>();
        for (var entry : furnitureProviders) {
            if (!entry.live()) continue;
            var resolved = Objects.requireNonNull(entry.provider.resolve(furniture, side), "Provider returned null Optional");
            resolved.ifPresent(storage -> add(found, storage, target, entry::live));
        }
        return combine(found);
    }

    /** Inspect a caller-owned item; execution still requires ItemContainerTransfers and a real slot. */
    public Optional<ItemFluidContainer> item(ItemStack item, StorageContext owner) {
        check(); Objects.requireNonNull(owner).checkAccess();
        if (item == null || item.getType() == org.bukkit.Material.AIR || item.getType() == org.bukkit.Material.CAVE_AIR
                || item.getType() == org.bukkit.Material.VOID_AIR) return Optional.empty();
        long configured = generation.getAsLong(), lookup = epoch.get();
        return items.resolve(item).map(container -> new ContainerGuard(container, () -> {
            check(); owner.checkAccess();
            if (configured != generation.getAsLong() || lookup != epoch.get()) throw unavailable();
        }));
    }
    public Optional<ItemFluidContainer> item(ItemSlotAccess slot) {
        Objects.requireNonNull(slot); return item(slot.item(), slot.context());
    }

    public CompletableFuture<Optional<Snapshot>> queryBlockScheduled(Location location, BlockFace side) {
        Location position = position(location);
        return track(BukkitStorageContext.schedule(plugin, position, () -> block(position, side).map(this::snapshot)));
    }
    public CompletableFuture<Optional<Snapshot>> queryFurnitureScheduled(FurnitureTarget furniture, BlockFace side) {
        Objects.requireNonNull(furniture);
        return track(BukkitStorageContext.schedule(plugin, furniture.location(), () -> furniture(furniture, side).map(this::snapshot)));
    }
    public CompletableFuture<Optional<Snapshot>> queryEntityScheduled(Entity entity, BlockFace side) {
        Objects.requireNonNull(entity);
        CompletableFuture<Optional<Snapshot>> result = track(new CompletableFuture<>());
        if (!available()) return CompletableFuture.failedFuture(unavailable());
        try {
            if (entity.getScheduler().run(plugin, task -> {
                if (result.isCancelled()) return;
                try { result.complete(entity(entity, side).map(this::snapshot)); }
                catch (Throwable failure) { result.completeExceptionally(failure); }
            }, () -> result.completeExceptionally(unavailable())) == null) result.completeExceptionally(unavailable());
        } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }

    public void unregisterOwner(Plugin owner) {
        Objects.requireNonNull(owner);
        removeOwner(blockProviders, owner); removeOwner(entityProviders, owner); removeOwner(furnitureProviders, owner);
        List.copyOf(itemRegistrations).stream().filter(entry -> entry.owner == owner).forEach(Registration::close);
        items.unregisterOwner(owner);
    }
    /** Invalidates acquired views while keeping provider registrations in place. */
    public void invalidate() { epoch.incrementAndGet(); cauldrons.clear(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        invalidate();
        retire(blockProviders); retire(entityProviders); retire(furnitureProviders);
        List.copyOf(itemRegistrations).forEach(Registration::close);
        scheduled.forEach(future -> future.cancel(false)); scheduled.clear();
    }

    private Snapshot snapshot(FluidStorage storage) {
        List<FluidStack> content = new ArrayList<>(); List<Long> capacities = new ArrayList<>();
        for (int i = 0, size = storage.tanks(); i < size; i++) { content.add(storage.content(i)); capacities.add(storage.capacity(i)); }
        return new Snapshot(content, capacities, generation.getAsLong());
    }
    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        scheduled.add(future); future.whenComplete((value, failure) -> scheduled.remove(future));
        if (!available()) future.cancel(false);
        return future;
    }
    private boolean available() { return !closed.get() && running.getAsBoolean() && plugin.isEnabled(); }
    private void check() { if (!available()) throw unavailable(); }
    private void checkOwner(Plugin owner) { check(); if (!Objects.requireNonNull(owner).isEnabled()) throw new IllegalArgumentException("Provider owner must be enabled"); }
    private static StorageAccessException unavailable() { return new StorageAccessException("Fluid lookup, target or provider is no longer available; resolve again"); }
    private static Location position(Location value) { Location position = Objects.requireNonNull(value).clone(); Objects.requireNonNull(position.getWorld(), "world"); return position; }
    private <P> Registration register(Plugin owner, P provider, CopyOnWriteArrayList<Entry<P>> list) {
        checkOwner(owner); Entry<P> entry = new Entry<>(owner, Objects.requireNonNull(provider)); list.add(entry);
        if (closed.get()) { entry.active.set(false); list.remove(entry); throw unavailable(); }
        return () -> { entry.active.set(false); list.remove(entry); };
    }
    private static <P> void removeOwner(CopyOnWriteArrayList<Entry<P>> entries, Plugin owner) {
        entries.removeIf(entry -> { if (entry.owner != owner) return false; entry.active.set(false); return true; });
    }
    private static <P> void retire(CopyOnWriteArrayList<Entry<P>> entries) { entries.forEach(entry -> entry.active.set(false)); entries.clear(); }
    private void add(List<FluidStorage> found, FluidStorage storage, StorageContext target, BooleanSupplier active) {
        // Identity duplicates are not independent capacity and must never be counted twice.
        for (FluidStorage previous : found) if (previous instanceof GuardedStorage guarded && guarded.delegate == storage) return;
        found.add(guard(storage, target, active));
    }
    private static Optional<FluidStorage> combine(List<FluidStorage> found) {
        return found.isEmpty() ? Optional.empty() : Optional.of(found.size() == 1 ? found.getFirst() : new MultiTankStorage(found));
    }
    private FluidStorage guard(FluidStorage storage, StorageContext target, BooleanSupplier active) {
        return new GuardedStorage(Objects.requireNonNull(storage), target, active);
    }
    private static final class Entry<P> {
        final Plugin owner; final P provider; final AtomicBoolean active = new AtomicBoolean(true);
        Entry(Plugin owner, P provider) { this.owner = owner; this.provider = provider; }
        boolean live() { return active.get() && owner.isEnabled(); }
    }
    private final class ItemRegistration implements Registration {
        final Plugin owner;
        final AtomicBoolean active;
        final ItemFluidContainerRegistry.Registration registered;
        ItemRegistration(Plugin owner, AtomicBoolean active, ItemFluidContainerRegistry.Registration registered) {
            this.owner = owner; this.active = active; this.registered = registered;
        }
        public void close() { active.set(false); registered.close(); itemRegistrations.remove(this); }
    }

    private final class GuardedStorage implements FluidStorage {
        final FluidStorage delegate;
        final StorageContext target, delegated;
        final BooleanSupplier active;
        final long configured = generation.getAsLong(), lookup = epoch.get();
        final StorageContext context = new StorageContext() {
            public void checkAccess() { checkCurrent(); }
            public long tick() { checkCurrent(); return target.tick(); }
        };
        final TransactionParticipant lease = new TransactionParticipant() {
            public Object snapshot() { checkCurrent(); return target.tick(); }
            public void restore(Object ignored) { /* Delegate participants restore their own state. */ }
            public void validate() { checkCurrent(); }
            public void validateSnapshot(Object snapshot) { checkCurrent(); if ((long) snapshot != target.tick()) throw new StorageAccessException("Lookup transaction crossed a tick"); }
            public void afterCommit() { }
        };
        GuardedStorage(FluidStorage delegate, StorageContext target, BooleanSupplier active) {
            this.delegate = delegate; this.target = target; this.active = active;
            this.delegated = delegate.context(); checkCurrent();
        }
        private void checkCurrent() {
            check(); target.checkAccess();
            if (!active.getAsBoolean() || configured != generation.getAsLong() || lookup != epoch.get()) throw unavailable();
            delegated.checkAccess();
        }
        public StorageContext context() { return context; }
        public boolean supportsTransactions() { checkCurrent(); return delegate.supportsTransactions(); }
        public int tanks() { checkCurrent(); return delegate.tanks(); }
        public FluidStack content(int tank) { checkCurrent(); return delegate.content(tank); }
        public long capacity(int tank) { checkCurrent(); return delegate.capacity(tank); }
        public boolean isFluidValid(int tank, FluidVariant variant) { checkCurrent(); return delegate.isFluidValid(tank, variant); }
        public long insert(FluidVariant variant, long amount, FluidTransaction transaction) { checkCurrent(); transaction.enlist(lease); return delegate.insert(variant, amount, transaction); }
        public long extract(FluidVariant variant, long amount, FluidTransaction transaction) { checkCurrent(); transaction.enlist(lease); return delegate.extract(variant, amount, transaction); }
    }

    private static final class ContainerGuard implements ItemFluidContainer {
        private final ItemFluidContainer delegate;
        private final Runnable check;
        ContainerGuard(ItemFluidContainer delegate, Runnable check) { this.delegate = delegate; this.check = check; }
        public ItemStack item() { check.run(); return delegate.item(); }
        public FluidStack content() { check.run(); return delegate.content(); }
        public long capacity() { check.run(); return delegate.capacity(); }
        public boolean accepts(FluidVariant variant) { check.run(); return delegate.accepts(variant); }
        public ItemFluidReadResult readResult() { check.run(); return delegate.readResult(); }
        public long fill(FluidStack offered, FluidAction action) { check.run(); return delegate.fill(offered, action); }
        public FluidStack drain(FluidVariant variant, long maximum, FluidAction action) { check.run(); return delegate.drain(variant, maximum, action); }
    }
}
