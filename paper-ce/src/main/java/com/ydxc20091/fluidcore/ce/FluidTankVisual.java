/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import java.util.Set;

/** Optional existing block properties; visual changes never recreate a block or its entity. */
record FluidTankVisual(String levelProperty, String fluidProperty) {
    private static final Set<String> KINDS = Set.of("empty", "water", "milk", "lava", "honey", "other");

    static FluidTankVisual parse(BlockDefinition block, ConfigSection section) {
        if (!section.containsKey("visual")) return null;
        ConfigSection visual = section.getNonNullSection("visual");
        String levelName = visual.getString("level_property", "fluid_level");
        String kindName = visual.getString("fluid_property", "fluid_kind");
        if (levelName == null || levelName.isBlank() || kindName == null || kindName.isBlank() || levelName.equals(kindName))
            throw new IllegalArgumentException("Tank visual properties must have distinct non-empty names");
        Property<?> level = block.getProperty(levelName);
        Property<?> kind = block.getProperty(kindName);
        if (level == null || level.valueClass() != Integer.class)
            throw new IllegalArgumentException("Tank visual level_property must name an integer block property");
        for (int value = 0; value <= 16; value++) if (!level.possibleValues().contains(value))
            throw new IllegalArgumentException("Tank visual level_property must include every value from 0 through 16");
        if (kind == null || kind.valueClass() != String.class || !kind.possibleValues().containsAll(KINDS))
            throw new IllegalArgumentException("Tank visual fluid_property must include empty, water, milk, lava, honey and other");
        return new FluidTankVisual(levelName, kindName);
    }

    ImmutableBlockState apply(ImmutableBlockState state, FluidTankVisualState visual) {
        Property<Integer> level = state.getProperty(levelProperty);
        Property<String> kind = state.getProperty(fluidProperty);
        if (level == null || kind == null || level.valueClass() != Integer.class || kind.valueClass() != String.class) return state;
        Integer nextLevel = level.valueByName(Integer.toString(visual.level()));
        String nextKind = kind.valueByName(visual.kind());
        if (nextLevel == null || nextKind == null) return state;
        return state.with(level, nextLevel).with(kind, nextKind);
    }
}
