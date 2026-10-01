/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.examples;

import com.ydxc20091.fluidcore.FluidCorePlugin;
import com.ydxc20091.fluidcore.bukkit.FluidStorageCommitEvent;
import com.ydxc20091.fluidcore.ce.CraftEngineBridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional demo content. It registers before CE parses its content pack. */
public final class FluidCoreExamplesPlugin extends JavaPlugin implements Listener {
    private final ConcurrentHashMap<Position, HeatingController> loaded = new ConcurrentHashMap<>();
    private CraftEngineBridge bridge;

    @Override public void onLoad() {
        bridge = JavaPlugin.getPlugin(FluidCorePlugin.class).bridge();
        BlockBehaviors.register(Key.of("fluidcoreexample:heater"), (block, config) -> new HeatingBehavior(block, this));
        try {
            Path pack = getDataFolder().toPath().getParent().resolve("CraftEngine/resources/fluidcore-examples");
            install(pack, "pack.yml");
            install(pack, "configuration/examples.yml");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not install the optional FluidCore example pack", exception);
        }
    }

    private void install(Path pack, String path) throws IOException {
        Path target = pack.resolve(path);
        if (Files.exists(target)) return;
        Files.createDirectories(target.getParent());
        try (var input = getResource("pack/" + path)) {
            if (input == null) throw new IOException("Missing embedded resource: " + path);
            Files.copy(input, target);
        }
    }

    @Override public void onEnable() {
        bridge = getServer().getServicesManager().load(CraftEngineBridge.class);
        if (bridge == null) throw new IllegalStateException("FluidCore bridge service is unavailable");
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ydxc20091 example pack loaded: tank, canteen and sleeping water heater");
    }

    @Override public void onDisable() { loaded.clear(); }
    CraftEngineBridge bridge() { return bridge; }
    void loaded(HeatingController controller) { loaded.put(Position.of(controller.location()), controller); }
    void unloaded(HeatingController controller) { loaded.remove(Position.of(controller.location()), controller); }

    private void wake(Location location) {
        HeatingController controller = loaded.get(Position.of(location));
        if (controller != null) controller.wakeUp();
    }

    @EventHandler public void onFluidCommit(FluidStorageCommitEvent event) { wake(event.location()); }
    @EventHandler(ignoreCancelled = true) public void onNeighborUpdate(BlockPhysicsEvent event) { wake(event.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        for (var entry : Map.copyOf(loaded).entrySet()) {
            Position position = entry.getKey();
            HeatingController controller = entry.getValue();
            var world = getServer().getWorld(position.world());
            if (world == null) continue;
            var location = new Location(world, position.x(), position.y(), position.z());
            bridge.schedule(location, () -> {
                if (isEnabled() && loaded.get(position) == controller) controller.wakeUp();
                return null;
            }).exceptionally(failure -> null);
        }
    }

    private record Position(java.util.UUID world, int x, int y, int z) {
        static Position of(Location location) {
            return new Position(java.util.Objects.requireNonNull(location.getWorld()).getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }
}
