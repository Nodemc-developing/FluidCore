/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import net.momirealms.sparrow.ui.window.Window;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** A rejected owner validation must never be reported as an opened window. */
final class MenuOpening {
    private MenuOpening() {}
    static CompletableFuture<Boolean> open(boolean allowed, Supplier<CompletableFuture<Window.OpenResult>> opening) {
        if (!allowed) return CompletableFuture.completedFuture(false);
        return opening.get().thenApply(result -> result == Window.OpenResult.OPENED || result == Window.OpenResult.ALREADY_OPEN);
    }
}
