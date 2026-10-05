/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.ce.CraftEngineBridge;
import com.ydxc20091.fluidcore.ce.FluidTankController;
import com.ydxc20091.fluidcore.ce.TankProcessor;
import com.ydxc20091.fluidcore.ce.TankRecipeNavigation;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Bukkit chest backend for clients predating Sparrow's 1.21.4 packet support. */
final class LegacyTankMenu implements Listener, AutoCloseable {
    private record Display(FluidTankController tank, ItemStack input, ItemStack output, FluidStack fluid,
                           long capacity, boolean protectedData, TankProcessor.Progress progress,
                           long activation, long generation) {}
    private final CraftEngineBridge bridge;
    private final TankMenuActions actions;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<FluidTankController, Set<Session>> viewers = new ConcurrentHashMap<>();
    private final DeferredMenuOpen<UUID, Inventory> pending = new DeferredMenuOpen<>();
    private final AtomicBoolean registered = new AtomicBoolean();

    LegacyTankMenu(CraftEngineBridge bridge) { this.bridge = bridge; actions = new TankMenuActions(bridge); }

    void openAsync(Player player, Location location) {
        Location position = location.clone();
        AtomicReference<DeferredMenuOpen.Request<UUID, Inventory>> request = new AtomicReference<>();
        onPlayer(player, () -> {
            if (!available(player, position)) return false;
            request.set(pending.begin(player.getUniqueId(), player.getOpenInventory().getTopInventory()));
            return true;
        }).thenCompose(valid -> !valid ? CompletableFuture.completedFuture(null) : bridge.schedule(position,
                () -> bridge.resolver().controller(position).map(this::capture).orElse(null)))
                .thenCompose(display -> display == null ? CompletableFuture.completedFuture(false) : onPlayer(player,
                        () -> pending.consume(request.get(), player.getOpenInventory().getTopInventory()) && open(player, position, display)))
                .whenComplete((opened, failure) -> {
                    pending.cancel(request.get());
                    if (failure != null) bridge.plugin().getLogger().log(Level.WARNING, "Cannot open legacy tank menu", failure);
                });
    }

