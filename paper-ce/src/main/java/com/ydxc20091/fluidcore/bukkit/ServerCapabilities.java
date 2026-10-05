/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.bukkit;

import org.bukkit.Bukkit;

/** Client item models and the Sparrow packet backend start at Minecraft 1.21.4. */
public final class ServerCapabilities {
    private ServerCapabilities() {}
    public static boolean modernItems() { return structuredModels(Bukkit.getMinecraftVersion()); }
    public static boolean structuredModels(String version) {
        String[] parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        if (major != 1) return major >= 26;
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        return minor > 21 || minor == 21 && parts.length > 2 && Integer.parseInt(parts[2]) >= 4;
    }
}
