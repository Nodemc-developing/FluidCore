/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.core.FluidDefinition;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.plugin.config.*;
import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStage;
import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStages;
import net.momirealms.craftengine.core.util.Key;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/** Publishes fluid definitions and independently declared tags as one validated registry candidate. */
final class FluidConfigParser extends IdSectionConfigParser {
    private final FluidRegistry registry;
    private final Logger logger;
    private final FluidTagConfigParser tags;
    private final FluidRegistryReadyParser readyParser;
    private final Map<FluidKey, FluidDefinition> pending = new LinkedHashMap<>();
    private final Map<FluidKey, FluidDefinition> pendingOverrides = new LinkedHashMap<>();
    private Set<FluidKey> publishedFluids = Set.of();
    private Set<FluidKey> publishedTags = Set.of();
    private Set<FluidKey> publishedOverrides = Set.of();
    private boolean invalid;
    private boolean ready;
    private boolean unacknowledgedPublication;

    FluidConfigParser(FluidRegistry registry, Logger logger) {
        this.registry = registry;
        this.logger = logger;
        tags = new FluidTagConfigParser();
        readyParser = new FluidRegistryReadyParser(this);
    }
    FluidTagConfigParser tagsParser() { return tags; }
    FluidRegistryReadyParser readyParser() { return readyParser; }
    @Override public Key type() { return Key.of("fluidcore:fluids"); }
    @Override public String[] sectionId() { return new String[]{"fluidcore:fluids"}; }
    @Override public LoadingStage loadingStage() { return FluidCoreLoadingStages.FLUIDS; }
    @Override public List<LoadingStage> dependencies() { return List.of(LoadingStages.TEMPLATE, LoadingStages.CONFIG_FACTORY); }
    @Override public synchronized void preProcess() { pending.clear(); pendingOverrides.clear(); invalid = false; ready = false; unacknowledgedPublication = false; }
    @Override public synchronized void postProcess() { ready = true; }
    @Override public synchronized int count() { return pending.size() + pendingOverrides.size(); }

    @Override public void setErrorHandler(Consumer<ResourceException> handler) {
        super.setErrorHandler(failure -> { synchronized (this) { invalid = true; } handler.accept(failure); });
    }

    @Override protected synchronized void parseSection(Pack pack, Path path, Key id, ConfigSection section) {
        try {
            FluidKey key = FluidKey.of(id.toString());
            if (pending.containsKey(key) || pendingOverrides.containsKey(key)) throw new IllegalArgumentException("Duplicate fluid: " + id);
            boolean override = false;
            if (section.containsKey("override")) {
                if (!(section.get("override") instanceof Boolean flag)) throw new IllegalArgumentException("override must be a boolean");
                override = flag;
            }
            FluidDefinition inherited = override ? registry.baseDefinition(key).orElseThrow(() ->
                    new IllegalArgumentException("Cannot override an unknown base fluid: " + key)) : null;
            FluidDefinition definition = decode(key, section, inherited);
            (override ? pendingOverrides : pending).put(key, definition);
        } catch (RuntimeException failure) { invalid = true; throw failure; }
    }

    static FluidDefinition decode(FluidKey key, ConfigSection section) {
        return decode(key, section, null);
    }

    private static FluidDefinition decode(FluidKey key, ConfigSection section, FluidDefinition inherited) {
        Set<FluidKey> declaredTags = new LinkedHashSet<>();
        if (!section.containsKey("tags") && inherited != null) declaredTags.addAll(inherited.tags());
        for (String tag : FluidConfigurationValues.strings(section, "tags")) {
            FluidKey id = FluidKey.of(tag.startsWith("#") ? tag.substring(1) : tag);
            if (!declaredTags.add(id)) throw new IllegalArgumentException("Duplicate fluid tag: " + tag);
        }
        FluidProperties defaults = inherited == null ? FluidProperties.defaults() : inherited.properties();
        var properties = defaults.toBuilder()
                .density(FluidConfigurationValues.intValue(section, "density", defaults.density()))
                .viscosity(FluidConfigurationValues.intValue(section, "viscosity", defaults.viscosity()))
                .temperature(FluidConfigurationValues.intValue(section, "temperature", defaults.temperature()))
                .lightLevel(FluidConfigurationValues.intValue(section, "light-level", defaults.lightLevel()));
        if (section.containsKey("color")) properties.color(color(section.get("color")));
        String texture = FluidConfigurationValues.string(section, "texture");
        if (texture != null) properties.texture(texture);
        String rarity = FluidConfigurationValues.string(section, "rarity");
        if (rarity != null) properties.rarity(FluidRarity.valueOf(rarity.toUpperCase(Locale.ROOT).replace('-', '_')));
        String bucket = FluidConfigurationValues.string(section, "bucket-item");
        if (bucket != null) properties.bucketItem(FluidKey.of(bucket));
        ConfigSection sounds = section.getSection("sounds");
        if (sounds != null) {
            Set<FluidSound> supplied = EnumSet.noneOf(FluidSound.class);
            for (String event : sounds.keySet()) {
                FluidSound type = switch (event) {
                    case "fill", "container-fill" -> FluidSound.CONTAINER_FILL;
                    case "empty", "container-empty" -> FluidSound.CONTAINER_EMPTY;
                    case "vaporize" -> FluidSound.VAPORIZE;
                    case "cauldron-drip" -> FluidSound.CAULDRON_DRIP;
                    default -> throw new IllegalArgumentException("Unknown fluid sound event: " + event);
                };
                if (!supplied.add(type)) throw new IllegalArgumentException("Duplicate fluid sound event: " + type);
                properties.sound(type, FluidKey.of(FluidConfigurationValues.string(sounds, event)));
            }
        }
        String displayName = FluidConfigurationValues.string(section, "display-name");
        return new FluidDefinition(key, displayName == null ? (inherited == null ? key.toString() : inherited.displayName()) : displayName,
                declaredTags, properties.build());
    }

