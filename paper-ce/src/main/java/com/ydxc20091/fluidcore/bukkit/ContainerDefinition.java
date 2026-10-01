/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.FluidKey;
import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.api.FluidVariant;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import java.util.Objects;
import java.util.Set;

/** CE setting data kept independent of CE classes for plugin integrations. */
public record ContainerDefinition(long capacity, Set<FluidKey> allowedFluids,
                                  Set<FluidKey> allowedTags, boolean allowAll, FluidStack initialContent) {
    public ContainerDefinition {
        if (capacity <= 0) throw new IllegalArgumentException("Container capacity must be positive");
        allowedFluids = Set.copyOf(allowedFluids);
        allowedTags = Set.copyOf(allowedTags);
        if (allowAll && (!allowedFluids.isEmpty() || !allowedTags.isEmpty()))
            throw new IllegalArgumentException("An unrestricted container cannot declare a whitelist");
        if (initialContent == null || initialContent.amount() > capacity) throw new IllegalArgumentException("Invalid initial content");
        // Tag membership is validated by the runtime against the published registry, after CE parses all sections.
        if (!initialContent.isEmpty() && !allowAll && allowedTags.isEmpty() && !allowedFluids.contains(initialContent.variant().fluid()))
            throw new IllegalArgumentException("Initial fluid is rejected by its container");
    }

    /** Retains the original constructor's empty-set-means-unrestricted contract. */
    public ContainerDefinition(long capacity, Set<FluidKey> allowedFluids, FluidStack initialContent) {
        this(capacity, allowedFluids, Set.of(), allowedFluids.isEmpty(), initialContent);
    }

    /** Direct fluid matching without a registry; tag matching requires the overload below. */
    public boolean accepts(FluidVariant variant) {
        Objects.requireNonNull(variant, "variant");
        return allowAll || allowedFluids.contains(variant.fluid());
    }

    public boolean accepts(FluidVariant variant, FluidRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        if (accepts(variant)) return true;
        for (FluidKey tag : allowedTags) if (registry.hasTag(variant.fluid(), tag)) return true;
        return false;
    }
}
