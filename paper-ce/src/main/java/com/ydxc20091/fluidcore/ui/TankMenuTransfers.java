/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.bukkit.*;
import com.ydxc20091.fluidcore.ce.*;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Serialized menu exchanges with a durable return leg. Each real edit runs on its own owner. */
final class TankMenuTransfers {
    private final CraftEngineBridge bridge;
    TankMenuTransfers(CraftEngineBridge bridge) { this.bridge = Objects.requireNonNull(bridge); }

    CompletableFuture<Boolean> execute(TankMenuActions.Context context, TankMenuActions.Request request) {
        if (!Bukkit.isOwnedByCurrentRegion(context.player()) || !context.active().getAsBoolean())
            return CompletableFuture.completedFuture(false);
        if (request.kind() == TankMenuActions.Kind.TANK_CLICK && !request.output()
                && (request.click() == ClickType.DROP || request.click() == ClickType.CONTROL_DROP))
            return dropInput(context, request.click() == ClickType.CONTROL_DROP);
        long generation = bridge.generation(), activation = context.tank().activationVersion();
        return bridge.schedule(context.position(), () -> capture(context, generation, activation))
                .thenCompose(capture -> capture == null ? CompletableFuture.completedFuture(false)
                        : exchange(context, request, capture))
                .exceptionally(failure -> { report(failure); return false; });
    }

    private Capture capture(TankMenuActions.Context context, long generation, long activation) {
        if (!valid(context, generation, activation)) return null;
        return new Capture(encode(context.tank().inventory().input()), encode(context.tank().inventory().output()),
                context.tank().inventory().version(), generation, activation);
    }

    private CompletableFuture<Boolean> exchange(TankMenuActions.Context context, TankMenuActions.Request request, Capture capture) {
        Player player = context.player();
        RegionItemHandoff.Source source = new RegionItemHandoff.Source() {
            PlayerPlan prepared;
            public <T> CompletableFuture<T> onOwner(Supplier<T> task) { return playerOwner(player, task); }
            public byte[] prepare() {
                if (!context.active().getAsBoolean() || bridge.generation() != capture.generation()) return null;
                prepared = plan(player, request, capture);
                return prepared == null ? null : prepared.envelope(capture);
            }
            public boolean reserve() {
                if (prepared == null || !context.active().getAsBoolean() || bridge.generation() != capture.generation()) return false;
                return prepared.reserve(player);
            }
            public boolean compensate(byte[] payload) { return prepared != null && prepared.refund(player, context.active().getAsBoolean()); }
            public String address() { return "menu-player:" + player.getUniqueId(); }
        };
        RegionItemHandoff.Destination destination = new RegionItemHandoff.Destination() {
            public <T> CompletableFuture<T> onOwner(Supplier<T> task) { return bridge.schedule(context.position(), task); }
            public boolean canAccept(byte[] bytes) {
                Envelope envelope = Envelope.read(bytes);
                return matches(context, envelope);
            }
            public boolean deliver(byte[] bytes) {
                Envelope envelope = Envelope.read(bytes);
                if (!matches(context, envelope)) return false;
                ItemStack before = decode(envelope.before()), after = decode(envelope.after());
                try (FluidTransaction transaction = FluidTransaction.open()) {
                    boolean replaced = envelope.output()
                            ? context.tank().inventory().replaceOutput(before, after, transaction)
                            : context.tank().inventory().replaceInput(before, after, transaction);
                    if (!replaced) return false;
                    commit(transaction);
                    return true;
                }
            }
            public CompletableFuture<Boolean> afterDelivery(byte[] bytes) {
                // The envelope includes every source and target value before the first real mutation.
                // It remains durable until the owner has received the cursor/hotbar return value.
                Envelope envelope = Envelope.read(bytes);
                return playerOwner(player, () -> finishPlayer(context, envelope));
            }
            public String address() { return "menu-tank:" + TankMenuTransfers.address(context.position()); }
        };
        return bridge.handoffs().transfer(source, destination).thenApply(this::delivered);
    }

    private boolean matches(TankMenuActions.Context context, Envelope envelope) {
        if (!valid(context, envelope.generation(), envelope.activation())
                || context.tank().inventory().version() != envelope.version()) return false;
        ItemStack current = envelope.output() ? context.tank().inventory().output() : context.tank().inventory().input();
        return Objects.equals(current, decode(envelope.before()));
    }

