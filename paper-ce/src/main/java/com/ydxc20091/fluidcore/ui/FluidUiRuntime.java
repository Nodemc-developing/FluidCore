package com.ydxc20091.fluidcore.ui;

import net.momirealms.sparrow.ui.SparrowUI;
import org.bukkit.plugin.java.JavaPlugin;

final class FluidUiRuntime {
    private static boolean initialized;
    private FluidUiRuntime() {}
    static synchronized void initialize(JavaPlugin plugin) {
        if (!initialized) { SparrowUI.getInstance().setUp(plugin); initialized = true; }
    }
}
