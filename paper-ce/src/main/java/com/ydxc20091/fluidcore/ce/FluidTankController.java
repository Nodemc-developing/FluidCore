/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.bukkit.*;
import com.ydxc20091.fluidcore.core.*;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.block.entity.tick.SleepingBlockEntityTicker;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.core.block.ImmutableBlockState;

/** Owner-confined storage with a native sleeping ticker and immutable save snapshots. */
public final class FluidTankController extends BlockEntityController {
    static final String DATA_KEY = "fluidcore:tank";
    private final CraftEngineBridge bridge;
    private final FluidTankBehavior behavior;
    private final FluidStackCodec codec;
    private final StorageContext context;
    private FluidTank tank;
    private volatile byte[] savedData;
    private volatile Tag malformedTag;
    private volatile CompoundTag foreignData;
    private long registryGeneration = -1;
    private final TankActivation activation = new TankActivation();
    private boolean protectedData;
    private FluidStack deferredContent = FluidStack.EMPTY;
    private FluidVariant validatedVariant;
    private long validatedRegistryGeneration = -1;
    private final TankInventory inventory;
    private volatile byte[] savedInput, savedOutput;
    private SleepingBlockEntityTicker<FluidTankController> ticker;
    private int hopperCooldown;
    private volatile int glassColor = 0xffffff;
    private volatile int savedGlassColor = 0xffffff;
    private final java.util.List<net.momirealms.craftengine.core.world.chunk.ChunkSubscription> neighborSubscriptions = new java.util.ArrayList<>(2);
    private static final org.bukkit.NamespacedKey GLASS_COLOR = new org.bukkit.NamespacedKey("fluidcore", "glass_color");

    FluidTankController(BlockEntity entity, FluidTankBehavior behavior, CraftEngineBridge bridge) {
        super(entity);
        this.behavior = behavior;
        this.bridge = bridge;
        codec = new FluidStackCodec(bridge.registry());
        savedData = codec.encode(FluidStack.EMPTY);
        context = new BukkitStorageContext(
                () -> blockEntity.world != null && Bukkit.isOwnedByCurrentRegion(location()),
                () -> bridge.running() && activation.active() && blockEntity.isValid());
        inventory = new TankInventory(context, this::itemsChanged);
    }

    public Location location() {
        if (blockEntity.world == null) throw new StorageAccessException("CE block entity has no world");
        return new Location((World) blockEntity.world.world().platformWorld(), blockEntity.pos.x(), blockEntity.pos.y(), blockEntity.pos.z());
    }

    private void ensureTank() {
        context.checkAccess();
        if (malformedTag == null && foreignData == null && registryGeneration != bridge.registry().snapshot().generation()) {
            if (FluidTransaction.hasOpenTransaction()) throw new StorageAccessException("Resolve refreshed tank state before opening a transaction");
            read(savedData);
        }
        if (tank == null) {
            tank = new FluidTank(behavior.capacity(), context, behavior::accepts, this::committed);
            tank.restore(deferredContent);
        }
    }

