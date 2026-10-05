/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import com.ydxc20091.fluidcore.core.FluidStackCodec;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Predicate;

/** Per-service providers followed by CE setting resolution and vanilla buckets. No global registry. */
public final class ItemFluidContainerRegistry implements AutoCloseable {
    @FunctionalInterface public interface Provider {
        /** The supplied item is already an isolated count-one copy. Return null when unsupported. */
        ItemFluidContainer resolve(ItemStack oneItem);
    }
    public interface Registration extends AutoCloseable { @Override void close(); }
    private final FluidRegistry fluids;
    private final ItemFluidData data;
    private final FluidStackCodec codec;
    private final Function<ItemStack, ContainerDefinition> customDefinition;
    private final Predicate<ItemStack> customItem;
    private final CopyOnWriteArrayList<Entry> providers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();

    public ItemFluidContainerRegistry(FluidRegistry fluids, Function<ItemStack, ContainerDefinition> customDefinition,
                                      Predicate<ItemStack> customItem) {
        this.fluids = Objects.requireNonNull(fluids); this.data = new ItemFluidData(fluids);
        this.codec = new FluidStackCodec(fluids); this.customDefinition = Objects.requireNonNull(customDefinition);
        this.customItem = Objects.requireNonNull(customItem);
    }
    public ItemFluidData itemData() { return data; }
    /** Earlier registrations have priority. Closing the handle or unloading its owner removes it. */
    public Registration registerProvider(Object owner, Provider provider) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(provider, "provider");
        synchronized (providers) {
            if (closed.get()) throw new IllegalStateException("Item container registry is closed");
            Entry entry = new Entry(owner, provider); providers.add(entry);
            return () -> { entry.active.set(false); providers.remove(entry); };
        }
    }
    public void unregisterOwner(Object owner) { providers.removeIf(entry -> { if (!Objects.equals(entry.owner, owner)) return false; entry.active.set(false); return true; }); }
    /** Invalidates previously resolved copies after a configuration reload without removing providers. */
    public void invalidate() { generation.incrementAndGet(); }
    public void clear() { providers.forEach(entry -> entry.active.set(false)); providers.clear(); }
    @Override public void close() { synchronized (providers) { closed.set(true); clear(); } }

    /** Caller must own the original item. Provider inputs and all returned default handlers are copies. */
    public Optional<ItemFluidContainer> resolve(ItemStack input) {
        Objects.requireNonNull(input, "input");
        if (closed.get()) throw new IllegalStateException("Item container registry is closed");
        if (empty(input)) return Optional.empty();
        ItemStack one = input.clone(); one.setAmount(1);
        for (Entry entry : providers) {
            ItemFluidContainer handler = entry.provider.resolve(one.clone());
            if (handler == null) continue;
            if (handler.item().getAmount() != 1) throw new IllegalArgumentException("Item container provider must return a count-one item");
            ItemFluidReadResult record = data.read(one);
            return Optional.of(record.protectedData() ? new ProtectedContainer(one, record) : new ProviderContainer(handler, entry));
        }
        ContainerDefinition definition = customDefinition.apply(one);
        if (definition != null) return Optional.of(new DynamicContainer(one, definition));
        // A CE item whose material happens to be a bucket is never treated as a vanilla bucket.
        if (customItem.test(one)) return Optional.empty();
        FluidStack vanilla = vanillaContent(one);
        if (vanilla == null) return Optional.empty();
        var persistentData = one.getPersistentDataContainer();
        if (persistentData.has(ItemFluidData.DATA_KEY) || persistentData.has(ItemContainerTransfers.TANK_DATA_KEY)
                || persistentData.has(ItemFluidData.INITIALIZED_KEY) || ForeignFluidData.item(one)) {
            ItemFluidReadResult record = data.read(one);
            if (record.usable()) record = data.conflict(one, "Vanilla bucket carries a FluidCore container/tank record");
            return Optional.of(new ProtectedContainer(one, record));
        }
        return Optional.of(new VanillaContainer(one, vanilla));
    }
    private static final class Entry {
        final Object owner; final Provider provider; final AtomicBoolean active = new AtomicBoolean(true);
        Entry(Object owner, Provider provider) { this.owner = owner; this.provider = provider; }
    }
    private final class ProviderContainer implements ItemFluidContainer {
        private final ItemFluidContainer delegate; private final Entry entry; private final Thread owner = Thread.currentThread();
        private final long resolvedGeneration = generation.get();
        private boolean invalid;
        ProviderContainer(ItemFluidContainer delegate, Entry entry) { this.delegate = delegate; this.entry = entry; }
        private void check() {
            if (Thread.currentThread() != owner) throw new StorageAccessException("Detached item handler used on a different thread");
            if (closed.get() || !entry.active.get()) throw new StorageAccessException("Item container provider was unloaded");
            if (resolvedGeneration != generation.get()) throw new StorageAccessException("Item container configuration was reloaded");
            if (invalid) throw new StorageAccessException("Item container provider violated its operation contract");
        }
        @Override public ItemStack item() { check(); return checkedItem(); }
        @Override public FluidStack content() { check(); return delegate.content(); }
        @Override public long capacity() { check(); return delegate.capacity(); }
        @Override public boolean accepts(FluidVariant variant) { check(); return delegate.accepts(variant); }
        @Override public ItemFluidReadResult readResult() { check(); return delegate.readResult(); }
        @Override public long fill(FluidStack offered, FluidAction action) {
            check(); Objects.requireNonNull(offered); Objects.requireNonNull(action);
            try {
                ProviderState before = snapshot();
                long accepted = delegate.fill(offered, action);
                if (accepted < 0 || accepted > offered.amount()) throw contract("Invalid provider fill amount");
                ProviderState after = snapshot();
                if (action == FluidAction.SIMULATE) unchanged(before, after);
                else {
                    if (accepted > 0 && !before.content.isEmpty() && !before.content.variant().equals(offered.variant()))
                        throw contract("Provider filled a different existing fluid identity");
                    FluidStack expected = accepted == 0 ? before.content : FluidStack.of(offered.variant(), Math.addExact(before.content.amount(), accepted));
                    if (!expected.equals(after.content)) throw contract("Provider fill result does not match its contents");
                }
                return accepted;
            } catch (RuntimeException failure) { invalid = true; throw failure; }
        }
        @Override public FluidStack drain(FluidVariant variant, long maximum, FluidAction action) {
            check(); Objects.requireNonNull(variant); Objects.requireNonNull(action);
            if (maximum < 0) throw new IllegalArgumentException("Negative maximum");
            try {
                ProviderState before = snapshot();
                FluidStack removed = Objects.requireNonNull(delegate.drain(variant, maximum, action), "Provider returned null fluid");
                if (removed.amount() > maximum || removed.amount() > before.content.amount()
                        || !removed.isEmpty() && (!removed.variant().equals(variant) || !removed.variant().equals(before.content.variant())))
                    throw contract("Invalid provider drain identity or amount");
                ProviderState after = snapshot();
                if (action == FluidAction.SIMULATE) unchanged(before, after);
                else {
                    FluidStack expected = removed.isEmpty() ? before.content : FluidStack.of(variant, before.content.amount() - removed.amount());
                    if (!expected.equals(after.content)) throw contract("Provider drain result does not match its contents");
                }
                return removed;
            } catch (RuntimeException failure) { invalid = true; throw failure; }
        }
        private ItemStack checkedItem() {
            ItemStack actual = Objects.requireNonNull(delegate.item(), "Provider returned null item");
            if (actual.getAmount() != 1) throw contract("Provider returned an item whose count is not one");
            return actual.clone();
        }
        private ProviderState snapshot() { return new ProviderState(checkedItem(), Objects.requireNonNull(delegate.content(), "Provider returned null contents")); }
        private void unchanged(ProviderState before, ProviderState after) {
            if (!before.content.equals(after.content) || !before.item.equals(after.item)) throw contract("Provider mutated its item during simulation");
        }
        private StorageAccessException contract(String message) { invalid = true; return new StorageAccessException(message); }
        private record ProviderState(ItemStack item, FluidStack content) {}
    }
    private abstract class DetachedContainer implements ItemFluidContainer {
        protected ItemStack item;
        private final Thread owner = Thread.currentThread();
        private final long resolvedGeneration = generation.get();
        DetachedContainer(ItemStack item) { this.item = item.clone(); this.item.setAmount(1); }
        protected final void check() {
            if (Thread.currentThread() != owner) throw new StorageAccessException("Detached item handler used on a different thread");
            if (closed.get()) throw new StorageAccessException("Item container registry is closed");
            if (resolvedGeneration != generation.get()) throw new StorageAccessException("Item container configuration was reloaded");
        }
        @Override public ItemStack item() { check(); return item.clone(); }
        protected final void checkRequest(long maximum, FluidAction action) {
            check(); if (maximum < 0) throw new IllegalArgumentException("Negative maximum"); Objects.requireNonNull(action, "action");
        }
    }
    private final class DynamicContainer extends DetachedContainer {
        private final ContainerDefinition definition;
        private ItemFluidReadResult read;
        private FluidStack content;
        DynamicContainer(ItemStack item, ContainerDefinition definition) {
            super(item); this.definition = definition; read = data.read(item);
            if (read.status() == ItemFluidReadResult.Status.ABSENT) {
                content = definition.initialContent();
                var decoded = codec.decode(codec.encode(content));
                if (!decoded.usable()) read = new ItemFluidReadResult(
                        decoded.status() == com.ydxc20091.fluidcore.core.FluidReadResult.Status.UNKNOWN
                                ? ItemFluidReadResult.Status.UNKNOWN : ItemFluidReadResult.Status.INVALID,
                        content, decoded.raw(), new byte[0], decoded.message(), item);
                else if (!content.isEmpty() && !definition.accepts(content.variant(), fluids))
                    read = data.conflict(item, "Configured initial content is rejected by its container filter");
                else read = new ItemFluidReadResult(content.isEmpty() ? ItemFluidReadResult.Status.EMPTY : ItemFluidReadResult.Status.PRESENT,
                        content, decoded.raw(), new byte[0], "Configured initial container contents", item);
            } else content = read.stack();
        }
        @Override public FluidStack content() { check(); return content; }
        @Override public long capacity() { check(); return definition.capacity(); }
        @Override public ItemStack item() {
            check();
            if (!read.protectedData()) com.ydxc20091.fluidcore.ce.TankItemPresentation.apply(item, content, definition.capacity(), fluids, null);
            return super.item();
        }
        @Override public boolean accepts(FluidVariant variant) {
            check(); Objects.requireNonNull(variant);
            return definition.accepts(variant, fluids) && codec.decode(codec.encode(FluidStack.of(variant, 1))).usable();
        }
        @Override public ItemFluidReadResult readResult() { check(); return read; }
        @Override public long fill(FluidStack offered, FluidAction action) {
            Objects.requireNonNull(offered, "offered"); checkRequest(offered.amount(), action);
            if (read.protectedData() || offered.isEmpty() || !accepts(offered.variant())
                    || !content.isEmpty() && !content.variant().equals(offered.variant()) || content.amount() >= definition.capacity()) return 0;
            long accepted = Math.min(offered.amount(), definition.capacity() - content.amount());
            if (accepted > 0 && action == FluidAction.EXECUTE) set(FluidStack.of(offered.variant(), Math.addExact(content.amount(), accepted)));
            return accepted;
        }
        @Override public FluidStack drain(FluidVariant variant, long maximum, FluidAction action) {
            Objects.requireNonNull(variant, "variant"); checkRequest(maximum, action);
            if (read.protectedData() || content.isEmpty() || !content.variant().equals(variant)) return FluidStack.EMPTY;
            long extracted = Math.min(maximum, content.amount());
            FluidStack result = FluidStack.of(variant, extracted);
            if (extracted > 0 && action == FluidAction.EXECUTE) set(FluidStack.of(variant, content.amount() - extracted));
            return result;
        }
        private void set(FluidStack changed) { data.write(item, changed); content = changed; read = data.read(item); }
    }
    private final class VanillaContainer extends DetachedContainer {
        private FluidStack content;
        private final boolean bottle;
        VanillaContainer(ItemStack item, FluidStack content) {
            super(item); this.content = content;
            bottle = item.getType() == Material.GLASS_BOTTLE || item.getType() == Material.POTION || item.getType() == Material.HONEY_BOTTLE;
        }
        @Override public FluidStack content() { check(); return content; }
        @Override public long capacity() { check(); return bottle ? 250 : 1000; }
        @Override public boolean accepts(FluidVariant variant) { check(); return vanillaMaterial(Objects.requireNonNull(variant), bottle) != null; }
        @Override public ItemFluidReadResult readResult() {
            check(); return new ItemFluidReadResult(content.isEmpty() ? ItemFluidReadResult.Status.EMPTY : ItemFluidReadResult.Status.PRESENT,
                    content, new byte[0], new byte[0], "Vanilla container contents", item);
        }
        @Override public long fill(FluidStack offered, FluidAction action) {
            Objects.requireNonNull(offered, "offered"); checkRequest(offered.amount(), action);
            long unit = capacity();
            if (!content.isEmpty() || offered.isEmpty() || offered.amount() < unit || !accepts(offered.variant())) return 0;
            if (action == FluidAction.EXECUTE) {
                item.setType(vanillaMaterial(offered.variant(), bottle));
                if (item.getType() == Material.POTION) {
                    PotionMeta meta = (PotionMeta) item.getItemMeta();
                    meta.setBasePotionType(PotionType.WATER); item.setItemMeta(meta);
                }
                content = FluidStack.of(offered.variant(), unit);
            }
            return unit;
        }
        @Override public FluidStack drain(FluidVariant variant, long maximum, FluidAction action) {
            Objects.requireNonNull(variant, "variant"); checkRequest(maximum, action);
            if (content.isEmpty() || !content.variant().equals(variant) || maximum < capacity()) return FluidStack.EMPTY;
            FluidStack result = content;
            if (action == FluidAction.EXECUTE) { item.setType(bottle ? Material.GLASS_BOTTLE : Material.BUCKET); content = FluidStack.EMPTY; }
            return result;
        }
    }
    private final class ProtectedContainer extends DetachedContainer {
        private final ItemFluidReadResult read;
        ProtectedContainer(ItemStack item, ItemFluidReadResult read) { super(item); this.read = read; }
        @Override public FluidStack content() { check(); return read.stack(); }
        @Override public long capacity() { check(); return 0; }
        @Override public boolean accepts(FluidVariant variant) { check(); Objects.requireNonNull(variant); return false; }
        @Override public ItemFluidReadResult readResult() { check(); return read; }
        @Override public long fill(FluidStack offered, FluidAction action) { checkRequest(Objects.requireNonNull(offered).amount(), action); return 0; }
        @Override public FluidStack drain(FluidVariant variant, long maximum, FluidAction action) { Objects.requireNonNull(variant); checkRequest(maximum, action); return FluidStack.EMPTY; }
    }
    private static boolean empty(ItemStack item) {
        return item.getAmount() <= 0 || item.getType() == Material.AIR || item.getType() == Material.CAVE_AIR || item.getType() == Material.VOID_AIR;
    }
    private static FluidStack vanillaContent(ItemStack item) {
        return switch (item.getType()) {
            case BUCKET, GLASS_BOTTLE -> FluidStack.EMPTY;
            case WATER_BUCKET -> FluidStack.of(FluidVariant.of("minecraft:water"), 1000);
            case LAVA_BUCKET -> FluidStack.of(FluidVariant.of("minecraft:lava"), 1000);
            case MILK_BUCKET -> FluidStack.of(FluidVariant.of("minecraft:milk"), 1000);
            case HONEY_BOTTLE -> FluidStack.of(FluidVariant.of("minecraft:honey"), 250);
            case POTION -> item.getItemMeta() instanceof PotionMeta potion && potion.getBasePotionType() == PotionType.WATER
                    && potion.getCustomEffects().isEmpty() ? FluidStack.of(FluidVariant.of("minecraft:water"), 250) : null;
            default -> null;
        };
    }
    private static Material vanillaMaterial(FluidVariant variant, boolean bottle) {
        if (!variant.components().isEmpty()) return null;
        if (bottle) return switch (variant.fluid().toString()) {
            case "minecraft:water" -> Material.POTION;
            case "minecraft:honey" -> Material.HONEY_BOTTLE;
            default -> null;
        };
        return switch (variant.fluid().toString()) {
            case "minecraft:water" -> Material.WATER_BUCKET;
            case "minecraft:lava" -> Material.LAVA_BUCKET;
            case "minecraft:milk" -> Material.MILK_BUCKET;
            default -> null;
        };
    }
}
