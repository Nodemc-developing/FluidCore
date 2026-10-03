/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import net.momirealms.sparrow.ui.window.Window;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MenuOpeningTest {
    @Test void revokedFinalOwnerValidationDoesNotAllocateOrOpenTheWindow() {
        var attempts = new AtomicInteger();
        assertFalse(MenuOpening.open(false, () -> {
            attempts.incrementAndGet(); return CompletableFuture.completedFuture(Window.OpenResult.OPENED);
        }).join());
        assertEquals(0, attempts.get());
    }
    @Test void completionWaitsForRealSparrowResultAndUnavailableIsNotSuccess() {
        var actual = new CompletableFuture<Window.OpenResult>();
        var result = MenuOpening.open(true, () -> actual);
        assertFalse(result.isDone());
        actual.complete(Window.OpenResult.VIEWER_UNAVAILABLE);
        assertFalse(result.join());
        assertTrue(MenuOpening.open(true, () -> CompletableFuture.completedFuture(Window.OpenResult.OPENED)).join());
        assertTrue(MenuOpening.open(true, () -> CompletableFuture.completedFuture(Window.OpenResult.ALREADY_OPEN)).join());
    }
    @Test void openingFailuresRemainObservableInsteadOfBeingReportedAsSuccess() {
        var failure = new IllegalStateException("viewer disappeared during opening");
        var result = MenuOpening.open(true, () -> CompletableFuture.failedFuture(failure));
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
    }
}
