/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce.verification;

import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.api.FluidVariant;
import com.ydxc20091.fluidcore.api.StorageAccessException;
import com.ydxc20091.fluidcore.api.FluidAction;
import com.ydxc20091.fluidcore.bukkit.ItemContainerTransfers;
import com.ydxc20091.fluidcore.ce.CraftEngineBridge;
import com.ydxc20091.fluidcore.core.FluidStackCodec;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.behavior.BlockItemBehavior;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockHitResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;

/** Destructive fixture operation, callable only in the explicitly enabled isolated verification world. */
public final class DropVerification {
    private static final Key TANK = Key.of("fluidcoreexample:tank");
    private static final FluidStack EXPECTED = FluidStack.of(FluidVariant.of("minecraft:water"), 3000);
    private DropVerification() {}

    public static void verify(CraftEngineBridge bridge, Location supplied) {
        Location location = supplied.clone();
        require(Files.isRegularFile(bridge.plugin().getDataFolder().toPath().resolve("verification.enabled")), "Verification gate is missing");
        require(location.getWorld() != null && location.getWorld().getName().equals("fluidcore-verification"), "Drop verification requires its isolated fixture world");
        require(Bukkit.isOwnedByCurrentRegion(location), "Drop verification must run on the owning region");
        require(location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4), "Fixture chunk is unloaded");
        var state = CraftEngineBlocks.getCustomBlockState(location.getBlock());
        require(state != null && state.owner().value().id().equals(TANK), "Fixture is not the passive example tank");
        var original = bridge.resolver().controller(location).orElseThrow();
        require(EXPECTED.equals(original.storage().content(0)), "Fixture must initially hold 3000 mB plain water");
        FluidStackCodec codec = new FluidStackCodec(bridge.registry());
        Chunk chunk = location.getWorld().getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        Set<UUID> before = entityIds(chunk);

        // Pinned CE 26.10 signature: movedByPiston, dropLoot, sendLevelEvent.
        require(CraftEngineBlocks.remove(location.getBlock(), null, false, true, false), "CE failed to remove the fixture with loot enabled");
        require(bridge.resolver().resolve(location).isEmpty(), "Removal left a live tank provider");

