/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.core.plugin.config.lifecycle.LoadingStage;

/** Actual CE parser stages. Addon parsers depend on REGISTRY_READY to query the published registry. */
public final class FluidCoreLoadingStages {
    public static final LoadingStage FLUIDS = new LoadingStage("fluidcore:fluids");
    public static final LoadingStage FLUID_TAGS = new LoadingStage("fluidcore:fluid-tags");
    public static final LoadingStage REGISTRY_READY = new LoadingStage("fluidcore:registry-ready");
    private FluidCoreLoadingStages() {}
}
