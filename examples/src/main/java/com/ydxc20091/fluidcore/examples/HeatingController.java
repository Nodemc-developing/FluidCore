/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.examples;

import com.ydxc20091.fluidcore.api.FluidKey;
import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.api.FluidVariant;
import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.bukkit.BukkitStorageContext;
import net.momirealms.craftengine.bukkit.world.BukkitWorld;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.block.entity.tick.SleepingBlockEntityTicker;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.Location;

/** Converts a whole water batch, preserving quantity and components, then stops ticking. */
public final class HeatingController extends BlockEntityController {
    private static final FluidKey WATER = FluidKey.of("minecraft:water");
    private static final FluidKey HEATED = FluidKey.of("fluidcoreexample:heated_water");
    private final FluidCoreExamplesPlugin plugin;
    private long tickCount;
    private boolean accessFailureReported;
    private final SleepingBlockEntityTicker<HeatingController> ticker = new SleepingBlockEntityTicker<>((world, position, state, controller) -> controller.process());

    HeatingController(BlockEntity entity, FluidCoreExamplesPlugin plugin) {
        super(entity);
        this.plugin = plugin;
        ticker.sleep();
    }

    Location location() {
        var position = blockEntity.pos();
        return new Location(((BukkitWorld) blockEntity.world().world()).bukkitWorld(), position.x(), position.y(), position.z());
    }

    @Override public void onLoad() { plugin.loaded(this); ticker.wakeUp(); }
    @Override public void onUnload() { plugin.unloaded(this); ticker.sleep(); }
    @Override public void onRemove() { plugin.unloaded(this); ticker.sleep(); }
    void wakeUp() {
        if (!plugin.isEnabled() || !blockEntity.isValid()) return;
        BukkitStorageContext.block(location(), blockEntity::isValid).checkAccess();
        ticker.wakeUp();
    }

    /** Diagnostics must be queried from this block's owning region. */
    public boolean sleeping() {
        BukkitStorageContext.block(location(), blockEntity::isValid).checkAccess();
        return ticker.isSleeping();
    }

    public long tickCount() {
        BukkitStorageContext.block(location(), blockEntity::isValid).checkAccess();
        return tickCount;
    }

    @Override public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(CEWorld world, ImmutableBlockState state) {
        return createTickerHelper(ticker);
    }

    private void process() {
        tickCount++;
        if (!plugin.isEnabled() || !blockEntity.isValid()) { ticker.sleep(); return; }
        try {
            var storage = plugin.bridge().resolver().resolve(location()).orElse(null);
            if (storage == null || storage.tanks() != 1) return;
            var content = storage.content(0);
            if (content.isEmpty() || !content.variant().fluid().equals(WATER) || content.amount() < 1000) return;
            var output = FluidVariant.of(HEATED, content.variant().components());
            try (var transaction = FluidTransaction.open()) {
                if (storage.extract(content.variant(), content.amount(), transaction) != content.amount()
                        || storage.insert(output, content.amount(), transaction) != content.amount()) return;
                transaction.commit();
            }
        } catch (StorageAccessException rejected) {
            if (!accessFailureReported) {
                accessFailureReported = true;
                plugin.getLogger().warning("Heater at " + blockEntity.pos() + " is sleeping: " + rejected.getMessage());
            }
        } finally {
            ticker.sleep();
        }
    }
}