    /** Numeric values are packed ARGB; six digit hex has opaque alpha, eight digit hex includes alpha. */
    static int color(Object value) {
        if (value instanceof String text && (text.startsWith("#") || text.startsWith("0x") || text.startsWith("0X"))) {
            String hex = text.startsWith("#") ? text.substring(1) : text.substring(2);
            if (!hex.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}"))
                throw new IllegalArgumentException("color must be #RRGGBB or #AARRGGBB");
            long packed = Long.parseLong(hex, 16);
            return (int) (hex.length() == 6 ? packed | 0xff000000L : packed);
        }
        if (value == null) throw new IllegalArgumentException("color must be a packed ARGB value");
        long packed = FluidConfigurationValues.longValue(ConfigSection.of("color", Map.of("value", value)), "value", 0);
        if (packed < Integer.MIN_VALUE || packed > 0xffffffffL) throw new IllegalArgumentException("color must fit a packed ARGB value");
        return (int) packed;
    }

    /** Called after both CE parsers finish; failures retain the complete previous FluidCore registry. */
    synchronized boolean publish() {
        if (ready && tags.ready()) unacknowledgedPublication |= publishCandidate();
        boolean changed = unacknowledgedPublication;
        unacknowledgedPublication = false;
        return changed;
    }

    synchronized void publishInLoadingStage() { unacknowledgedPublication |= publishCandidate(); }

    private boolean publishCandidate() {
        if (!ready || !tags.ready()) return false;
        ready = false;
        FluidTagConfigParser.Candidate tagCandidate = tags.consume();
        if (invalid || tagCandidate.invalid()) {
            logger.warning("Invalid CE fluid configuration; the previous FluidCore registry remains active");
            return false;
        }
        try {
            // Registry writers use this same monitor. Candidate tags and effective overlays
            // must come from one revision until the new candidate is published.
            synchronized (registry) {
                FluidRegistry.Snapshot current = registry.snapshot();
                Map<FluidKey, FluidDefinition> fluids = new HashMap<>(current.fluids());
                publishedFluids.forEach(fluids::remove);
                publishedOverrides.forEach(key -> {
                    var baseDefinition = registry.baseDefinition(key);
                    if (baseDefinition.isPresent()) fluids.put(key, baseDefinition.get());
                    else fluids.remove(key);
                });
                fluids.putAll(pending);
                // Base replacements do not replace another plugin's effective metadata overlay.
                // Deleted bases are excluded because only this round's surviving definitions are considered.
                pending.keySet().forEach(key -> {
                    if (!publishedOverrides.contains(key)) registry.metadataOverride(key).ifPresent(overlay -> fluids.put(key, overlay));
                });
                fluids.putAll(pendingOverrides);
                Map<FluidKey, Set<FluidKey>> knownTags = new HashMap<>(current.tags());
                publishedTags.forEach(knownTags::remove);
                Map<FluidKey, Set<FluidKey>> base = new HashMap<>();
                knownTags.forEach((key, members) -> base.put(key, new LinkedHashSet<>(members)));
                fluids.values().forEach(definition -> definition.tags().forEach(tag ->
                        base.computeIfAbsent(tag, ignored -> new LinkedHashSet<>()).add(definition.key())));
                Map<FluidKey, Set<FluidKey>> resolved = FluidTagConfigParser.resolve(tagCandidate.definitions(), fluids.keySet(), base);
                registry.replaceOwned("craftengine", List.copyOf(pending.values()), resolved, List.copyOf(pendingOverrides.values()));
                publishedFluids = Set.copyOf(pending.keySet());
                publishedTags = Set.copyOf(resolved.keySet());
                publishedOverrides = Set.copyOf(pendingOverrides.keySet());
                return true;
            }
        } catch (RuntimeException failure) {
            logger.warning("CE fluid registry publication failed: " + failure.getMessage());
            return false;
        }
    }
}
