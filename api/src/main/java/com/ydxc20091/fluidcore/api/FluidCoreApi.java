/* Copyright 2026 ydxc20091. SPDX-License-Identifier: Apache-2.0 */
package com.ydxc20091.fluidcore.api;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Additive API capability negotiation, independent of the artifact's development version.
 * API level 1 describes this distribution's Java API and core contracts; it does not declare
 * a frozen 1.0 release or the availability of a particular server, renderer or optional addon.
 */
public final class FluidCoreApi {
    public static final int API_LEVEL = 1;
    public enum Capability {
        TYPED_COMPONENTS, NESTED_TRANSACTIONS, CURRENT_TRANSACTION,
        SHARED_CAPACITY_STORAGE, COMPOSABLE_TRANSFERS, REGISTRY_SUBSCRIPTIONS
    }
    private static final Set<Capability> CAPABILITIES = Set.copyOf(EnumSet.allOf(Capability.class));
    private FluidCoreApi() { }

    public static int apiLevel() { return API_LEVEL; }
    public static Set<Capability> capabilities() { return CAPABILITIES; }
    public static boolean supports(Capability capability) { return CAPABILITIES.contains(Objects.requireNonNull(capability, "capability")); }
    public static void requireApi(int minimum) {
        if (minimum < 0) throw new IllegalArgumentException("Minimum API level must be nonnegative");
        if (minimum > API_LEVEL) throw new IllegalStateException("FluidCore API level " + minimum + " is required; available level is " + API_LEVEL);
    }
    public static void requireCapabilities(Capability... required) {
        Objects.requireNonNull(required, "required");
        for (Capability capability : required) if (!supports(capability))
            throw new IllegalStateException("FluidCore does not provide capability " + capability);
    }
}