    private boolean valid(TankMenuActions.Context context, long generation, long activation) {
        Location position = context.position();
        return bridge.running() && bridge.generation() == generation && context.tank().sameActivation(activation)
                && position.getWorld().isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4)
                && bridge.resolver().controller(position).orElse(null) == context.tank() && !context.tank().hasProtectedData();
    }

    private PlayerPlan plan(Player player, TankMenuActions.Request request, Capture capture) {
        boolean output = request.output();
        ItemStack target = decode(output ? capture.output() : capture.input());
        CursorParticipant cursor = new CursorParticipant(player, BukkitStorageContext.entity(player));
        ItemStack carried = cursor.current();
        if (request.kind() == TankMenuActions.Kind.DRAG)
            return dragPlan(player, request, target, carried);
        if (request.kind() == TankMenuActions.Kind.SHIFT_TO_INPUT) {
            int slot = request.storageSlot();
            if (slot < 0 || slot >= 36) return null;
            ItemStack original = copy(player.getInventory().getItem(slot));
            int amount = TankClickPlan.inputFit(target, original);
            if (amount == 0) return null;
            return new PlayerPlan(SourceKind.SLOT, slot, original, remainder(original, amount), List.of(),
                    false, target, merge(target, portion(original, amount)));
        }
        if (request.kind() != TankMenuActions.Kind.TANK_CLICK) return null;
        if (output) {
            if (carried != null || target == null) return null;
            return new PlayerPlan(SourceKind.CURSOR, -1, null, target, List.of(), true, target, null);
        }
        ClickType click = request.click();
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            int amount = TankClickPlan.storageFit(target, player.getInventory().getStorageContents(), player.getInventory().getMaxStackSize());
            if (amount == 0) return null;
            return new PlayerPlan(SourceKind.BACKPACK, -1, null, portion(target, amount), List.of(),
                    false, target, remainder(target, amount));
        }
        if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) {
            int slot = click == ClickType.SWAP_OFFHAND ? 40 : request.hotbarButton();
            if (!(slot >= 0 && slot < 9) && slot != 40) return null;
            ItemStack original = copy(player.getInventory().getItem(slot));
            var change = TankClickPlan.swap(target, original, Integer.MAX_VALUE, player.getInventory().getMaxStackSize());
            if (change == null) return null;
            return new PlayerPlan(SourceKind.SLOT, slot, original, change.cursor(), List.of(), false, target, change.slot());
        }
        if (click != ClickType.LEFT && click != ClickType.RIGHT) return null;
        var change = TankClickPlan.normal(target, carried, click == ClickType.RIGHT, Integer.MAX_VALUE);
        return change == null ? null : new PlayerPlan(SourceKind.CURSOR, -1, carried, change.cursor(), List.of(), false, target, change.slot());
    }

    private PlayerPlan dragPlan(Player player, TankMenuActions.Request request, ItemStack target, ItemStack cursor) {
        if (cursor == null || !request.includesInput()) return null;
        if (request.playerStorageSlots().stream().anyMatch(slot -> slot == null || slot < 0 || slot >= 36)) return null;
        List<Integer> selected = request.playerStorageSlots().stream().distinct().toList();
        List<SlotChange> changes = new ArrayList<>();
        List<ItemStack> originals = new ArrayList<>(); List<Integer> maximums = new ArrayList<>();
        originals.add(target); maximums.add(Integer.MAX_VALUE);
        for (int slot : selected) { originals.add(copy(player.getInventory().getItem(slot))); maximums.add(player.getInventory().getMaxStackSize()); }
        var distribution = TankClickPlan.drag(cursor, originals, maximums, request.single());
        if (distribution == null || distribution.moved() < 1) return null;
        for (int index = 0; index < selected.size(); index++) {
            ItemStack before = originals.get(index + 1), after = distribution.slots().get(index + 1);
            if (!Objects.equals(before, after)) changes.add(new SlotChange(selected.get(index), before, after));
        }
        return new PlayerPlan(SourceKind.CURSOR, -1, cursor, distribution.cursor(), changes, false, target, distribution.slots().getFirst());
    }

    private boolean finishPlayer(TankMenuActions.Context context, Envelope envelope) {
        Player player = context.player();
        ItemStack item = decode(envelope.sourceAfter());
        if (item == null) return true;
        boolean active = player.isOnline() && player.isValid() && !player.isDead() && context.active().getAsBoolean();
        var storageContext = BukkitStorageContext.entity(player);
        try (FluidTransaction transaction = FluidTransaction.open()) {
            if (active && envelope.kind() == SourceKind.CURSOR) {
                var cursor = new CursorParticipant(player, storageContext);
                if (cursor.current() == null && cursor.put(item, transaction)) { commit(transaction); return true; }
            } else if (active && envelope.kind() == SourceKind.SLOT) {
                var slot = new PlayerSlotParticipant(player, envelope.slot(), storageContext);
                if (slot.replace(null, item, transaction)) { commit(transaction); return true; }
            }
            var inventory = new InventoryParticipant(player.getInventory(), storageContext);
            if (inventory.put(item, transaction)) { commit(transaction); return true; }
        }
        return drop(player, item);
    }

    private CompletableFuture<Boolean> dropInput(TankMenuActions.Context context, boolean entire) {
        if (new CursorParticipant(context.player(), BukkitStorageContext.entity(context.player())).current() != null)
            return CompletableFuture.completedFuture(false);
        long generation = bridge.generation(), activation = context.tank().activationVersion();
        RegionItemHandoff.Source source = new RegionItemHandoff.Source() {
            ItemStack expected; int amount;
            public <T> CompletableFuture<T> onOwner(Supplier<T> task) { return bridge.schedule(context.position(), task); }
            public byte[] prepare() {
                if (!valid(context, generation, activation)) return null;
                expected = context.tank().inventory().input();
                amount = entire ? count(expected) : Math.min(1, count(expected));
                return amount == 0 ? null : encode(portion(expected, amount));
            }
            public boolean reserve() {
                if (!valid(context, generation, activation) || !Objects.equals(expected, context.tank().inventory().input())) return false;
                try (FluidTransaction transaction = FluidTransaction.open()) {
                    if (context.tank().inventory().extractInput(amount, transaction) == null) return false;
                    commit(transaction); return true;
                }
            }
            public boolean compensate(byte[] bytes) {
                if (!valid(context, generation, activation)) return false;
                try (FluidTransaction transaction = FluidTransaction.open()) {
                    if (!context.tank().inventory().insertInput(decode(bytes), transaction)) return false;
                    commit(transaction); return true;
                }
            }
            public String address() { return "menu-drop-source:" + TankMenuTransfers.address(context.position()); }
        };
        RegionItemHandoff.Destination destination = new RegionItemHandoff.Destination() {
            public <T> CompletableFuture<T> onOwner(Supplier<T> task) { return playerOwner(context.player(), task); }
            public boolean canAccept(byte[] bytes) { return dropAllowedOnPlayer(context); }
            public boolean deliver(byte[] bytes) { return dropAllowedOnPlayer(context) && drop(context.player(), decode(bytes)); }
            public String address() { return "menu-player-drop:" + context.player().getUniqueId(); }
        };
        return bridge.handoffs().transfer(source, destination).thenApply(this::delivered);
    }

    private boolean dropAllowedOnPlayer(TankMenuActions.Context context) {
        Player player = context.player();
        return Bukkit.isOwnedByCurrentRegion(player) && bridge.running()
                && player.isOnline() && player.isValid() && !player.isDead()
                && context.active().getAsBoolean()
                && new CursorParticipant(player, BukkitStorageContext.entity(player)).current() == null;
    }

    private boolean drop(Player player, ItemStack item) {
        if (item == null || !player.isOnline() || !player.isValid() || player.isDead()) return false;
        org.bukkit.entity.Item entity = player.getWorld().dropItem(player.getLocation(), item.clone());
        try {
            entity.setThrower(player.getUniqueId()); entity.setPickupDelay(40);
            PlayerDropItemEvent event = new PlayerDropItemEvent(player, entity);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled() || !entity.isValid() || !Objects.equals(item, entity.getItemStack())) { entity.remove(); return false; }
            return true;
        } catch (RuntimeException failure) { entity.remove(); throw failure; }
    }

    private boolean delivered(RegionItemHandoff.Outcome outcome) {
        if (outcome == RegionItemHandoff.Outcome.RECOVERY_REQUIRED)
            bridge.plugin().getLogger().warning("A menu exchange requires recovery; its complete payload remains in handoff-recovery");
        return outcome == RegionItemHandoff.Outcome.DELIVERED;
    }
    private void report(Throwable failure) { bridge.plugin().getLogger().log(Level.FINE, "Menu exchange rejected", failure); }
    private <T> CompletableFuture<T> playerOwner(Player player, Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            if (player.getScheduler().run(bridge.plugin(), scheduled -> {
                try { future.complete(task.get()); } catch (Throwable failure) { future.completeExceptionally(failure); }
            }, () -> future.completeExceptionally(new StorageAccessException("Menu player retired"))) == null)
                future.completeExceptionally(new StorageAccessException("Menu player unavailable"));
        } catch (RuntimeException stopped) { future.completeExceptionally(stopped); }
        return future;
    }

    private enum SourceKind { CURSOR, SLOT, BACKPACK }
    private record Capture(byte[] input, byte[] output, long version, long generation, long activation) {}
    private record SlotChange(int slot, ItemStack before, ItemStack after) {}
    private final class PlayerPlan {
        final SourceKind kind; final int slot; final ItemStack before, after, targetBefore, targetAfter;
        final List<SlotChange> changes; final boolean output;
        PlayerPlan(SourceKind kind, int slot, ItemStack before, ItemStack after, List<SlotChange> changes,
                   boolean output, ItemStack targetBefore, ItemStack targetAfter) {
            this.kind = kind; this.slot = slot; this.before = copy(before); this.after = copy(after);
            this.changes = List.copyOf(changes); this.output = output; this.targetBefore = copy(targetBefore); this.targetAfter = copy(targetAfter);
        }
        byte[] envelope(Capture capture) {
            return new Envelope(kind, slot, encode(before), encode(after),
                    changes.stream().map(change -> new SlotBytes(change.slot(), encode(change.before()), encode(change.after()))).toList(),
                    output, encode(targetBefore), encode(targetAfter), capture.version(), capture.generation(), capture.activation()).write();
        }
        boolean reserve(Player player) {
            var storageContext = BukkitStorageContext.entity(player);
            try (FluidTransaction transaction = FluidTransaction.open()) {
                if (kind == SourceKind.CURSOR) {
                    if (!new CursorParticipant(player, storageContext).replace(before, null, transaction)) return false;
                } else if (kind == SourceKind.SLOT) {
                    if (!new PlayerSlotParticipant(player, slot, storageContext).replace(before, null, transaction)) return false;
                }
                var inventory = new InventoryParticipant(player.getInventory(), storageContext);
                for (SlotChange change : changes) {
                    if (!Objects.equals(change.before(), copy(player.getInventory().getItem(change.slot())))) return false;
                    if (change.before() != null && !inventory.take(change.slot(), change.before(), change.before().getAmount(), transaction)) return false;
                    if (change.after() != null && !inventory.putAt(change.slot(), change.after(), transaction)) return false;
                }
                commit(transaction); return true;
            }
        }
        boolean refund(Player player, boolean active) {
            var storageContext = BukkitStorageContext.entity(player);
            try (FluidTransaction transaction = FluidTransaction.open()) {
                var inventory = new InventoryParticipant(player.getInventory(), storageContext);
                for (SlotChange change : changes) {
                    if (!Objects.equals(change.after(), copy(player.getInventory().getItem(change.slot())))) return false;
                    if (change.after() != null && !inventory.take(change.slot(), change.after(), change.after().getAmount(), transaction)) return false;
                    if (change.before() != null && !inventory.putAt(change.slot(), change.before(), transaction)) return false;
                }
                if (before != null) {
                    boolean restored = kind == SourceKind.CURSOR && active
                            ? new CursorParticipant(player, storageContext).put(before, transaction)
                            : kind == SourceKind.SLOT && active && new PlayerSlotParticipant(player, slot, storageContext).replace(null, before, transaction);
                    if (!restored && !inventory.put(before, transaction)) return false;
                }
                commit(transaction); return true;
            }
        }
    }

    private record SlotBytes(int slot, byte[] before, byte[] after) {}
    /** The journal is plain bytes, including drag destinations and both sides of a swap. */
    private record Envelope(SourceKind kind, int slot, byte[] sourceBefore, byte[] sourceAfter, List<SlotBytes> changes,
                            boolean output, byte[] before, byte[] after, long version, long generation, long activation) {
        byte[] write() {
            try {
                var bytes = new ByteArrayOutputStream();
                var out = new DataOutputStream(bytes);
                out.writeInt(0x46434d31); out.writeByte(kind.ordinal()); out.writeInt(slot);
                part(out, sourceBefore); part(out, sourceAfter); out.writeInt(changes.size());
                for (SlotBytes change : changes) { out.writeInt(change.slot()); part(out, change.before()); part(out, change.after()); }
                out.writeBoolean(output); part(out, before); part(out, after);
                out.writeLong(version); out.writeLong(generation); out.writeLong(activation); out.flush();
                return bytes.toByteArray();
            } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
        }
        static Envelope read(byte[] bytes) {
            try {
                if (bytes.length > 2_097_152) throw new IOException("Oversized menu payload");
                var in = new DataInputStream(new ByteArrayInputStream(bytes));
                if (in.readInt() != 0x46434d31) throw new IOException("Unknown menu payload");
                int kind = in.readUnsignedByte(); if (kind >= SourceKind.values().length) throw new IOException("Invalid menu source");
                int slot = in.readInt(); byte[] sourceBefore = part(in), sourceAfter = part(in);
                int size = in.readInt(); if (size < 0 || size > 36) throw new IOException("Invalid drag size");
                List<SlotBytes> changes = new ArrayList<>(size);
                for (int index = 0; index < size; index++) { int selected = in.readInt(); if (selected < 0 || selected >= 36) throw new IOException("Invalid drag slot"); changes.add(new SlotBytes(selected, part(in), part(in))); }
                boolean output = in.readBoolean(); byte[] before = part(in), after = part(in);
                Envelope value = new Envelope(SourceKind.values()[kind], slot, sourceBefore, sourceAfter, List.copyOf(changes), output, before, after,
                        in.readLong(), in.readLong(), in.readLong());
                if (in.read() != -1) throw new IOException("Trailing menu payload");
                return value;
            } catch (IOException malformed) { throw new UncheckedIOException(malformed); }
        }
        private static void part(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
        private static byte[] part(DataInputStream in) throws IOException {
            int size = in.readInt(); if (size < 0 || size > 2_097_152 || size > in.available()) throw new IOException("Invalid menu item length");
            return in.readNBytes(size);
        }
    }

    private static byte[] encode(ItemStack item) { return empty(item) ? new byte[0] : BukkitItemManager.instance().wrap(item.clone()).toBytes(); }
    private static ItemStack decode(byte[] bytes) {
        if (bytes.length == 0) return null;
        ItemStack item = (ItemStack) Item.fromBytes(bytes).platformItem();
        if (empty(item) || item.getAmount() > item.getMaxStackSize()) throw new IllegalArgumentException("Invalid menu item amount");
        return item.clone();
    }
    private static ItemStack merge(ItemStack current, ItemStack addition) {
        if (empty(addition)) return copy(current);
        ItemStack value = empty(current) ? addition.clone() : current.clone();
        value.setAmount(count(current) + addition.getAmount()); return value;
    }
    private static ItemStack portion(ItemStack item, int amount) { if (empty(item) || amount < 1) return null; ItemStack value = item.clone(); value.setAmount(amount); return value; }
    private static ItemStack remainder(ItemStack item, int removed) { return portion(item, count(item) - removed); }
    private static int count(ItemStack item) { return empty(item) ? 0 : item.getAmount(); }
    private static boolean empty(ItemStack item) { return item == null || item.getAmount() < 1 || item.getType().isAir(); }
    private static ItemStack copy(ItemStack item) { return empty(item) ? null : item.clone(); }
    private static void commit(FluidTransaction transaction) { try { transaction.commit(); } catch (RuntimeException failure) { if (!transaction.isCommitted()) throw failure; } }
    private static String address(Location position) { return position.getWorld().getUID() + ":" + position.getBlockX() + ":" + position.getBlockY() + ":" + position.getBlockZ(); }
}
