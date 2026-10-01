/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.bukkit.ContainerDefinition;
import com.ydxc20091.fluidcore.bukkit.ItemContainerTransfers;
import com.ydxc20091.fluidcore.bukkit.BukkitStorageContext;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifiers;
import net.momirealms.craftengine.core.loot.function.LootFunctions;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** All version-sensitive CraftEngine 26.10 types are confined to this package. */
public final class CraftEngineBridge implements AutoCloseable, Listener {
    private final JavaPlugin plugin;
    private final FluidRegistry registry;
    private final FluidConfigParser parser;
    private final BlockProviderResolver resolver;
    private final ItemContainerTransfers containers;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean running;
    private boolean registered;
    private final Set<CompletableFuture<?>> scheduled = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<BlockAddress, byte[]> removedTankData = new ConcurrentHashMap<>();

    public CraftEngineBridge(JavaPlugin plugin, FluidRegistry registry) {
        this.plugin = Objects.requireNonNull(plugin);
        this.registry = Objects.requireNonNull(registry);
        parser = new FluidConfigParser(registry, plugin.getLogger());
        resolver = new BlockProviderResolver(this);
        containers = new ItemContainerTransfers(registry, this::definition, CraftEngineItems::isCustomItem);
    }

    /** Invoke from onLoad, after CE onLoad and before CE begins its first resource parse. */
    public void register() {
        if (registered) throw new IllegalStateException("Bridge already registered");
        CraftEngine engine = CraftEngine.instance();
        if (engine == null) throw new IllegalStateException("CraftEngine 26.10 must be loaded before FluidCore");
        if (engine.isFullyLoaded()) throw new IllegalStateException("FluidCore extensions must register before the first CE resource parse");
        ItemSettingsModifiers.register(Key.of("fluidcore:container"), value -> {
            ContainerDefinition definition = ContainerSettings.parse(value.getAsSection());
            return settings -> settings.addCustomData(ContainerSettings.TYPE, definition);
        });
        ItemBehaviors.register(Key.of("fluidcore:container"), (pack, path, id, section) -> new FluidContainerBehavior(this));
        BlockBehaviors.register(Key.of("fluidcore:tank"), (block, section) -> new FluidTankBehavior(block, section, this));
        LootFunctions.register(Key.of("fluidcore:preserve_tank"), section -> new PreserveTankLootFunction(this));
        if (!engine.packManager().registerConfigSectionParser(parser)) throw new IllegalStateException("CE fluid parser registration was rejected");
        if (!engine.packManager().registerConfigSectionParser(parser.tagsParser()))
            throw new IllegalStateException("CE fluid tag parser registration was rejected");
        if (!engine.packManager().registerConfigSectionParser(parser.readyParser()))
            throw new IllegalStateException("CE fluid registry ready parser registration was rejected");
        registered = true;
    }

    public void start() {
        if (!registered) throw new IllegalStateException("Register the bridge before starting it");
        running = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (parser.publish()) generation.incrementAndGet();
    }

    @EventHandler public void onReload(CraftEngineReloadEvent event) {
        if (!running) return;
        parser.publish();
        generation.incrementAndGet();
        containers.containers().invalidate();
    }

    @EventHandler public void onPluginDisable(PluginDisableEvent event) {
        resolver.unregisterOwner(event.getPlugin());
        containers.containers().unregisterOwner(event.getPlugin());
    }

    private ContainerDefinition definition(ItemStack item) {
        var definition = CraftEngineItems.byItemStack(item);
        return definition == null ? null : definition.settings().getCustomData(ContainerSettings.TYPE);
    }

    InteractionResult interact(UseOnContext context) {
        if (!running || context.getPlayer() == null) return InteractionResult.PASS;
        Player player = (Player) context.getPlayer().platformPlayer();
        var pos = context.getClickedPos();
        Location location = new Location((org.bukkit.World) context.getLevel().platformWorld(), pos.x(), pos.y(), pos.z());
        var storage = resolver.resolve(location);
        if (storage.isEmpty()) return InteractionResult.PASS;
        if (!BukkitCraftEngine.instance().antiGriefProvider().test(player, Flag.OPEN_CONTAINER, location)) return InteractionResult.SUCCESS_AND_CANCEL;
        int slot = context.getHand() == InteractionHand.OFF_HAND ? 40 : player.getInventory().getHeldItemSlot();
        try {
            var result = containers.transfer(player, slot, storage.get(), Long.MAX_VALUE);
            if (result == ItemContainerTransfers.Result.NOT_A_CONTAINER) return InteractionResult.PASS;
            return InteractionResult.SUCCESS_AND_CANCEL;
        } catch (StorageAccessException rejected) {
            player.sendMessage("FluidCore: " + rejected.getMessage());
            return InteractionResult.SUCCESS_AND_CANCEL;
        }
    }

    public JavaPlugin plugin() { return plugin; }
    public FluidRegistry registry() { return registry; }
    public BlockProviderResolver resolver() { return resolver; }
    public ItemContainerTransfers containers() { return containers; }
    public long generation() { return generation.get(); }
    public boolean running() { return running; }

    void preserveRemoval(Location location, byte[] data) {
        if (!running) return;
        BlockAddress address = BlockAddress.of(location);
        byte[] snapshot = data.clone();
        removedTankData.put(address, snapshot);
        // One-shot expiry is attached to removal, never an idle tank tick or a polling loop.
        Bukkit.getRegionScheduler().runDelayed(plugin, location, task -> removedTankData.remove(address, snapshot), 2);
    }

    Optional<byte[]> dataForDrop(Location location) {
        var current = resolver.controller(location);
        if (current.isPresent()) return Optional.of(current.get().savedData());
        byte[] removed = removedTankData.remove(BlockAddress.of(location));
        return removed == null ? Optional.empty() : Optional.of(removed.clone());
    }

    private record BlockAddress(UUID world, int x, int y, int z) {
        static BlockAddress of(Location location) {
            return new BlockAddress(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    public <T> CompletableFuture<T> schedule(Location location, Supplier<T> operation) {
        if (!running) return CompletableFuture.failedFuture(new StorageAccessException("FluidCore is stopped"));
        CompletableFuture<T> future = BukkitStorageContext.schedule(plugin, location, () -> {
            if (!running) throw new StorageAccessException("FluidCore stopped before the operation");
            return operation.get();
        });
        scheduled.add(future);
        future.whenComplete((value, failure) -> scheduled.remove(future));
        if (!running) future.cancel(false);
        return future;
    }

    @Override public void close() {
        running = false;
        generation.incrementAndGet();
        for (CompletableFuture<?> future : scheduled) future.cancel(false);
        scheduled.clear();
        removedTankData.clear();
        resolver.close();
        containers.containers().close();
        HandlerList.unregisterAll(this);
        if (registered && CraftEngine.instance() != null) {
            CraftEngine.instance().packManager().unregisterConfigSectionParser(parser);
            CraftEngine.instance().packManager().unregisterConfigSectionParser(parser.tagsParser());
            CraftEngine.instance().packManager().unregisterConfigSectionParser(parser.readyParser());
        }
    }
}