    public FluidStorage storage() {
        ensureTank();
        long generation = bridge.generation();
        long activationVersion = activation.version();
        return new FluidStorage() {
            private final TransactionParticipant guard = new TransactionParticipant() {
                @Override public Object snapshot() { check(); return new GuardSnapshot(bridge.generation(), bridge.registry().snapshot().generation()); }
                @Override public void restore(Object snapshot) {}
                @Override public void validate() { check(); }
                @Override public void afterCommit() {}
                @Override public void validateSnapshot(Object snapshot) {
                    GuardSnapshot captured = (GuardSnapshot) snapshot;
                    check();
                    if (captured.bridgeGeneration != bridge.generation() || captured.registryGeneration != bridge.registry().snapshot().generation())
                        throw new StorageAccessException("Reload changed the tank registry during this transaction");
                }
            };
            private void check() {
                context.checkAccess();
                if (generation != bridge.generation()) throw new StorageAccessException("CE reload invalidated this provider; resolve it again");
                if (!activation.matches(activationVersion)) throw new StorageAccessException("Tank lifecycle invalidated this provider; resolve it again");
                ensureTank();
            }
            @Override public StorageContext context() {
                return new StorageContext() {
                    @Override public void checkAccess() { check(); }
                    @Override public long tick() { check(); return context.tick(); }
                };
            }
            @Override public int tanks() { check(); return tank.tanks(); }
            @Override public FluidStack content(int index) { check(); return tank.content(index); }
            @Override public long capacity(int index) { check(); return tank.capacity(index); }
            @Override public long insert(FluidVariant variant, long amount, FluidTransaction transaction) {
                checkWritable();
                if (tank.content(0).amount() > behavior.capacity()) throw new StorageAccessException("Tank exceeds configured capacity; drain existing content first");
                FluidStack content = tank.content(0);
                if (!content.isEmpty() && !behavior.accepts(content.variant())) throw new StorageAccessException("Tank content no longer matches its filter; drain existing content first");
                validateVariant(variant);
                transaction.enlist(guard);
                return tank.insert(variant, amount, transaction);
            }
            @Override public long extract(FluidVariant variant, long amount, FluidTransaction transaction) {
                checkWritable(); transaction.enlist(guard); return tank.extract(variant, amount, transaction);
            }
            private void checkWritable() {
                check();
                if (protectedData) throw new StorageAccessException("Tank contains unknown or invalid preserved data");
            }
        };
    }

    private void validateVariant(FluidVariant variant) {
        long current = bridge.registry().snapshot().generation();
        if (current == validatedRegistryGeneration && variant.equals(validatedVariant)) return;
        if (!codec.decode(codec.encode(FluidStack.of(variant, 1))).usable())
            throw new StorageAccessException("Insert requires a registered fluid and valid component codecs");
        validatedVariant = variant;
        validatedRegistryGeneration = current;
    }

    private record GuardSnapshot(long bridgeGeneration, long registryGeneration) {}

    /** Read-only lifecycle token; obtaining it does not grant storage or world access. */
    public long activationVersion() { return activation.version(); }
    /** Safe to check from another owner; all live inventory and world reads still require their owner. */
    public boolean sameActivation(long version) { return activation.matches(version); }
    /** Immutable presentation settings; this does not expose live block, inventory, or fluid state. */
    public TankMenuSettings menuSettings() { return behavior.menuSettings(); }

