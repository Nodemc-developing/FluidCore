/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.core.plugin.config.AbstractConfigParser;
import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStage;
import net.momirealms.craftengine.core.util.Key;
import java.util.List;

/** Lifecycle barrier: runs even without resources and publishes only after both candidate parsers complete. */
final class FluidRegistryReadyParser extends AbstractConfigParser {
    private final FluidConfigParser fluids;
    FluidRegistryReadyParser(FluidConfigParser fluids) { this.fluids = fluids; }
    @Override public Key type() { return Key.of("fluidcore:registry-ready"); }
    @Override public String[] sectionId() { return new String[]{"fluidcore:registry-ready"}; }
    @Override public LoadingStage loadingStage() { return FluidCoreLoadingStages.REGISTRY_READY; }
    @Override public List<LoadingStage> dependencies() { return List.of(FluidCoreLoadingStages.FLUID_TAGS); }
    @Override public void loadAll() {}
    @Override public void postProcess() { fluids.publishInLoadingStage(); }
    @Override public boolean silentIfNotExists() { return true; }
}
