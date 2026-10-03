package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.bukkit.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Hopper;
import org.bukkit.inventory.ItemStack;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One item per eight ticks; all inventory reads happen only on the owning region. */
public final class TankHoppers {
    private static final List<BlockFace> FACES = List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);
    private final CraftEngineBridge bridge;
    private final Set<FluidTankController> pending = ConcurrentHashMap.newKeySet();
    private final Map<FluidTankController, Map<BlockFace, Boolean>> remote = new ConcurrentHashMap<>();
    private final Map<FluidTankController, Boolean> localNeighbors = new ConcurrentHashMap<>();
    private final Map<FluidTankController, Integer> turns = new ConcurrentHashMap<>();
    TankHoppers(CraftEngineBridge bridge) { this.bridge = bridge; }
    public boolean hasNeighbor(FluidTankController tank) {
        if (pending.contains(tank) || remote.getOrDefault(tank, Map.of()).containsValue(true)) return true;
        // An invalidated scan must remain awake until the next eight-tick scan has completed.
        return !Boolean.FALSE.equals(localNeighbors.get(tank));
    }
    public boolean tick(FluidTankController tank) {
        if (tank.hasProtectedData()) return false;
        Location center = tank.location();
        localNeighbors.put(tank, false);
        int turn = turns.merge(tank, 1, (old, one) -> old == Integer.MAX_VALUE ? 0 : old + one);
        for (int examined = 0; examined < FACES.size(); examined++) {
            var face = FACES.get(Math.floorMod(turn + examined, FACES.size()));
            Location position = neighbor(center, face);
            if (!loaded(position)) {
                Map<BlockFace, Boolean> cached = remote.get(tank);
                if (cached != null) cached.remove(face);
                continue;
            }
            if (!Bukkit.isOwnedByCurrentRegion(position)) {
                if (Boolean.FALSE.equals(remote.getOrDefault(tank, Map.of()).get(face))) continue;
                if (!pending.add(tank)) return true;
                bridge.schedule(position, () -> position.getBlock().getType() == Material.HOPPER)
                        .whenComplete((hopper, failure) -> {
                            if (!Boolean.TRUE.equals(hopper) || failure != null) { finishRemote(tank, face, false); return; }
                            var transfer = face == BlockFace.DOWN
                                    ? bridge.handoffs().transfer(TankHandoffEndpoints.tankSource(bridge, tank, false), TankHandoffEndpoints.hopperDestination(bridge, position))
                                    : bridge.handoffs().transfer(TankHandoffEndpoints.hopperSource(bridge, position, center, turn / FACES.size()), TankHandoffEndpoints.tankDestination(bridge, tank));
                            transfer.whenComplete((outcome, transferFailure) -> {
                                if (outcome == com.ydxc20091.fluidcore.bukkit.RegionItemHandoff.Outcome.RECOVERY_REQUIRED || transferFailure != null)
                                    bridge.plugin().getLogger().warning("Hopper handoff requires recovery; original item bytes retained in handoff-recovery");
                                finishRemote(tank, face, true);
                            });
                        });
                return true;
            }
            var block = position.getBlock();
            if (!(block.getState(false) instanceof Hopper hopper)) continue;
            var data = (org.bukkit.block.data.type.Hopper) block.getBlockData();
            if (!data.isEnabled()) continue;
            localNeighbors.put(tank, true);
            var items = new InventoryParticipant(hopper.getInventory(), BukkitStorageContext.block(position,
                    () -> bridge.running() && position.getBlock().getType() == Material.HOPPER));
            if (face == BlockFace.DOWN && tank.inventory().output() != null) {
                try (var transaction = FluidTransaction.open()) {
                    ItemStack item = tank.inventory().extractOutput(1, transaction);
                    if (item != null && items.putOne(item, transaction)) { transaction.commit(); return true; }
                }
            }
            if (face == BlockFace.DOWN || data.getFacing() != face.getOppositeFace()) continue;
            for (int slot = 0; slot < hopper.getInventory().getSize(); slot++) {
                ItemStack original = hopper.getInventory().getItem(slot);
                if (original == null || original.getType().isAir()) continue;
                ItemStack one = original.clone(); one.setAmount(1);
                if (!tank.inventory().canAcceptInput(one)) continue;
                try (var transaction = FluidTransaction.open()) {
                    if (items.takeOne(slot, original, transaction) && tank.inventory().insertInput(one, transaction)) { transaction.commit(); return true; }
                }
            }
        }
        return false;
    }
    private void finishRemote(FluidTankController tank, BlockFace face, boolean hopper) {
        pending.remove(tank);
        if (!bridge.running()) return;
        bridge.schedule(tank.location(), () -> {
            if (bridge.resolver().controller(tank.location()).orElse(null) != tank) return null;
            remote.computeIfAbsent(tank, ignored -> new ConcurrentHashMap<>()).put(face, hopper);
            localNeighbors.remove(tank); tank.wakeUp(); return null;
        });
    }
    public void invalidate(FluidTankController tank) { remote.remove(tank); localNeighbors.remove(tank); }
    public void retire(FluidTankController tank) { invalidate(tank); pending.remove(tank); turns.remove(tank); }
    private static Location neighbor(Location center, BlockFace face) { return center.clone().add(face.getModX(), face.getModY(), face.getModZ()); }
    private static boolean loaded(Location location) { return location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4); }
}
