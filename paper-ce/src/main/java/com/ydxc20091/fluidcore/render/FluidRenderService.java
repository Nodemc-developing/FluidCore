/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.render;

import com.ydxc20091.fluidcore.api.FluidKey;
import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.bukkit.ServerCapabilities;
import com.ydxc20091.fluidcore.core.FluidDefinition;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackCacheEvent;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.IntTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import net.momirealms.craftengine.libraries.nbt.StringTag;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;

/**
 * Opt-in rendering for addon menus and display entities. Item methods run on the caller's
 * Paper main / Folia inventory-owning thread; resource generation is pure and runs in CE's async pack event.
 * Register this listener once during runtime startup, and close it during plugin shutdown.
 */
public final class FluidRenderService implements Listener, AutoCloseable {
    public interface Registration extends AutoCloseable { @Override void close(); }
    private static final int MAX_STYLES = 16;
    private final Plugin plugin;
    private final FluidRegistry registry;
    private final Path generatedPack;
    private final boolean modernItems;
    private final Object packLock = new Object();
    private final Map<FluidKey, Entry> styles = new LinkedHashMap<>();
    private final FluidRenderPublication publication = new FluidRenderPublication();
    private long generation;
    private boolean closed;

    public FluidRenderService(Plugin plugin, FluidRegistry registry) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        generatedPack = plugin.getDataFolder().toPath().resolve("generated-render-pack");
        modernItems = ServerCapabilities.modernItems();
    }
    /** Registration is permitted in onLoad, before the owner becomes enabled. A normal CE pack rebuild publishes assets. */
    public synchronized Registration registerStyle(Object owner, FluidRenderStyle style) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(style, "style");
        if (closed) throw new IllegalStateException("Fluid rendering service is closed");
        if (styles.containsKey(style.id())) throw new IllegalArgumentException("Render style is already registered: " + style.id());
        if (styles.size() >= MAX_STYLES) throw new IllegalStateException("At most " + MAX_STYLES + " render styles may be registered");
        Entry entry = new Entry(owner, style); styles.put(style.id(), entry); generation++;
        return () -> { synchronized (FluidRenderService.this) {
            if (styles.remove(style.id(), entry)) {
                generation++;
                publication.remove(style.id());
            }
        } };
    }
    public synchronized Set<FluidKey> registeredStyles() { return Set.copyOf(styles.keySet()); }
    public synchronized boolean assetsGenerated(FluidKey style) {
        return !closed && publication.available(style, registry.snapshot().generation());
    }
    private synchronized boolean assetsGenerated(FluidKey style, long snapshotGeneration) {
        return !closed && publication.available(style, snapshotGeneration);
    }
    /** Single-cell liquid level with exact quantity in its tooltip. */
    public ItemStack icon(FluidKey style, FluidStack fluid, long capacity) {
        return grid(style, 1, 1, List.of(Objects.requireNonNull(fluid, "fluid")), capacity).item(0, 0);
    }
    /** Each returned item can be installed directly into a GUI slot. Input stacks and inventories are never mutated. */
    public FluidRenderGrid grid(FluidKey styleId, int columns, int rows, List<FluidStack> layers, long capacity) {
        FluidRenderStyle style = style(styleId);
        FluidRenderLayout layout = FluidRenderLayout.of(columns, rows, layers, capacity);
        FluidRegistry.Snapshot snapshot = registry.snapshot();
        boolean generated = assetsGenerated(styleId, snapshot.generation());
        String[] layerIds = new String[layout.layers().size()];
        int[] layerColors = new int[layout.layers().size()];
        for (int layer = 0; layer < layout.layers().size(); layer++) {
            FluidStack fluid = layout.layers().get(layer);
            layerIds[layer] = fluid.isEmpty() ? "" : fluid.variant().fluid().toString();
            layerColors[layer] = fluid.isEmpty() ? 0xffffff : style.color(fluid, definition(snapshot, fluid));
        }
        ItemStack[][] items = new ItemStack[rows][columns];
        for (int row = 0; row < rows; row++) {
            int dominant = layout.layerAt(row, 0);
            ItemStack stack = fallback(style, dominant < 0 ? FluidStack.EMPTY : layout.layers().get(dominant), snapshot);
            if (generated) {
                String[] identities = new String[FluidRenderLayout.PIXELS_PER_CELL];
                int[] colors = new int[FluidRenderLayout.PIXELS_PER_CELL];
                int[] bands = layout.bands(row);
                for (int band = 0; band < bands.length; band++) {
                    identities[band] = bands[band] < 0 ? "" : layerIds[bands[band]];
                    colors[band] = bands[band] < 0 ? 0xffffff : layerColors[bands[band]];
                }
                stack = modeled(stack, FluidRenderAssets.model(styleId), identities, colors);
            }
            tooltip(stack, layout, capacity, snapshot);
            for (int column = 0; column < columns; column++) items[row][column] = stack.clone();
        }
        return new FluidRenderGrid(layout, items);
    }
    /** Optional 16-level model for an existing item-display renderer; this does not place entities or change block states. */
    public ItemStack worldModel(FluidKey styleId, FluidStack fluid, long capacity) {
        Objects.requireNonNull(fluid, "fluid");
        if (capacity < 0) throw new IllegalArgumentException("Negative render capacity");
        FluidRenderStyle style = style(styleId);
        if (!style.worldModels()) throw new IllegalStateException("World models are not enabled for render style " + styleId);
        FluidRegistry.Snapshot snapshot = registry.snapshot();
        ItemStack stack = fallback(style, fluid, snapshot);
        if (!assetsGenerated(styleId, snapshot.generation())) return stack;
        int level = fluid.isEmpty() || capacity == 0 ? 0 : BigInteger.valueOf(fluid.amount()).min(BigInteger.valueOf(capacity))
                .multiply(BigInteger.valueOf(16)).add(BigInteger.valueOf(capacity - 1)).divide(BigInteger.valueOf(capacity)).intValueExact();
        FluidKey modelFluid = fluid.isEmpty() || !snapshot.fluids().containsKey(fluid.variant().fluid()) ? null : fluid.variant().fluid();
        return modeled(stack, FluidRenderAssets.world(styleId, modelFluid, level), new String[0],
                new int[]{fluid.isEmpty() ? 0xffffff : style.color(fluid, definition(snapshot, fluid))});
    }
    @EventHandler public void onResourcePack(AsyncResourcePackCacheEvent event) {
        List<FluidRenderStyle> captured; long token, attempt;
        synchronized (this) {
            // CE's external pack registration is per event. Old files are not evidence that
            // they will be present in this new pack, so fail closed before attempting any I/O.
            attempt = publication.begin();
            if (closed || !modernItems || styles.isEmpty()) return;
            token = generation; captured = styles.values().stream().map(Entry::style).toList();
        }
        FluidRegistry.Snapshot snapshot = registry.snapshot();
        synchronized (packLock) {
            try {
                FluidRenderAssets.compile(generatedPack, captured, snapshot.fluids().values());
                synchronized (this) {
                    if (closed || token != generation || snapshot.generation() != registry.snapshot().generation()
                            || !publication.current(attempt)) return;
                    event.registerExternalResourcePack(generatedPack);
                    publication.publish(attempt, snapshot.generation(), captured.stream().map(FluidRenderStyle::id).toList());
                }
            } catch (IOException | RuntimeException failure) {
                plugin.getLogger().log(Level.WARNING, "Fluid render assets were not published for this pack build; using fallback items", failure);
            }
        }
    }
    @EventHandler public void onOwnerDisable(PluginDisableEvent event) { unregisterOwner(event.getPlugin()); }
    public synchronized void unregisterOwner(Object owner) {
        if (styles.entrySet().removeIf(entry -> entry.getValue().owner == owner)) {
            generation++; publication.retain(styles.keySet());
        }
    }
    private synchronized FluidRenderStyle style(FluidKey id) {
        if (closed) throw new IllegalStateException("Fluid rendering service is closed");
        Entry entry = styles.get(Objects.requireNonNull(id, "style"));
        if (entry == null) throw new IllegalArgumentException("Render style is not registered: " + id);
        return entry.style;
    }
    private static FluidDefinition definition(FluidRegistry.Snapshot snapshot, FluidStack stack) {
        FluidDefinition found = snapshot.fluids().get(stack.variant().fluid());
        return found != null ? found : FluidDefinition.builder(stack.variant().fluid()).build();
    }
    private static ItemStack fallback(FluidRenderStyle style, FluidStack stack, FluidRegistry.Snapshot snapshot) {
        if (stack.isEmpty()) return new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        int rgb = style.color(stack, definition(snapshot, stack));
        DyeColor best = DyeColor.GRAY; long distance = Long.MAX_VALUE;
        for (DyeColor dye : DyeColor.values()) {
            int color = dye.getColor().asRGB();
            long dr = (rgb >> 16 & 255) - (color >> 16 & 255), dg = (rgb >> 8 & 255) - (color >> 8 & 255), db = (rgb & 255) - (color & 255);
            long candidate = dr * dr + dg * dg + db * db;
            if (candidate < distance) { distance = candidate; best = dye; }
        }
        return new ItemStack(Material.valueOf(best.name() + "_STAINED_GLASS_PANE"));
    }
    private static ItemStack modeled(ItemStack original, String model, String[] identities, int[] colorValues) {
        Item wrapped = BukkitItemManager.instance().wrap(original.clone());
        wrapped.itemModel(model);
        CompoundTag data = new CompoundTag(); ListTag strings = new ListTag(), colors = new ListTag();
        for (String identity : identities) strings.add(new StringTag(identity));
        for (int color : colorValues) colors.add(IntTag.valueOf(color & 0xffffff));
        data.put("strings", strings); data.put("colors", colors);
        wrapped.setSparrowTagComponent(DataComponentKeys.CUSTOM_MODEL_DATA, data);
        return ((ItemStack) wrapped.platformItem()).clone();
    }
    private static void tooltip(ItemStack stack, FluidRenderLayout layout, long capacity, FluidRegistry.Snapshot snapshot) {
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(layout.totalAmount() + " / " + capacity + " mB", NamedTextColor.WHITE));
        List<Component> lore = new ArrayList<>();
        for (FluidStack fluid : layout.layers()) if (!fluid.isEmpty())
            lore.add(Component.text(definition(snapshot, fluid).displayName() + ": " + fluid.amount() + " mB", NamedTextColor.GRAY));
        if (layout.overflow()) lore.add(Component.text("Stored amount exceeds displayed capacity", NamedTextColor.YELLOW));
        meta.lore(lore); stack.setItemMeta(meta);
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true; generation++; styles.clear(); publication.begin(); HandlerList.unregisterAll(this);
    }
    private record Entry(Object owner, FluidRenderStyle style) {}
}