    public boolean hasProtectedData() { context.checkAccess(); return protectedData; }
    public byte[] savedData() { return savedData.clone(); }
    public TankInventory inventory() { context.checkAccess(); return inventory; }
    public void wakeUp() { context.checkAccess(); if (ticker != null) ticker.wakeUp(); }
    public void progressChanged() { context.checkAccess(); bridge.tankChanged(this); }
    public int glassColor() { context.checkAccess(); return glassColor; }
    public void writeColor(ItemStack item) { context.checkAccess(); item.editMeta(meta -> meta.getPersistentDataContainer().set(GLASS_COLOR, PersistentDataType.INTEGER, glassColor)); }
    boolean dye(org.bukkit.entity.Player player, int slot) {
        context.checkAccess();
        if (!Bukkit.isOwnedByCurrentRegion(player)) throw new StorageAccessException("Tank dye requires the player owner");
        if (!behavior.dyeable() || protectedData) return false;
        ItemStack held = player.getInventory().getItem(slot);
        if (held == null || !held.getType().name().endsWith("_DYE")) return false;
        org.bukkit.DyeColor dye;
        try { dye = org.bukkit.DyeColor.valueOf(held.getType().name().substring(0, held.getType().name().length() - 4)); }
        catch (IllegalArgumentException invalid) { return false; }
        int color = dye.getColor().asRGB(); int prior = glassColor;
        if (color == prior) return true;
        ItemSlotAccess source = new ItemSlotAccess(player.getInventory(), slot, BukkitStorageContext.entity(player));
        try (var transaction = FluidTransaction.open()) {
            new TankColorChange(context, () -> glassColor, value -> glassColor = value, bridge::generation, () -> {
                savedGlassColor = glassColor;
                blockEntity.world.blockEntityChanged(blockEntity.pos);
                synchronizeVisual();
                bridge.tankChanged(FluidTankController.this);
            }).set(color, transaction);
            if (player.getGameMode() != org.bukkit.GameMode.CREATIVE && !source.replaceOne(null, transaction)) return true;
            try { transaction.commit(); } catch (RuntimeException notification) { if (!transaction.isCommitted()) throw notification; }
        }
        return true;
    }
    private void itemsChanged() {
        savedInput = encodeItem(inventory.input()); savedOutput = encodeItem(inventory.output());
        blockEntity.world.blockEntityChanged(blockEntity.pos); wakeUp();
        bridge.tankChanged(this);
    }
    private static byte[] encodeItem(ItemStack item) { return item == null ? null : net.momirealms.craftengine.bukkit.item.BukkitItemManager.instance().wrap(item).toBytes(); }
    private static ItemStack decodeItem(byte[] bytes) { return bytes == null ? null : (ItemStack) Item.fromBytes(bytes).platformItem(); }
    @Override public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(CEWorld world, ImmutableBlockState state) {
        if (ticker == null) ticker = new SleepingBlockEntityTicker<>((level, position, blockState, controller) -> controller.tick());
        return createTickerHelper(ticker);
    }
    private void tick() {
        if (!activation.active() || !bridge.running()) { ticker.sleep(); return; }
        ensureTank();
        if (protectedData) { ticker.sleep(); return; }
        boolean moving = false;
        if (--hopperCooldown <= 0) { hopperCooldown = 8; moving = bridge.hoppers().tick(this); }
        boolean processing = bridge.processor() != null && bridge.processor().tick(this, 1);
        if (!moving && !processing && !bridge.hoppers().hasNeighbor(this)) ticker.sleep();
    }

    private void committed() {
        savedData = codec.encode(tank.content(0));
        registryGeneration = bridge.registry().snapshot().generation();
        blockEntity.world.blockEntityChanged(blockEntity.pos);
        synchronizeVisual();
        Bukkit.getPluginManager().callEvent(new FluidStorageCommitEvent(location(), tank.version()));
        wakeUp(); bridge.tankChanged(this);
    }

    private void synchronizeVisual() {
        if (protectedData || !bridge.running() || !activation.active()
                || blockEntity.world == null || !blockEntity.isValid() || !Bukkit.isOwnedByCurrentRegion(location())) return;
        var current = blockEntity.blockState();
        if (!current.owner().value().id().equals(behavior.block().id())) return;
        FluidStack content = tank == null ? deferredContent : tank.content(0);
        publishRender(current, content);
        if (!behavior.hasVisualState()) { blockEntity.updateConstantRenderers(); return; }
        var next = behavior.visualState(current, content);
        if (next == current) { blockEntity.updateConstantRenderers(); return; }
        // Updating properties on the same block preserves its controller and stored contents.
        blockEntity.world.world().setBlockState(blockEntity.pos, next, 2);
    }
    private void publishRender(ImmutableBlockState current, FluidStack content) {
        int fluidColor = content.isEmpty() ? 0xffffff : bridge.registry().find(content.variant().fluid()).map(definition -> definition.color().orElse(0xff9966bf)).orElse(0xff9966bf);
        var waterlogged = current.getProperty("waterlogged");
        boolean wet = waterlogged != null && Boolean.TRUE.equals(current.get(waterlogged));
        bridge.publishRenderState(this, new TankRenderState(content, behavior.capacity(), glassColor, fluidColor, behavior.itemModel(), wet));
    }
    @Override public void preBlockStateChange(ImmutableBlockState state) {
        if (activation.active() && !protectedData && state.owner().value().id().equals(behavior.block().id())) {
            context.checkAccess(); publishRender(state, tank == null ? deferredContent : tank.content(0));
        }
    }