    private boolean open(Player player, Location position, Display display) {
        if (!available(player, position) || !bridge.isLoadedTank(display.tank) || !display.tank.sameActivation(display.activation)
                || bridge.generation() != display.generation) return false;
        if (registered.compareAndSet(false, true)) Bukkit.getPluginManager().registerEvents(this, bridge.plugin());
        Session session = new Session(player, position, display);
        session.inventory = Bukkit.createInventory(session, TankMenuLayout.SIZE, session.theme.title(player.getLocale()));
        render(session, display);
        Session old = sessions.put(player.getUniqueId(), session);
        if (old != null) remove(old);
        viewers.computeIfAbsent(display.tank, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        try {
            if (player.openInventory(session.inventory) == null || player.getOpenInventory().getTopInventory() != session.inventory) {
                remove(session); return false;
            }
            return true;
        } catch (RuntimeException failure) { remove(session); throw failure; }
    }

    private Display capture(FluidTankController tank) {
        var storage = tank.storage();
        return new Display(tank, tank.inventory().input(), tank.inventory().output(), storage.content(0), storage.capacity(0),
                tank.hasProtectedData(), bridge.processor() == null ? null : bridge.processor().progress(tank),
                tank.activationVersion(), bridge.generation());
    }

    void changed(FluidTankController tank) {
        Set<Session> watching = viewers.get(tank);
        if (watching == null || watching.isEmpty()) return;
        Display display = capture(tank);
        for (Session session : watching) {
            session.latest = display;
            if (!session.queued.compareAndSet(false, true)) continue;
            if (session.player.getScheduler().run(bridge.plugin(), task -> {
                session.queued.set(false);
                if (valid(session)) render(session, session.latest);
                else {
                    remove(session);
                    if (session.player.isOnline() && session.player.getOpenInventory().getTopInventory() == session.inventory) session.player.closeInventory();
                }
            }, () -> { session.queued.set(false); remove(session); }) == null) {
                session.queued.set(false); remove(session);
            }
        }
    }

    void retired(FluidTankController tank) {
        Set<Session> watching = viewers.remove(tank);
        if (watching != null) for (Session session : watching) {
            remove(session);
            onPlayer(session.player, () -> {
                if (session.player.getOpenInventory().getTopInventory() == session.inventory) session.player.closeInventory();
                return true;
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || event.getView().getTopInventory() != session.inventory) return;
        if (session.busy.get() || !valid(session)) { event.setCancelled(true); return; }
        int raw = event.getRawSlot();
        if (event.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            defer(session, () -> actions.collectPlayer(context(session)), java.util.stream.IntStream.range(0, 36).toArray());
            return;
        }
        if (TankMenuLayout.playerSlot(raw)) {
            if (!event.isShiftClick()) return;
            event.setCancelled(true);
            defer(session, () -> actions.shiftToInput(context(session), TankMenuLayout.playerStorageSlot(raw)), TankMenuLayout.playerStorageSlot(raw));
        } else if (raw >= 0 && raw < TankMenuLayout.SIZE) {
            event.setCancelled(true);
            if (raw == TankMenuLayout.INPUT || raw == TankMenuLayout.OUTPUT) {
                int selected = event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND ? 40
                        : event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY ? event.getHotbarButton() : -1;
                defer(session, () -> actions.click(context(session), raw == TankMenuLayout.OUTPUT, event.getClick(), event.getHotbarButton()),
                        selected >= 0 ? new int[]{selected} : new int[0]);
            }
            else if (raw == TankMenuLayout.FLUID) defer(session, () -> navigate(session));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || event.getView().getTopInventory() != session.inventory) return;
        if (session.busy.get() || !valid(session)) { event.setCancelled(true); return; }
        if (event.getRawSlots().stream().noneMatch(slot -> slot < TankMenuLayout.SIZE)) return;
        event.setCancelled(true);
        var targets = TankMenuLayout.dragTargets(event.getRawSlots());
        if (targets != null) defer(session, () -> actions.drag(context(session),
                event.getType() == org.bukkit.event.inventory.DragType.SINGLE, targets.playerSlots(), targets.includesInput()),
                targets.playerSlots().stream().mapToInt(Integer::intValue).toArray());
    }

    @EventHandler
    public void closed(InventoryCloseEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && event.getInventory() == session.inventory) remove(session);
    }

    private void defer(Session session, Supplier<CompletableFuture<Boolean>> operation, int... sourceSlots) {
        if (!session.busy.compareAndSet(false, true)) return;
        Display displayed = session.displayed;
        if (displayed == null) { session.busy.set(false); return; }
        LegacyClickSnapshot observed = LegacyClickSnapshot.capture(session.player.getItemOnCursor(), session.player.getInventory()::getItem,
                displayed.input, displayed.output, sourceSlots);
        if (session.player.getScheduler().runDelayed(bridge.plugin(), task -> {
            if (!valid(session) || !observed.matches(session.player.getItemOnCursor(), session.player.getInventory()::getItem)
                    || !observed.matchesTank(session.latest.input, session.latest.output)) { session.busy.set(false); return; }
            CompletableFuture<Boolean> result;
            try { result = operation.get(); }
            catch (RuntimeException failure) { result = CompletableFuture.failedFuture(failure); }
            result.whenComplete((changed, failure) -> {
                session.busy.set(false);
                if (failure != null) bridge.plugin().getLogger().log(Level.WARNING, "Legacy tank interaction failed", failure);
                if (bridge.running()) bridge.schedule(session.position, () -> {
                    if (session.tank.sameActivation(session.activation)
                            && bridge.resolver().controller(session.position).orElse(null) == session.tank) changed(session.tank);
                    return null;
                });
            });
        }, () -> session.busy.set(false), 1) == null) session.busy.set(false);
    }

    private TankMenuActions.Context context(Session session) {
        return new TankMenuActions.Context(session.player, session.tank, session.position, () -> valid(session));
    }

    private void render(Session session, Display display) {
        String locale = session.player.getLocale();
        if (session.locale == null || !session.locale.equals(locale) || session.messages != bridge.messages()) {
            session.locale = locale; session.messages = bridge.messages();
            ItemStack frame = session.theme.border();
            for (int slot = 0; slot < TankMenuLayout.SIZE; slot++) if (TankMenuLayout.decoration(slot)) update(session, slot, frame);
        }
        update(session, TankMenuLayout.INPUT, display.input == null ? session.theme.emptyInput(locale) : display.input);
        update(session, TankMenuLayout.OUTPUT, display.output == null ? session.theme.emptyOutput(locale) : display.output);
        update(session, TankMenuLayout.FLUID, session.theme.fluid(display.fluid, display.capacity, display.protectedData, locale));
        update(session, TankMenuLayout.PROGRESS, session.theme.progress(display.progress == null ? 0
                : TankMenuLayout.progressStage(display.progress.elapsed(), display.progress.total()), display.progress == null ? 0 : display.progress.percent(), locale));
        update(session, TankMenuLayout.BUCKETS, session.theme.capacity(true, display.fluid.amount(), locale));
        update(session, TankMenuLayout.BOTTLES, session.theme.capacity(false, display.fluid.amount(), locale));
        session.displayed = display;
    }

    private void update(Session session, int slot, ItemStack value) {
        if (session.rendered.update(slot, value)) session.inventory.setItem(slot, session.rendered.value(slot));
    }

    private boolean available(Player player, Location position) {
        return bridge.running() && Bukkit.isOwnedByCurrentRegion(player) && player.isOnline() && player.isValid() && !player.isDead()
                && player.getGameMode() != GameMode.SPECTATOR && player.getWorld() == position.getWorld()
                && player.getLocation().distanceSquared(position.clone().add(.5, .5, .5)) <= 36
                && net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine.instance().antiGriefProvider().test(player,
                net.momirealms.craftengine.libraries.antigrieflib.Flag.OPEN_CONTAINER, position);
    }

    private boolean valid(Session session) {
        return sessions.get(session.player.getUniqueId()) == session && session.player.getOpenInventory().getTopInventory() == session.inventory
                && available(session.player, session.position) && bridge.generation() == session.generation
                && bridge.isLoadedTank(session.tank) && session.tank.sameActivation(session.activation);
    }

    private CompletableFuture<Boolean> navigate(Session session) {
        var navigation = bridge.recipeNavigation();
        if (navigation == null || !valid(session)) return CompletableFuture.completedFuture(false);
        Return destination = new Return(session, navigation);
        return destination.valid().thenCompose(valid -> !valid ? CompletableFuture.completedFuture(false) : onPlayer(session.player, () -> {
            if (!valid(session)) { destination.cancel(); return false; }
            session.player.closeInventory();
            navigation.navigator().open(session.player, session.position.clone(), destination);
            return true;
        }));
    }

    private final class Return implements TankRecipeNavigation.ReturnHandle {
        private final Session origin; private final TankRecipeNavigation.Entry navigation;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        Return(Session origin, TankRecipeNavigation.Entry navigation) { this.origin = origin; this.navigation = navigation; }
        private boolean playerValid() {
            return !cancelled.get() && available(origin.player, origin.position) && bridge.generation() == origin.generation
                    && bridge.recipeNavigation() == navigation && bridge.isLoadedTank(origin.tank)
                    && origin.tank.sameActivation(origin.activation)
                    && (!(navigation.owner() instanceof org.bukkit.plugin.Plugin plugin) || plugin.isEnabled());
        }
        private boolean tankValid() {
            return !cancelled.get() && bridge.generation() == origin.generation && bridge.recipeNavigation() == navigation
                    && origin.tank.sameActivation(origin.activation) && !origin.tank.hasProtectedData()
                    && bridge.resolver().controller(origin.position).orElse(null) == origin.tank;
        }
        @Override public CompletableFuture<Boolean> valid() {
            return onPlayer(origin.player, this::playerValid).thenCompose(valid -> !valid ? CompletableFuture.completedFuture(false)
                    : bridge.schedule(origin.position, this::tankValid)).thenCompose(valid -> !valid ? CompletableFuture.completedFuture(false)
                    : onPlayer(origin.player, this::playerValid));
        }
        @Override public CompletableFuture<Boolean> reopen() {
            AtomicReference<Inventory> previous = new AtomicReference<>();
            return onPlayer(origin.player, () -> {
                if (!playerValid() || origin.player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) return false;
                previous.set(origin.player.getOpenInventory().getTopInventory()); return true;
            }).thenCompose(valid -> !valid ? CompletableFuture.completedFuture(null) : bridge.schedule(origin.position,
                    () -> tankValid() ? capture(origin.tank) : null)).thenCompose(display -> display == null
                    ? CompletableFuture.completedFuture(false) : onPlayer(origin.player, () -> {
                if (!playerValid() || origin.player.getOpenInventory().getTopInventory() != previous.get()) return false;
                boolean opened = open(origin.player, origin.position, display); cancel(); return opened;
            }));
        }
        @Override public void cancel() { cancelled.set(true); }
    }

    private CompletableFuture<Boolean> onPlayer(Player player, Supplier<Boolean> operation) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Runnable run = () -> { try { result.complete(operation.get()); } catch (Throwable failure) { result.completeExceptionally(failure); } };
        if (Bukkit.isOwnedByCurrentRegion(player)) run.run();
        else if (!bridge.running() || player.getScheduler().run(bridge.plugin(), task -> run.run(), () -> result.complete(false)) == null) result.complete(false);
        return result;
    }

    private void remove(Session session) {
        sessions.remove(session.player.getUniqueId(), session);
        viewers.computeIfPresent(session.tank, (tank, watching) -> { watching.remove(session); return watching.isEmpty() ? null : watching; });
    }
    @Override public void close() {
        try {
            for (Session session : sessions.values()) {
                Runnable close = () -> {
                    if (session.player.getOpenInventory().getTopInventory() == session.inventory) session.player.closeInventory();
                };
                try {
                    if (Bukkit.isOwnedByCurrentRegion(session.player)) close.run();
                    else session.player.getScheduler().run(bridge.plugin(), task -> close.run(), () -> {});
                } catch (RuntimeException failure) {
                    bridge.plugin().getLogger().log(bridge.plugin().isEnabled() ? Level.WARNING : Level.FINE,
                            "Player-owner closure unavailable; tank menu session retired", failure);
                }
            }
        } finally {
            HandlerList.unregisterAll(this);
            pending.clear(); sessions.clear(); viewers.clear(); registered.set(false);
        }
    }

    private final class Session implements InventoryHolder {
        final Player player; final Location position; final FluidTankController tank; final long activation, generation;
        final TankMenuTheme theme; final AtomicBoolean queued = new AtomicBoolean(), busy = new AtomicBoolean();
        final TankMenuRenderCache<ItemStack> rendered = new TankMenuRenderCache<>(TankMenuLayout.SIZE, ItemStack::clone);
        volatile Display latest; Display displayed; Inventory inventory; String locale; Object messages;
        Session(Player player, Location position, Display display) {
            this.player = player; this.position = position.clone(); tank = display.tank;
            activation = display.activation; generation = display.generation; latest = display;
            theme = new TankMenuTheme(bridge, tank.menuSettings());
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}
