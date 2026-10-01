/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.config;

import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.route.Route;
import java.nio.file.Path;
import java.io.IOException;

public record FluidCoreSettings(int workers, int queueCapacity, boolean diagnosticUi) {
    public FluidCoreSettings {
        if (workers < 1 || workers > 64) throw new IllegalArgumentException("async.workers must be 1..64");
        if (queueCapacity < 1 || queueCapacity > 1_000_000)
            throw new IllegalArgumentException("async.queue-capacity must be 1..1000000");
    }

    public static FluidCoreSettings load(Path path) throws IOException {
        var document = SparrowYaml.builder().setAllowDuplicateKeys(false).build().load(path);
        if (document.getIntOrDefault(1, Route.from("config-version")) != 1)
            throw new IllegalArgumentException("Unsupported config-version; existing file was preserved");
        return new FluidCoreSettings(
                document.getIntOrDefault(2, Route.from("async", "workers")),
                document.getIntOrDefault(1024, Route.from("async", "queue-capacity")),
                document.getBooleanOrDefault(true, Route.from("diagnostics", "ui")));
    }
}
