/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.bukkit.ContainerDefinition;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.item.behavior.ItemBehavior;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifier;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.ConfigValue;
import org.bukkit.Bukkit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit configuration adapters create only new FluidCore data; they never convert foreign saves. */
public final class FluidContentFactories {
    private FluidContentFactories() {}
    private static CraftEngineBridge bridge() {
        CraftEngineBridge bridge = Bukkit.getServicesManager().load(CraftEngineBridge.class);
        if (bridge == null) {
            // Behavior factories are invoked during the first CE parse, before services are published.
            var plugin = Bukkit.getPluginManager().getPlugin("FluidCore");
            if (plugin instanceof com.ydxc20091.fluidcore.FluidCorePlugin core) bridge = core.bridge();
        }
        if (bridge == null) throw new IllegalStateException("FluidCore must be loaded before fluid content is parsed");
        return bridge;
    }
    public static FluidTankBehavior tank(BlockDefinition block, ConfigSection configuration) {
        return FluidTankBehavior.create(block, configuration, bridge());
    }
    public static FluidTankBehavior tank(BlockDefinition block, ConfigSection configuration, Map<String, Object> menuDefaults) {
        return tank(block, withMenuDefaults(configuration, menuDefaults));
    }

    /** Resolves presentation defaults on a private loading copy, preserving explicitly configured fields. */
    public static ConfigSection withMenuDefaults(ConfigSection configuration, Map<String, Object> defaults) {
        Map<String, Object> fields = copyMap(configuration.values());
        Object configured = fields.get("menu");
        if (fields.containsKey("menu") && !(configured instanceof Map<?, ?>))
            throw new IllegalArgumentException("Tank menu must be a section at " + configuration.assemblePath("menu"));
        Map<String, Object> menu = copyMap(defaults);
        if (configured instanceof Map<?, ?> selected) menu.putAll(copyMap(selected));
        fields.put("menu", menu);
        return configuration.withSamePath(fields);
    }
    private static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), copy(value)));
        return result;
    }
    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) return copyMap(map);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(entry -> result.add(copy(entry)));
            return result;
        }
        return value;
    }
    public static ItemBehavior containerBehavior(ConfigSection configuration) {
        if (configuration.containsKey("model")) {
            String model = configuration.getNonEmptyString("model");
            if (!model.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid container item model");
        }
        String itemModel = configuration.getString("item-model", (String) null);
        if (itemModel != null && !itemModel.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid generated container item-model");
        if (configuration.containsKey("model") && itemModel == null)
            throw new IllegalArgumentException("Container model requires its loading-stage visual item definitions");
        return new FluidContainerBehavior(bridge(), itemModel);
    }
    public static ItemSettingsModifier containerSetting(ConfigValue configuration) {
        ContainerDefinition definition = ContainerSettings.parse(configuration.getAsSection());
        return settings -> settings.addCustomData(ContainerSettings.TYPE, definition);
    }
}
