/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce.verification;

import com.ydxc20091.fluidcore.api.FluidAction;
import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.api.FluidVariant;
import com.ydxc20091.fluidcore.bukkit.BukkitStorageContext;
import com.ydxc20091.fluidcore.ce.CraftEngineBridge;
import com.ydxc20091.fluidcore.core.FluidTank;
import com.ydxc20091.fluidcore.core.FluidTransfers;
import com.ydxc20091.fluidcore.core.TransferResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses real distant Folia owners without placing content or constructing synthetic region tokens. */
public final class CrossRegionVerification {
    private static final FluidVariant WATER = FluidVariant.of("minecraft:water");
    private CrossRegionVerification() {}

    public static CompletableFuture<Void> verifyCrossRegion(CraftEngineBridge bridge, Plugin plugin, World world) {
        if (!Bukkit.getServer().getName().toLowerCase(Locale.ROOT).contains("folia"))
            return CompletableFuture.completedFuture(null);
        if (!Files.isRegularFile(bridge.plugin().getDataFolder().toPath().resolve("verification.enabled"))
                || !world.getName().equals("fluidcore-verification"))
            return CompletableFuture.failedFuture(new IllegalStateException("Cross-region verification requires the enabled isolated world"));
        AtomicBoolean closed = new AtomicBoolean();
        Endpoint source = new Endpoint(new Location(world, 512, 100, 512));
        Endpoint destination = new Endpoint(new Location(world, 2560, 100, 512));
        CompletableFuture<FluidTank> sourceReady = prepare(bridge, plugin, source, closed, true);
        CompletableFuture<FluidTank> destinationReady = prepare(bridge, plugin, destination, closed, false);
        CompletableFuture<Void> operation = sourceReady.thenCombine(destinationReady, Pair::new)
                .thenCompose(pair -> bridge.schedule(source.location, () -> {
                    require(!closed.get(), "Verification was cancelled");
                    require(!Bukkit.isOwnedByCurrentRegion(destination.location), "Distant fixture unexpectedly shares the source region");
                    var transfer = FluidTransfers.move(pair.source, pair.destination, WATER, 1000, FluidAction.EXECUTE);
                    require(transfer.status() == TransferResult.Status.WRONG_CONTEXT && transfer.amount() == 0,
                            "Cross-region transfer was not explicitly rejected: " + transfer);
                    require(pair.source.content(0).equals(FluidStack.of(WATER, 1000)), "Rejected transfer changed its source");
                    return pair;
                }))
                .thenCompose(pair -> bridge.schedule(destination.location, () -> {
                    require(!closed.get(), "Verification was cancelled");
                    require(!Bukkit.isOwnedByCurrentRegion(source.location), "Distant fixture unexpectedly shares the destination region");
                    require(pair.destination.content(0).isEmpty(), "Rejected transfer changed its destination");
                    return pair;
                }))
                .thenCompose(pair -> bridge.schedule(source.location, () -> {
                    require(pair.source.content(0).equals(FluidStack.of(WATER, 1000)), "Source quantity changed after checking the remote owner");
                    return (Void) null;
                })).orTimeout(60, TimeUnit.SECONDS);

        CompletableFuture<Void> result = new CompletableFuture<>();
        operation.whenComplete((value, failure) -> {
            closed.set(true);
            CompletableFuture.allOf(release(bridge, plugin, source), release(bridge, plugin, destination))
                    .orTimeout(10, TimeUnit.SECONDS).whenComplete((released, releaseFailure) -> {
                        if (failure != null) {
                            if (releaseFailure != null) failure.addSuppressed(releaseFailure);
                            result.completeExceptionally(failure);
                        } else if (releaseFailure != null) result.completeExceptionally(releaseFailure);
                        else result.complete(null);
                    });
        });
        result.whenComplete((value, failure) -> { if (result.isCancelled()) operation.cancel(false); });
        return result;
    }

    private static CompletableFuture<FluidTank> prepare(CraftEngineBridge bridge, Plugin plugin, Endpoint endpoint,
                                                       AtomicBoolean closed, boolean source) {
        Location location = endpoint.location;
        return location.getWorld().getChunkAtAsync(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                .thenCompose(chunk -> bridge.schedule(location, () -> {
                    require(!closed.get(), "Verification closed before region preparation");
                    boolean added = location.getWorld().addPluginChunkTicket(location.getBlockX() >> 4, location.getBlockZ() >> 4, plugin);
                    endpoint.ticket.set(added);
                    if (closed.get()) {
                        if (endpoint.ticket.getAndSet(false)) location.getWorld().removePluginChunkTicket(location.getBlockX() >> 4, location.getBlockZ() >> 4, plugin);
                        throw new IllegalStateException("Verification closed during region preparation");
                    }
                    FluidTank tank = new FluidTank(1000, BukkitStorageContext.block(location,
                            () -> !closed.get() && plugin.isEnabled() && bridge.running()));
                    if (source) require(tank.fill(FluidStack.of(WATER, 1000), FluidAction.EXECUTE) == 1000, "Could not initialize source fixture");
                    return tank;
                }));
    }

    private static CompletableFuture<Void> release(CraftEngineBridge bridge, Plugin plugin, Endpoint endpoint) {
        if (!endpoint.ticket.get()) return CompletableFuture.completedFuture(null);
        return bridge.schedule(endpoint.location, () -> {
            if (endpoint.ticket.getAndSet(false)) endpoint.location.getWorld().removePluginChunkTicket(
                    endpoint.location.getBlockX() >> 4, endpoint.location.getBlockZ() >> 4, plugin);
            return (Void) null;
        });
    }

    private record Pair(FluidTank source, FluidTank destination) {}
    private static final class Endpoint {
        final Location location;
        final AtomicBoolean ticket = new AtomicBoolean();
        Endpoint(Location location) { this.location = location; }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