        ArrayList<Item> drops = new ArrayList<>();
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item item && !before.contains(entity.getUniqueId())
                    && entity.getLocation().distanceSquared(location.clone().add(0.5, 0.5, 0.5)) < 4)
                drops.add(item);
        }
        require(drops.size() == 1, "CE must spawn exactly one tank item; found " + drops.size());
        Item droppedEntity = drops.getFirst();
        ItemStack droppedStack = droppedEntity.getItemStack().clone();
        require(droppedStack.getAmount() == 1 && TANK.equals(CraftEngineItems.getCustomItemId(droppedStack)), "CE dropped an unexpected item or duplicated its count");
        byte[] data = ItemContainerTransfers.data(droppedStack, ItemContainerTransfers.TANK_DATA_KEY);
        require(data != null, "Real CE loot dropped a tank without persisted fluid PDC");
        var decoded = codec.decode(data);
        require(decoded.usable() && EXPECTED.equals(decoded.stack()), "Real CE tank drop did not preserve 3000 mB water");

        // Feed the actual dropped stack through CE's real BlockItemBehavior.place(). It calls
        // BlockEntityController.loadCustomDataFromItem itself; this helper does not restore a tank directly.
        var replayItem = BukkitAdaptor.adapt(droppedStack);
        var behavior = replayItem.getBehavior().orElseThrow().getFirst(BlockItemBehavior.class);
        require(behavior != null && behavior.block().equals(TANK), "Dropped tank is missing its standard CE block-item behavior");
        BlockPos position = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BlockHitResult hit = new BlockHitResult(Vec3d.atCenterOf(position), Direction.UP, position, false);
        BlockPlaceContext context = new BlockPlaceContext(BukkitAdaptor.adapt(location.getWorld()), null,
                InteractionHand.MAIN_HAND, replayItem, hit);
        require(context.getClickedPos().equals(position), "CE replay context targets another block");
        droppedEntity.remove();
        require(behavior.place(context).success(), "Standard CE block-item placement failed");
        // CE only shrinks stacks for player placement. This player-free fixture consumes its detached stack explicitly.
        replayItem.count(0);
        require(!droppedEntity.isValid(), "Fixture drop was not consumed");
        var replayed = bridge.resolver().controller(location).orElseThrow();
        require(replayed != original, "Replay reused the removed block entity");
        require(EXPECTED.equals(replayed.storage().content(0)), "Standard CE placement did not restore the dropped tank fluid");
        require(EXPECTED.equals(codec.decode(replayed.savedData()).stack()), "Replayed tank does not have the correct committed persistence blob");

        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item && !before.contains(entity.getUniqueId()) && entity.isValid()
                    && entity.getLocation().distanceSquared(location.clone().add(0.5, 0.5, 0.5)) < 4)
                throw new IllegalStateException("Drop replay left an extra item beside the 3000 mB tank");
        }
    }

    /** Separate fixture position: exercises real CE item placement with an incorrectly typed tank PDC. */
    public static void verifyMalformedItemPlacement(CraftEngineBridge bridge, Location supplied) {
        Location location = supplied.clone();
        require(Files.isRegularFile(bridge.plugin().getDataFolder().toPath().resolve("verification.enabled")), "Verification gate is missing");
        require(location.getWorld() != null && location.getWorld().getName().equals("fluidcore-verification"), "Malformed placement requires its isolated fixture world");
        require(Bukkit.isOwnedByCurrentRegion(location), "Malformed placement must run on the owning region");
        require(location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4), "Malformed fixture chunk is unloaded");
        var existing = CraftEngineBlocks.getCustomBlockState(location.getBlock());
        if (existing != null) {
            require(existing.owner().value().id().equals(TANK), "Malformed fixture may only replace its own tank");
            require(CraftEngineBlocks.remove(location.getBlock(), false), "Cannot remove the previous malformed fixture");
        }
        require(location.getBlock().getType().isAir(), "Malformed fixture position must be empty");
        var definition = CraftEngineItems.byId(TANK);
        require(definition != null, "Malformed fixture tank definition is missing");
        ItemStack source = definition.buildBukkitItem();
        var meta = source.getItemMeta();
        meta.getPersistentDataContainer().set(ItemContainerTransfers.TANK_DATA_KEY, PersistentDataType.STRING, "unresolved original tank value");
        NamespacedKey foreign = new NamespacedKey("verification", "original_marker");
        meta.getPersistentDataContainer().set(foreign, PersistentDataType.INTEGER, 90210);
        source.setItemMeta(meta);
        placeItem(location, source);
        var controller = bridge.resolver().controller(location).orElseThrow();
        require(controller.hasProtectedData(), "Incorrect tank PDC type became a writable empty tank");
        byte[] archive = controller.savedData();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(archive))) {
            require(input.readInt() == 0x46435044 && input.readUnsignedByte() == 1, "Malformed item did not use its archival PDC envelope");
            int length = input.readInt();
            require(length > 0 && length == input.available(), "Malformed PDC archival length is invalid");
            ItemStack restored = new ItemStack(Material.PAPER);
            var restoredMeta = restored.getItemMeta();
            restoredMeta.getPersistentDataContainer().readFromBytes(input.readNBytes(length), true);
            require("unresolved original tank value".equals(restoredMeta.getPersistentDataContainer().get(ItemContainerTransfers.TANK_DATA_KEY, PersistentDataType.STRING)),
                    "Malformed PDC archive lost the original key/type/value");
            require(Integer.valueOf(90210).equals(restoredMeta.getPersistentDataContainer().get(foreign, PersistentDataType.INTEGER)), "Malformed PDC archive lost the accompanying original data");
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        boolean rejected = false;
        try { controller.storage().fill(FluidStack.of(FluidVariant.of("minecraft:water"), 1000), FluidAction.EXECUTE); }
        catch (StorageAccessException expected) { rejected = true; }
        require(rejected && Arrays.equals(archive, controller.savedData()), "Malformed item accepted writes or changed its archival bytes");

        Chunk chunk = location.getChunk();
        Set<UUID> before = entityIds(chunk);
        require(CraftEngineBlocks.remove(location.getBlock(), null, false, true, false), "Malformed fixture removal failed");
        ArrayList<Item> drops = new ArrayList<>();
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Item item && !before.contains(entity.getUniqueId())
                    && entity.getLocation().distanceSquared(location.clone().add(0.5, 0.5, 0.5)) < 4) drops.add(item);
        }
        require(drops.size() == 1 && drops.getFirst().getItemStack().getAmount() == 1, "Malformed tank did not drop exactly one preserved item");
        Item drop = drops.getFirst();
        ItemStack replay = drop.getItemStack().clone();
        require(Arrays.equals(archive, ItemContainerTransfers.data(replay, ItemContainerTransfers.TANK_DATA_KEY)), "Malformed tank loot lost its original archival bytes");
        drop.remove();
        placeItem(location, replay);
        controller = bridge.resolver().controller(location).orElseThrow();
        require(controller.hasProtectedData() && Arrays.equals(archive, controller.savedData()), "Malformed archive did not survive actual CE replacement");

        // An explicit valid load must clear a preceding malformed CE tag, including its save override.
        CompoundTag malformed = new CompoundTag();
        malformed.putString("fluidcore:tank", "previous malformed CE value");
        controller.loadCustomData(malformed);
        CompoundTag valid = new CompoundTag();
        byte[] validBytes = new FluidStackCodec(bridge.registry()).encode(EXPECTED);
        valid.putByteArray("fluidcore:tank", validBytes);
        controller.loadCustomData(valid);
        CompoundTag exported = new CompoundTag();
        controller.saveCustomData(exported);
        require(!controller.hasProtectedData() && EXPECTED.equals(controller.storage().content(0))
                && Arrays.equals(validBytes, exported.getByteArray("fluidcore:tank")), "Valid data load left an obsolete malformed save override");
        require(CraftEngineBlocks.remove(location.getBlock(), false), "Malformed fixture cleanup failed");
    }

    private static void placeItem(Location location, ItemStack item) {
        var wrapped = BukkitAdaptor.adapt(item);
        var behavior = wrapped.getBehavior().orElseThrow().getFirst(BlockItemBehavior.class);
        require(behavior != null && behavior.block().equals(TANK), "Fixture item is missing its standard block placement behavior");
        BlockPos position = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BlockPlaceContext context = new BlockPlaceContext(BukkitAdaptor.adapt(location.getWorld()), null,
                InteractionHand.MAIN_HAND, wrapped, new BlockHitResult(Vec3d.atCenterOf(position), Direction.UP, position, false));
        require(behavior.place(context).success(), "CE could not place the malformed fixture item");
        wrapped.count(0);
    }

    private static Set<UUID> entityIds(Chunk chunk) {
        Set<UUID> ids = new HashSet<>();
        for (Entity entity : chunk.getEntities()) ids.add(entity.getUniqueId());
        return ids;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
