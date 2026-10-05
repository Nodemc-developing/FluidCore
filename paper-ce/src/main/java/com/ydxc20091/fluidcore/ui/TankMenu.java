package com.ydxc20091.fluidcore.ui;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.bukkit.*;
import com.ydxc20091.fluidcore.ce.*;
import net.kyori.adventure.text.Component;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.item.click.ItemDrag;
import net.momirealms.sparrow.ui.item.provider.ItemProvider;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.ClickType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Owner-confined real slots behind a virtual Sparrow window; updates publish immutable display copies. */
public final class TankMenu implements AutoCloseable {
    private record Display(ItemStack input, ItemStack output, FluidStack fluid, long capacity, boolean protectedData, TankProcessor.Progress progress, long version, long activation, long generation) {}
    private record Opening(FluidTankController tank, Location position, Display display) {}
    private record Status(FluidStack fluid, long capacity, boolean protectedData, int progress, int stage, String locale) {}
    private final CraftEngineBridge bridge;
    private final TankMenuActions actions;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<FluidTankController, Set<Session>> viewers = new ConcurrentHashMap<>();
    private final DeferredMenuOpen<UUID, org.bukkit.inventory.Inventory> pendingOpen = new DeferredMenuOpen<>();
    private final LegacyTankMenu legacy;
    public TankMenu(CraftEngineBridge bridge) { this.bridge = bridge; this.actions = new TankMenuActions(bridge); this.legacy = new LegacyTankMenu(bridge); }
    public void open(Player player, FluidTankController tank) {
        if (!com.ydxc20091.fluidcore.bukkit.ServerCapabilities.modernItems()) { legacy.openAsync(player, tank.location()); return; }
        if (!Bukkit.isOwnedByCurrentRegion(player) || !Bukkit.isOwnedByCurrentRegion(tank.location())) return;
        openWindow(player, tank, tank.location(), capture(tank));
    }
    public void openAsync(Player player, Location position) {
        if (!com.ydxc20091.fluidcore.bukkit.ServerCapabilities.modernItems()) { legacy.openAsync(player, position); return; }
        Location target = position.clone();
        var request = new java.util.concurrent.atomic.AtomicReference<DeferredMenuOpen.Request<UUID, org.bukkit.inventory.Inventory>>();
        onPlayer(player, () -> {
            if (!bridge.running() || !player.isOnline() || !player.isValid() || player.isDead()) return false;
            request.set(pendingOpen.begin(player.getUniqueId(), player.getOpenInventory().getTopInventory()));
            return true;
        }).thenCompose(valid -> !valid ? CompletableFuture.completedFuture(null) : bridge.schedule(target, () -> {
            var tank = bridge.resolver().controller(target).orElse(null);
            return tank == null ? null : new Opening(tank, target, capture(tank));
        })).thenCompose(opening -> opening == null ? CompletableFuture.completedFuture(false) : onPlayerWindow(player, () -> {
            if (!player.isOnline() || !player.isValid() || player.isDead() || player.getWorld() != target.getWorld()
                    || player.getLocation().distanceSquared(target.clone().add(.5,.5,.5)) > 36
                    || !pendingOpen.consume(request.get(), player.getOpenInventory().getTopInventory())) return CompletableFuture.completedFuture(false);
            return openWindow(player, opening.tank, opening.position, opening.display);
        })).whenComplete((ignored, failure) -> pendingOpen.cancel(request.get()));
    }
    private CompletableFuture<Boolean> openWindow(Player player, FluidTankController tank, Location position, Display initial) {
        boolean allowed = bridge.running() && bridge.generation() == initial.generation && bridge.isLoadedTank(tank) && tank.sameActivation(initial.activation)
                && player.isOnline() && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine.instance().antiGriefProvider().test(player,
                net.momirealms.craftengine.libraries.antigrieflib.Flag.OPEN_CONTAINER, position);
        return MenuOpening.open(allowed, () -> {
        pendingOpen.cancel(player.getUniqueId());
        FluidUiRuntime.initialize(bridge.plugin());
        Session session = new Session(player, tank, position, initial.activation, initial.generation, new TankMenuTheme(bridge, tank.menuSettings()));
        Pane upper = Pane.empty(TankMenuLayout.WIDTH, TankMenuLayout.ROWS), lower = Pane.empty(9, 4);
        session.upper = upper; session.lower = lower;
        session.window = Window.builder(upper).setLowerPane(lower).setTitle(session.theme.title(player.getLocale()))
                .addCloseHandler((window, reason) -> remove(session)).build(player);
        Session old = sessions.put(player.getUniqueId(), session); if (old != null) { removeViewer(old); old.window.close(); }
        viewers.computeIfAbsent(tank, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        session.latest = initial;
        bind(session);
        render(session, initial);
        return session.window.open().whenComplete((result, failure) -> {
            if (failure != null || result == Window.OpenResult.VIEWER_UNAVAILABLE) remove(session);
        });
        });
    }
    public void changed(FluidTankController tank) {
        if (!com.ydxc20091.fluidcore.bukkit.ServerCapabilities.modernItems()) { legacy.changed(tank); return; }
        Set<Session> watching = viewers.get(tank);
        if (watching == null || watching.isEmpty()) return;
        Display display = capture(tank);
        for (Session session : watching) {
            session.latest = display;
            if (!session.queued.compareAndSet(false, true)) continue;
            session.player.getScheduler().run(bridge.plugin(), task -> {
                session.queued.set(false);
                if (validPlayer(session) && session.latest.activation == session.activation) render(session, session.latest);
            }, () -> { session.queued.set(false); remove(session); });
        }
    }
    public void retired(FluidTankController tank) {
        if (!com.ydxc20091.fluidcore.bukkit.ServerCapabilities.modernItems()) { legacy.retired(tank); return; }
        Set<Session> watching = viewers.remove(tank);
        if (watching != null) for (Session session : watching) if (sessions.remove(session.player.getUniqueId(), session))
            session.player.getScheduler().run(bridge.plugin(), task -> session.window.close(), () -> {});
    }
    private Display capture(FluidTankController tank) {
        FluidStorage storage = tank.storage(); FluidStack content = storage.content(0);
        var progress = bridge.processor() == null ? null : bridge.processor().progress(tank);
        return new Display(tank.inventory().input(), tank.inventory().output(), content, storage.capacity(0), tank.hasProtectedData(), progress, tank.inventory().version(), tank.activationVersion(), bridge.generation());
    }
    private void bind(Session session) {
        for (int raw = 0; raw < TankMenuLayout.TOTAL_SIZE; raw++) {
            int slot = raw;
            bindSlot(session, slot, event -> click(session, slot, event));
        }
    }
    private void bindSlot(Session session, int slot, Consumer<ItemClick> action) {
        SlotCell cell = new SlotCell(); session.cells[slot] = cell;
        ItemProvider provider = ItemProvider.sync(ignored -> cell.value);
        cell.item = new Item() {
            @Override public ItemProvider getItemProvider() { return provider; }
            @Override public void handleClick(ItemClick event) { if (event.window() == session.window) action.accept(event); }
            @Override public void handleDrag(ItemDrag event) { if (event.window() == session.window) drag(session, event); }
        };
        if (slot < TankMenuLayout.SIZE) session.upper.setItem(slot, cell.item); else session.lower.setItem(slot - TankMenuLayout.SIZE, cell.item);
    }
    private void click(Session session, int slot, ItemClick event) {
        if (TankMenuLayout.decoration(slot)) return;
        if (TankMenuLayout.playerSlot(slot)) {
            int storageSlot = TankMenuLayout.playerStorageSlot(slot);
            if (event.clickType() == ClickType.SHIFT_LEFT || event.clickType() == ClickType.SHIFT_RIGHT)
                invoke(session, context -> actions.shiftToInput(context, storageSlot));
            else invoke(session, context -> actions.playerClick(context, storageSlot, event.clickType(), event.hotbarButton()));
        } else invoke(session, context -> actions.click(context, slot == TankMenuLayout.OUTPUT, event.clickType(), event.hotbarButton()));
    }
    private void drag(Session session, ItemDrag event) {
        if (event.index() != 0 || event.clickType() == ClickType.MIDDLE) return;
        var targets = TankMenuLayout.dragTargets(event.path().stream().map(ItemDrag.Stop::windowSlot).toList());
        if (targets != null) invoke(session, context -> actions.drag(context, event.clickType() == ClickType.RIGHT, targets.playerSlots(), targets.includesInput()));
    }
    private void invoke(Session session, java.util.function.Function<TankMenuActions.Context, CompletableFuture<Boolean>> operation) {
        if (!validPlayer(session) || !session.busy.compareAndSet(false, true)) return;
        CompletableFuture<Boolean> result;
        try { result = operation.apply(new TankMenuActions.Context(session.player, session.tank, session.position, () -> validPlayer(session))); }
        catch (RuntimeException failure) { session.busy.set(false); throw failure; }
        result.whenComplete((changed, failure) -> {
            session.busy.set(false);
            if (failure != null) bridge.plugin().getLogger().log(java.util.logging.Level.WARNING, "Tank menu interaction failed", failure);
            if (!bridge.running()) return;
            bridge.schedule(session.position, () -> {
                if (bridge.generation() == session.generation && session.tank.sameActivation(session.activation)
                        && bridge.resolver().controller(session.position).orElse(null) == session.tank) changed(session.tank);
                return null;
            });
        });
    }
    private void update(Session session, int slot, ItemStack value) {
        if (!session.rendered.update(slot, value)) return;
        SlotCell cell = session.cells[slot]; cell.value = session.rendered.value(slot);
        if (slot < TankMenuLayout.SIZE) session.upper.setItem(slot, cell.item); else session.lower.setItem(slot - TankMenuLayout.SIZE, cell.item);
    }
    private void render(Session session, Display display) {
        var messages = bridge.messages(); String locale = session.player.getLocale();
        boolean chromeChanged = session.messages != messages || !locale.equals(session.locale);
        if (chromeChanged) {
            session.messages = messages; session.locale = locale;
            session.emptyInput = session.theme.emptyInput(locale);
            session.emptyOutput = session.theme.emptyOutput(locale);
            session.window.setTitle(session.theme.title(locale)); session.status = null;
            ItemStack frame = session.theme.border();
            for (int slot = 0; slot < TankMenuLayout.SIZE; slot++) if (TankMenuLayout.decoration(slot)) update(session, slot, frame);
        }
        updatePreview(session, TankMenuLayout.INPUT, display.input, chromeChanged);
        updatePreview(session, TankMenuLayout.OUTPUT, display.output, chromeChanged);
        Status key = new Status(display.fluid, display.capacity, display.protectedData, display.progress == null ? -1 : display.progress.percent(),
                display.progress == null ? 0 : TankMenuLayout.progressStage(display.progress.elapsed(), display.progress.total()), locale);
        if (!key.equals(session.status)) {
            update(session, TankMenuLayout.FLUID, session.theme.fluid(display.fluid, display.capacity, display.protectedData, locale));
            update(session, TankMenuLayout.PROGRESS, session.theme.progress(key.stage, Math.max(0, key.progress), locale));
            update(session, TankMenuLayout.BUCKETS, session.theme.capacity(true, display.fluid.amount(), locale));
            update(session, TankMenuLayout.BOTTLES, session.theme.capacity(false, display.fluid.amount(), locale));
            session.status = key;
        }
        for (int visible = 0; visible < 36; visible++) {
            int slot = (visible + 9) % 36; ItemStack actual = session.player.getInventory().getItem(slot);
            updatePreview(session, TankMenuLayout.SIZE + visible, actual == null || actual.getType().isAir() ? EMPTY : actual, chromeChanged);
        }
    }
    private void updatePreview(Session session, int slot, ItemStack value, boolean chromeChanged) {
        boolean changed = session.sources.update(slot, value);
        if (!changed && !chromeChanged) return;
        if (value == null) { update(session, slot, slot == TankMenuLayout.INPUT ? session.emptyInput : session.emptyOutput); return; }
        List<Component> extra = List.of();
        if (!session.theme.hasBackground() && (slot == TankMenuLayout.INPUT || slot == TankMenuLayout.OUTPUT)) {
            var messages = bridge.messages(); String locale = session.player.getLocale();
            extra = List.of(UiTextStyle.text(messages.text(locale, slot == TankMenuLayout.INPUT ? "input" : "output"), UiTextStyle.Role.HEADER),
                    UiTextStyle.text(messages.text(locale, slot == TankMenuLayout.INPUT ? "take-input" : "take-output"), UiTextStyle.Role.ACTION));
        }
        update(session, slot, TankMenuLayout.playerSlot(slot) ? value.clone() : UiTextStyle.displayCopy(value, extra));
    }
    private static final ItemStack EMPTY = new ItemStack(Material.AIR);
    private void navigate(Session session) {
        var navigation = bridge.recipeNavigation();
        if (navigation == null || !validPlayer(session) || !session.busy.compareAndSet(false, true)) return;
        RecipeReturn destination = new RecipeReturn(session, navigation, bridge.generation());
        destination.valid().whenComplete((valid, failure) -> onPlayer(session.player, () -> {
            if (failure != null || !Boolean.TRUE.equals(valid) || !validPlayer(session)) { session.busy.set(false); destination.cancel(); return false; }
            session.window.close().whenComplete((ignored, closedFailure) -> onPlayer(session.player, () -> {
                session.busy.set(false);
                if (closedFailure != null || !destination.validPlayer()) { destination.cancel(); return false; }
                try { navigation.navigator().open(session.player, session.position.clone(), destination); }
                catch (RuntimeException rejected) { destination.cancel(); bridge.plugin().getLogger().log(java.util.logging.Level.WARNING, "Cannot open tank recipe navigation", rejected); }
                return true;
            })); return true;
        }));
    }
    private CompletableFuture<Boolean> onPlayer(Player player, Supplier<Boolean> action) {
        return onPlayerValue(player, action, false);
    }
    private CompletableFuture<Boolean> onPlayerWindow(Player player, Supplier<CompletableFuture<Boolean>> action) {
        return onPlayerValue(player, action, CompletableFuture.completedFuture(false)).thenCompose(future -> future);
    }
    private <T> CompletableFuture<T> onPlayerValue(Player player, Supplier<T> action, T unavailable) {
        var future = new CompletableFuture<T>();
        Runnable run = () -> { try { future.complete(action.get()); } catch (Throwable failure) { future.completeExceptionally(failure); } };
        if (Bukkit.isOwnedByCurrentRegion(player)) run.run();
        else if (!bridge.running() || player.getScheduler().run(bridge.plugin(), task -> run.run(), () -> future.complete(unavailable)) == null) future.complete(unavailable);
        return future;
    }
    private final class RecipeReturn implements TankRecipeNavigation.ReturnHandle {
        private final Session origin; private final TankRecipeNavigation.Entry navigation; private final long generation;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        RecipeReturn(Session origin, TankRecipeNavigation.Entry navigation, long generation) { this.origin = origin; this.navigation = navigation; this.generation = generation; }
        private boolean validPlayer() {
            Player player = origin.player;
            return !cancelled.get() && bridge.running() && bridge.generation() == generation && bridge.recipeNavigation() == navigation
                    && bridge.isLoadedTank(origin.tank) && origin.tank.sameActivation(origin.activation)
                    && (!(navigation.owner() instanceof org.bukkit.plugin.Plugin plugin) || plugin.isEnabled())
                    && Bukkit.isOwnedByCurrentRegion(player) && player.isOnline() && player.isValid() && !player.isDead()
                    && player.getGameMode() != org.bukkit.GameMode.SPECTATOR && player.getWorld() == origin.position.getWorld()
                    && player.getLocation().distanceSquared(origin.position.clone().add(.5,.5,.5)) <= 36
                    && net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine.instance().antiGriefProvider().test(player,
                    net.momirealms.craftengine.libraries.antigrieflib.Flag.OPEN_CONTAINER, origin.position);
        }
        private boolean validTank() {
            return !cancelled.get() && bridge.running() && bridge.generation() == generation && bridge.recipeNavigation() == navigation
                    && origin.tank.sameActivation(origin.activation)
                    && bridge.resolver().controller(origin.position).orElse(null) == origin.tank && !origin.tank.hasProtectedData();
        }
        @Override public CompletableFuture<Boolean> valid() {
            return onPlayer(origin.player, this::validPlayer).thenCompose(valid -> !valid ? CompletableFuture.completedFuture(false)
                    : bridge.schedule(origin.position, this::validTank)).thenCompose(valid -> !valid ? CompletableFuture.completedFuture(false)
                    : onPlayer(origin.player, this::validPlayer));
        }
        @Override public CompletableFuture<Boolean> reopen() {
            var expected = new java.util.concurrent.atomic.AtomicReference<org.bukkit.inventory.Inventory>();
            return onPlayer(origin.player, () -> {
                if (!validPlayer() || origin.player.getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) return false;
                expected.set(origin.player.getOpenInventory().getTopInventory()); return true;
            }).thenCompose(valid -> !valid ? CompletableFuture.completedFuture(null)
                    : bridge.schedule(origin.position, () -> validTank() ? capture(origin.tank) : null))
                    .thenCompose(display -> display == null ? CompletableFuture.completedFuture(false) : onPlayerWindow(origin.player, () -> {
                        if (!validPlayer() || origin.player.getOpenInventory().getTopInventory() != expected.get()) return CompletableFuture.completedFuture(false);
                        var opened = openWindow(origin.player, origin.tank, origin.position, display); cancel(); return opened;
                    }));
        }
        @Override public void cancel() { cancelled.set(true); }
    }
    private boolean validPlayer(Session session) {
        Player player = session.player;
        return bridge.running() && sessions.get(player.getUniqueId()) == session && session.window.isOpen()
                && bridge.generation() == session.generation
                && bridge.isLoadedTank(session.tank) && session.tank.sameActivation(session.activation)
                && Bukkit.isOwnedByCurrentRegion(player)
                && player.isOnline() && player.isValid() && !player.isDead() && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && player.getWorld() == session.position.getWorld() && player.getLocation().distanceSquared(session.position.clone().add(.5,.5,.5)) <= 36
                && net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine.instance().antiGriefProvider().test(player,
                    net.momirealms.craftengine.libraries.antigrieflib.Flag.OPEN_CONTAINER, session.position);
    }
    private void remove(Session session) { sessions.remove(session.player.getUniqueId(), session); removeViewer(session); }
    private void removeViewer(Session session) {
        viewers.computeIfPresent(session.tank, (tank, watching) -> { watching.remove(session); return watching.isEmpty() ? null : watching; });
    }
    @Override public void close() {
        legacy.close();
        sessions.values().forEach(session -> session.player.getScheduler().run(bridge.plugin(), task -> session.window.close(), () -> {}));
        pendingOpen.clear(); sessions.clear(); viewers.clear();
    }
    private static final class Session {
        final Player player; final FluidTankController tank; final Location position; final long activation, generation; final AtomicBoolean queued = new AtomicBoolean(), busy = new AtomicBoolean();
        Pane upper, lower; Window window; volatile Display latest;
        final TankMenuRenderCache<ItemStack> rendered = new TankMenuRenderCache<>(TankMenuLayout.TOTAL_SIZE, ItemStack::clone);
        final TankMenuRenderCache<ItemStack> sources = new TankMenuRenderCache<>(TankMenuLayout.TOTAL_SIZE, ItemStack::clone);
        final SlotCell[] cells = new SlotCell[TankMenuLayout.TOTAL_SIZE];
        final TankMenuTheme theme;
        ItemStack emptyInput, emptyOutput; Status status; TankRecipeNavigation.Entry navigation;
        com.ydxc20091.fluidcore.config.FluidMessages messages; String locale;
        Session(Player player, FluidTankController tank, Location position, long activation, long generation, TankMenuTheme theme) { this.player = player; this.tank = tank; this.position = position.clone(); this.activation = activation; this.generation = generation; this.theme = theme; }
    }
    private static final class SlotCell { volatile ItemStack value = EMPTY; Item item; }
}