    @Override public void saveCustomData(CompoundTag tag) {
        // CE may save from a worker: export the last committed immutable blob without touching live state.
        CompoundTag foreign = foreignData;
        if (foreign != null) { foreign.entrySet().forEach(entry -> tag.put(entry.getKey(), entry.getValue().deepClone())); return; }
        Tag malformed = malformedTag;
        if (malformed != null) tag.put(DATA_KEY, malformed.deepClone());
        else tag.putByteArray(DATA_KEY, savedData.clone());
        byte[] input = savedInput, output = savedOutput;
        tag.putInt("fluidcore:glass_color", savedGlassColor);
        if (input != null) tag.putByteArray("fluidcore:input", input.clone());
        if (output != null) tag.putByteArray("fluidcore:output", output.clone());
    }

    @Override public void loadCustomData(CompoundTag tag) {
        Integer color = tag.getInt("fluidcore:glass_color"); if (color != null) glassColor = savedGlassColor = color & 0xffffff;
        savedInput = tag.getByteArray("fluidcore:input"); savedOutput = tag.getByteArray("fluidcore:output");
        if (ForeignFluidData.compound(tag)) {
            foreignData = tag.deepClone(); savedData = preserveMalformed(foreignData);
            protectedData = true; registryGeneration = bridge.registry().snapshot().generation(); return;
        }
        byte[] bytes = tag.getByteArray(DATA_KEY);
        if (bytes != null) read(bytes);
        else if (tag.containsKey(DATA_KEY)) {
            malformedTag = tag.get(DATA_KEY).deepClone();
            savedData = preserveMalformed(malformedTag);
            protectedData = true;
        }
        if ((tag.containsKey("fluidcore:input") && savedInput == null) || (tag.containsKey("fluidcore:output") && savedOutput == null)) {
            foreignData = tag.deepClone(); savedData = preserveMalformed(foreignData);
            protectedData = true; registryGeneration = bridge.registry().snapshot().generation();
        }
    }

    @Override public void loadCustomDataFromItem(Item item) {
        if (item.platformItem() instanceof ItemStack stack && stack.hasItemMeta()) {
            if (ForeignFluidData.item(stack)) {
                try { savedData = preserveMalformedPdc(stack.serializeAsBytes()); }
                catch (RuntimeException failure) { throw new StorageAccessException("Cannot preserve foreign tank item data"); }
                protectedData = true; registryGeneration = bridge.registry().snapshot().generation(); return;
            }
            var pdc = stack.getItemMeta().getPersistentDataContainer();
            Integer color = pdc.get(GLASS_COLOR, PersistentDataType.INTEGER); if (color != null) glassColor = savedGlassColor = color & 0xffffff;
            if (!pdc.has(ItemContainerTransfers.TANK_DATA_KEY) && pdc.has(ItemFluidData.DATA_KEY)) {
                var carried = bridge.containers().itemData().read(stack);
                if (carried.protectedData()) {
                    savedData = preserveMalformedPdc(stack.serializeAsBytes()); protectedData = true;
                    registryGeneration = bridge.registry().snapshot().generation(); return;
                }
                read(codec.encode(carried.stack())); return;
            }
            byte[] bytes = pdc.has(ItemContainerTransfers.TANK_DATA_KEY, PersistentDataType.BYTE_ARRAY)
                    ? pdc.get(ItemContainerTransfers.TANK_DATA_KEY, PersistentDataType.BYTE_ARRAY) : null;
            if (bytes != null) read(bytes);
            else if (pdc.has(ItemContainerTransfers.TANK_DATA_KEY)) {
                try {
                    savedData = preserveMalformedPdc(pdc.serializeToBytes());
                } catch (IOException failure) { throw new UncheckedIOException("Cannot preserve malformed tank item data", failure); }
                malformedTag = null;
                registryGeneration = bridge.registry().snapshot().generation();
                protectedData = true;
                deferredContent = FluidStack.EMPTY;
            }
        }
    }

