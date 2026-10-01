/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidKey;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.plugin.config.*;
import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStage;
import net.momirealms.craftengine.core.util.Key;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** Separate CE configuration section; publication belongs to the shared fluid candidate. */
final class FluidTagConfigParser extends IdSectionConfigParser {
    private final Map<FluidKey, List<String>> pending = new LinkedHashMap<>();
    private boolean ready;
    private boolean invalid;

    @Override public Key type() { return Key.of("fluidcore:fluid-tags"); }
    @Override public String[] sectionId() { return new String[]{"fluidcore:fluid-tags"}; }
    @Override public LoadingStage loadingStage() { return FluidCoreLoadingStages.FLUID_TAGS; }
    @Override public List<LoadingStage> dependencies() { return List.of(FluidCoreLoadingStages.FLUIDS); }
    @Override public synchronized void preProcess() { pending.clear(); ready = false; invalid = false; }
    @Override public synchronized void postProcess() { ready = true; }
    @Override public synchronized int count() { return pending.size(); }
    @Override public void setErrorHandler(Consumer<ResourceException> handler) {
        super.setErrorHandler(failure -> { synchronized (this) { invalid = true; } handler.accept(failure); });
    }
    @Override protected synchronized void parseSection(Pack pack, Path path, Key id, ConfigSection section) {
        try {
            if (!section.containsKey("values")) throw new IllegalArgumentException("Fluid tag requires a values list");
            List<String> values = FluidConfigurationValues.strings(section, "values");
            Set<String> supplied = new HashSet<>();
            for (String value : values) {
                FluidKey.of(value.startsWith("#") ? value.substring(1) : value);
                if (!supplied.add(value)) throw new IllegalArgumentException("Duplicate tag value: " + value);
            }
            if (pending.putIfAbsent(FluidKey.of(id.toString()), values) != null)
                throw new IllegalArgumentException("Duplicate fluid tag: " + id);
        } catch (RuntimeException failure) { invalid = true; throw failure; }
    }
    synchronized boolean ready() { return ready; }
    synchronized Candidate consume() {
        if (!ready) throw new IllegalStateException("Tag configuration has not finished loading");
        ready = false;
        return new Candidate(Map.copyOf(pending), invalid);
    }
    record Candidate(Map<FluidKey, List<String>> definitions, boolean invalid) {}

    static Map<FluidKey, Set<FluidKey>> resolve(Map<FluidKey, List<String>> declarations, Set<FluidKey> fluids,
                                               Map<FluidKey, Set<FluidKey>> existingTags) {
        Map<FluidKey, Set<FluidKey>> resolved = new LinkedHashMap<>();
        Set<FluidKey> visiting = new LinkedHashSet<>();
        for (FluidKey tag : declarations.keySet()) expand(tag, declarations, fluids, existingTags, resolved, visiting);
        return Map.copyOf(resolved);
    }

    private static Set<FluidKey> expand(FluidKey tag, Map<FluidKey, List<String>> declarations, Set<FluidKey> fluids,
                                        Map<FluidKey, Set<FluidKey>> existingTags,
                                        Map<FluidKey, Set<FluidKey>> resolved, Set<FluidKey> visiting) {
        if (resolved.containsKey(tag)) return resolved.get(tag);
        if (!declarations.containsKey(tag)) {
            Set<FluidKey> known = existingTags.get(tag);
            if (known == null) throw new IllegalArgumentException("Unknown fluid tag: " + tag);
            for (FluidKey fluid : known) if (!fluids.contains(fluid))
                throw new IllegalArgumentException("Fluid tag " + tag + " references unknown fluid: " + fluid);
            return known;
        }
        if (!visiting.add(tag)) throw new IllegalArgumentException("Cyclic fluid tag references: " + visiting + " -> " + tag);
        Set<FluidKey> members = new LinkedHashSet<>();
        // Inline memberships remain available even when the same tag also has a dedicated declaration.
        members.addAll(existingTags.getOrDefault(tag, Set.of()));
        for (String entry : declarations.get(tag)) {
            if (entry.startsWith("#")) {
                members.addAll(expand(FluidKey.of(entry.substring(1)), declarations, fluids, existingTags, resolved, visiting));
            } else {
                FluidKey fluid = FluidKey.of(entry);
                if (!fluids.contains(fluid)) throw new IllegalArgumentException("Fluid tag " + tag + " references unknown fluid: " + fluid);
                members.add(fluid);
            }
        }
        visiting.remove(tag);
        Set<FluidKey> result = Set.copyOf(members);
        resolved.put(tag, result);
        return result;
    }
}
