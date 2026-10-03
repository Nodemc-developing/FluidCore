package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.bukkit.*;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.*;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** Endpoint methods run solely on their declared owner and exchange serialized count-one values. */
public final class TankHandoffEndpoints {
    private TankHandoffEndpoints() {}
    private static byte[] encodeOne(ItemStack item) { ItemStack one = item.clone(); one.setAmount(1); return BukkitItemManager.instance().wrap(one).toBytes(); }
    private static ItemStack decode(byte[] bytes) { ItemStack item = (ItemStack) Item.fromBytes(bytes).platformItem(); if (item.getAmount() != 1) throw new IllegalArgumentException("Escrow requires one item"); return item; }
    public static RegionItemHandoff.Source playerSource(CraftEngineBridge bridge, Player player, int slot, BooleanSupplier permitted) {
        return new RegionItemHandoff.Source() {
            ItemStack expected;
            public <T> CompletableFuture<T> onOwner(Supplier<T> operation) { return entityOwner(bridge, player, operation); }
            public byte[] prepare() {
                if (!permitted.getAsBoolean()) return null; expected = player.getInventory().getItem(slot);
                if (expected == null || expected.getAmount() < 1) return null; expected = expected.clone(); return encodeOne(expected);
            }
            public boolean reserve() {
                if (!permitted.getAsBoolean()) return false;
                var access = new InventoryParticipant(player.getInventory(), BukkitStorageContext.entity(player));
                try (var transaction = FluidTransaction.open()) { if (!access.takeOne(slot, expected, transaction)) return false; transaction.commit(); return true; }
            }
            public boolean compensate(byte[] bytes) {
                var access = new InventoryParticipant(player.getInventory(), BukkitStorageContext.entity(player));
                try (var transaction = FluidTransaction.open()) { if (!access.putOne(decode(bytes), transaction)) return false; transaction.commit(); return true; }
            }
            public String address() { return "player:" + player.getUniqueId() + ":" + slot; }
        };
    }
    public static RegionItemHandoff.Destination playerDestination(CraftEngineBridge bridge, Player player, BooleanSupplier permitted) {
        return new RegionItemHandoff.Destination() {
            public <T> CompletableFuture<T> onOwner(Supplier<T> operation) { return entityOwner(bridge, player, operation); }
            public boolean canAccept(byte[] bytes) {
                if (!permitted.getAsBoolean()) return false;
                var access = new InventoryParticipant(player.getInventory(), BukkitStorageContext.entity(player));
                return access.canPutOne(decode(bytes));
            }
            public boolean deliver(byte[] bytes) {
                if (!permitted.getAsBoolean()) return false;
                var access = new InventoryParticipant(player.getInventory(), BukkitStorageContext.entity(player));
                try (var transaction = FluidTransaction.open()) { if (!access.putOne(decode(bytes), transaction)) return false; transaction.commit(); return true; }
            }
            public String address() { return "player:" + player.getUniqueId(); }
        };
    }
    public static RegionItemHandoff.Destination tankDestination(CraftEngineBridge bridge, FluidTankController tank) {
        Location position = tank.location(); long generation = bridge.generation(), activation = tank.activationVersion();
        return new RegionItemHandoff.Destination() {
            public <T> CompletableFuture<T> onOwner(Supplier<T> operation) { return blockOwner(bridge, position, operation); }
            public boolean canAccept(byte[] bytes) { return valid(bridge, tank, position, generation, activation) && tank.inventory().canAcceptInput(decode(bytes)); }
            public boolean deliver(byte[] bytes) {
                if (!valid(bridge, tank, position, generation, activation)) return false;
                try (var transaction = FluidTransaction.open()) {
                    if (!tank.inventory().insertInput(decode(bytes), transaction)) return false;
                    try { transaction.commit(); } catch (RuntimeException notification) { if (!transaction.isCommitted()) throw notification; }
                    return true;
                }
            }
            public String address() { return "tank-input:" + TankHandoffEndpoints.address(position); }
        };
    }
    public static RegionItemHandoff.Source tankSource(CraftEngineBridge bridge, FluidTankController tank, boolean input) {
        Location position = tank.location(); long generation = bridge.generation(), activation = tank.activationVersion();
        return new RegionItemHandoff.Source() {
            ItemStack expected;
            public <T> CompletableFuture<T> onOwner(Supplier<T> operation) { return blockOwner(bridge, position, operation); }
            public byte[] prepare() {
                if (!valid(bridge, tank, position, generation, activation)) return null;
                expected = input ? tank.inventory().input() : tank.inventory().output(); return expected == null ? null : encodeOne(expected);
            }
            public boolean reserve() {
                if (!valid(bridge, tank, position, generation, activation) || !Objects.equals(expected, input ? tank.inventory().input() : tank.inventory().output())) return false;
                try (var transaction = FluidTransaction.open()) {
                    ItemStack extracted = input ? tank.inventory().extractInput(1, transaction) : tank.inventory().extractOutput(1, transaction);
                    if (extracted == null) return false;
                    try { transaction.commit(); } catch (RuntimeException notification) { if (!transaction.isCommitted()) throw notification; }
                    return true;
                }
            }
            public boolean compensate(byte[] bytes) {
                ItemStack item = decode(bytes);
                if (!valid(bridge, tank, position, generation, activation)) { position.getWorld().dropItemNaturally(position.clone().add(.5,.5,.5), item); return true; }
                try (var transaction = FluidTransaction.open()) {
                    if (!(input ? tank.inventory().insertInput(item, transaction) : tank.inventory().insertOutput(item, transaction))) return false;
                    try { transaction.commit(); } catch (RuntimeException notification) { if (!transaction.isCommitted()) throw notification; }
                    return true;
                }
            }
            public String address() { return (input ? "tank-input:" : "tank-output:") + TankHandoffEndpoints.address(position); }
        };
    }
    public static RegionItemHandoff.Source hopperSource(CraftEngineBridge bridge, Location position, Location tankPosition) {
        return hopperSource(bridge, position, tankPosition, 0);
    }
    public static RegionItemHandoff.Source hopperSource(CraftEngineBridge bridge, Location position, Location tankPosition, int startingSlot) {
        Location source = position.clone(); long generation = bridge.generation();
        return new RegionItemHandoff.Source() {
            ItemStack expected; int slot;
            public <T> CompletableFuture<T> onOwner(Supplier<T> operation) { return blockOwner(bridge, source, operation); }
            private Hopper hopper() {
                if (generation != bridge.generation() || source.getBlock().getType() != Material.HOPPER) return null;
                var data = (org.bukkit.block.data.type.Hopper) source.getBlock().getBlockData();
                if (!data.isEnabled()) return null;
                Location facing = source.clone().add(data.getFacing().getModX(), data.getFacing().getModY(), data.getFacing().getModZ());
                return facing.equals(tankPosition) ? (Hopper) source.getBlock().getState(false) : null;
            }
            public byte[] prepare() {
                Hopper hopper = hopper(); if (hopper == null) return null;
                int size = hopper.getInventory().getSize();
                for (int examined = 0; examined < size; examined++) {
                    slot = Math.floorMod(startingSlot + examined, size);
                    ItemStack item = hopper.getInventory().getItem(slot); if (item != null && item.getAmount() > 0) { expected = item.clone(); return encodeOne(item); }
                }
                return null;
            }
            public boolean reserve() {
                Hopper hopper = hopper(); if (hopper == null) return false;
                var access = new InventoryParticipant(hopper.getInventory(), BukkitStorageContext.block(source, bridge::running));
                try (var transaction = FluidTransaction.open()) { if (!access.takeOne(slot, expected, transaction)) return false; transaction.commit(); return true; }
            }
            public boolean compensate(byte[] bytes) {
                ItemStack item = decode(bytes);
                if (source.getBlock().getType() != Material.HOPPER) { source.getWorld().dropItemNaturally(source.clone().add(.5,.5,.5), item); return true; }
                var hopper = (Hopper) source.getBlock().getState(false);
                var access = new InventoryParticipant(hopper.getInventory(), BukkitStorageContext.block(source, bridge::running));
                try (var transaction = FluidTransaction.open()) { if (!access.putOne(item, transaction)) return false; transaction.commit(); return true; }
            }
            public String address() { return "hopper:" + TankHandoffEndpoints.address(source); }
        };
    }
    public static RegionItemHandoff.Destination hopperDestination(CraftEngineBridge bridge, Location location) {
        Location position = location.clone(); long generation = bridge.generation();
        return new RegionItemHandoff.Destination() {
            public <T> CompletableFuture<T> onOwner(Supplier<T> operation) { return blockOwner(bridge, position, operation); }
            public boolean canAccept(byte[] bytes) {
                if (generation != bridge.generation() || position.getBlock().getType() != Material.HOPPER) return false;
                var hopper = (Hopper) position.getBlock().getState(false);
                var access = new InventoryParticipant(hopper.getInventory(), BukkitStorageContext.block(position, bridge::running));
                return access.canPutOne(decode(bytes));
            }
            public boolean deliver(byte[] bytes) {
                if (generation != bridge.generation() || position.getBlock().getType() != Material.HOPPER
                        || !((org.bukkit.block.data.type.Hopper) position.getBlock().getBlockData()).isEnabled()) return false;
                var hopper = (Hopper) position.getBlock().getState(false);
                var access = new InventoryParticipant(hopper.getInventory(), BukkitStorageContext.block(position, bridge::running));
                try (var transaction = FluidTransaction.open()) { if (!access.putOne(decode(bytes), transaction)) return false; transaction.commit(); return true; }
            }
            public String address() { return "hopper:" + TankHandoffEndpoints.address(position); }
        };
    }
    private static boolean valid(CraftEngineBridge bridge, FluidTankController tank, Location position, long generation, long activation) {
        return bridge.running() && bridge.generation() == generation && tank.sameActivation(activation)
                && bridge.resolver().controller(position).orElse(null) == tank && !tank.hasProtectedData();
    }
    private static <T> CompletableFuture<T> blockOwner(CraftEngineBridge bridge, Location position, Supplier<T> operation) {
        return BukkitStorageContext.schedule(bridge.plugin(), position, () -> {
            if (!position.getWorld().isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4)) throw new StorageAccessException("Handoff endpoint unloaded");
            return operation.get();
        });
    }
    private static <T> CompletableFuture<T> entityOwner(CraftEngineBridge bridge, Player player, Supplier<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            if (player.getScheduler().run(bridge.plugin(), task -> { try { future.complete(operation.get()); } catch (Throwable failure) { future.completeExceptionally(failure); } },
                    () -> future.completeExceptionally(new StorageAccessException("Handoff player retired"))) == null)
                future.completeExceptionally(new StorageAccessException("Handoff player unavailable"));
        } catch (RuntimeException stopped) { future.completeExceptionally(stopped); }
        return future;
    }
    private static String address(Location position) { return position.getWorld().getUID() + ":" + position.getBlockX() + ":" + position.getBlockY() + ":" + position.getBlockZ(); }
}