    private void read(byte[] bytes) {
        FluidReadResult decoded = codec.decode(bytes);
        malformedTag = null;
        registryGeneration = bridge.registry().snapshot().generation();
        savedData = bytes.clone();
        protectedData = !decoded.usable();
        deferredContent = decoded.stack();
        if (tank != null && activation.active() && !protectedData && !tank.content(0).equals(deferredContent)) tank.restore(deferredContent);
        synchronizeVisual();
    }

    @Override public void onLoad() {
        activation.activate();
        if (!protectedData) try { inventory.load(decodeItem(savedInput), decodeItem(savedOutput)); }
        catch (RuntimeException malformed) {
            protectedData = true; bridge.plugin().getLogger().warning("Preserved malformed tank inventory at " + blockEntity.pos);
        }
        if (protectedData && foreignData == null && (savedInput != null || savedOutput != null) && !isInventoryArchive(savedData)) {
            savedData = preserveInventory(savedData, savedInput, savedOutput); malformedTag = null;
        }
        bridge.loadedTank(this);
        subscribeNeighborLoads();
        synchronizeVisual(); if (ticker != null) ticker.wakeUp();
    }
    private void subscribeNeighborLoads() {
        cancelNeighborSubscriptions();
        int x = blockEntity.pos.x(), y = blockEntity.pos.y(), z = blockEntity.pos.z();
        int[][] offsets = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] offset : offsets) {
            int adjacentX = x + offset[0], adjacentZ = z + offset[1];
            if ((adjacentX >> 4) == (x >> 4) && (adjacentZ >> 4) == (z >> 4)) continue;
            neighborSubscriptions.add(subscribeChunkLoad(new net.momirealms.craftengine.core.world.BlockPos(adjacentX, y, adjacentZ), () -> {
                if (!activation.active() || !bridge.running() || !blockEntity.isValid()) return;
                context.checkAccess(); bridge.hoppers().invalidate(this); hopperCooldown = 0; wakeUp();
            }));
        }
    }
    private void cancelNeighborSubscriptions() {
        neighborSubscriptions.forEach(net.momirealms.craftengine.core.world.chunk.ChunkSubscription::cancel);
        neighborSubscriptions.clear();
    }
    @Override public void onUnload() { activation.retire(); cancelNeighborSubscriptions(); if (ticker != null) ticker.sleep(); bridge.retireTank(this); }
    @Override public void onRemove() {
        if (blockEntity.world != null) bridge.preserveRemoval(location(), savedData);
        if (activation.active() && !hasProtectedData()) {
            ItemStack input = inventory.input(), output = inventory.output();
            if (input != null) location().getWorld().dropItemNaturally(location().clone().add(.5, .5, .5), input);
            if (output != null) location().getWorld().dropItemNaturally(location().clone().add(.5, .5, .5), output);
        }
        activation.retire();
        cancelNeighborSubscriptions();
        if (ticker != null) ticker.sleep(); bridge.retireTank(this);
    }

    private static byte[] preserveMalformed(Tag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(0x46434D4C); // Distinct archival envelope, deliberately never accepted as live fluid state.
            output.writeByte(tag.getId());
            tag.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private static byte[] preserveMalformedPdc(byte[] serialized) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(0x46435044); // FCPD: archival item PDC, never accepted as live fluid state.
            output.writeByte(1);
            output.writeInt(serialized.length);
            output.write(serialized);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private static boolean isInventoryArchive(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 0x46 && bytes[1] == 0x43 && bytes[2] == 0x49 && bytes[3] == 0x4e;
    }
    private static byte[] preserveInventory(byte[] fluid, byte[] input, byte[] result) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(0x4643494e); output.writeByte(1);
            writeArchivePart(output, fluid); writeArchivePart(output, input); writeArchivePart(output, result);
            output.flush(); return bytes.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private static void writeArchivePart(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes == null ? -1 : bytes.length); if (bytes != null) output.write(bytes);
    }
}
