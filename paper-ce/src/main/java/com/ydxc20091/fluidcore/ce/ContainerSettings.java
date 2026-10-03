/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.bukkit.ContainerDefinition;
import net.momirealms.craftengine.core.item.setting.CustomItemSettingType;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;

import java.util.HashSet;
import java.util.Set;

final class ContainerSettings {
    static final CustomItemSettingType<ContainerDefinition> TYPE = CustomItemSettingType.simple();
    private ContainerSettings() {}

    static ContainerDefinition parse(ConfigSection section) {
        long capacity = FluidConfigurationValues.longValue(section, "capacity", 1000);
        Filter allowed = filter(section);
        long amount = FluidConfigurationValues.longValue(section, "default-amount", 0);
        if (amount < 0) throw new IllegalArgumentException("Negative default amount");
        String fluid = FluidConfigurationValues.string(section, "default-fluid");
        if (amount > 0 && fluid == null) throw new IllegalArgumentException("default-fluid is required for a filled container");
        if (fluid != null) FluidKey.of(fluid);
        return new ContainerDefinition(capacity, allowed.fluids(), allowed.tags(), allowed.allowAll(),
                amount == 0 ? FluidStack.EMPTY : FluidStack.of(FluidVariant.of(fluid), amount));
    }

    static Filter filter(ConfigSection section) {
        if (section.containsKey("allowed-fluids") && section.containsKey("allowed_fluids"))
            throw new IllegalArgumentException("Specify only allowed-fluids or allowed_fluids");
        String key = section.containsKey("allowed_fluids") ? "allowed_fluids" : "allowed-fluids";
        if (!section.containsKey(key)) return new Filter(Set.of(), Set.of(), true);
        Set<FluidKey> fluids = new HashSet<>();
        Set<FluidKey> tags = new HashSet<>();
        for (String value : FluidConfigurationValues.strings(section, key)) {
            boolean tag = value.startsWith("#");
            FluidKey id = FluidKey.of(tag ? value.substring(1) : value);
            if (!(tag ? tags : fluids).add(id)) throw new IllegalArgumentException("Duplicate allowed fluid or tag: " + value);
        }
        return new Filter(Set.copyOf(fluids), Set.copyOf(tags), false);
    }

    record Filter(Set<FluidKey> fluids, Set<FluidKey> tags, boolean allowAll) {}
}
