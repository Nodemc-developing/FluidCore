/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ui;

import java.util.LinkedHashSet;
import java.util.List;

/** Shared protocol positions for a four-row tank window and its actual player storage slots. */
final class TankMenuLayout {
    static final int WIDTH = 9, ROWS = 4, SIZE = WIDTH * ROWS, PLAYER_SIZE = 36, TOTAL_SIZE = SIZE + PLAYER_SIZE;
    static final int INPUT = 2, PROGRESS = 11, FLUID = 13, BUCKETS = 24, OUTPUT = 29, BOTTLES = 33;
    private TankMenuLayout() { }

    static int playerStorageSlot(int rawSlot) {
        if (rawSlot < SIZE || rawSlot >= TOTAL_SIZE) throw new IllegalArgumentException("Not a player storage window slot: " + rawSlot);
        return (rawSlot - SIZE + 9) % PLAYER_SIZE;
    }
    static boolean playerSlot(int rawSlot) { return rawSlot >= SIZE && rawSlot < TOTAL_SIZE; }
    static boolean decoration(int rawSlot) { return rawSlot >= 0 && rawSlot < SIZE && rawSlot != INPUT && rawSlot != OUTPUT; }

    record DragTargets(boolean includesInput, List<Integer> playerSlots) { }
    static DragTargets dragTargets(Iterable<Integer> path) {
        boolean input = false;
        var player = new LinkedHashSet<Integer>();
        for (int raw : path) {
            if (raw == INPUT) input = true;
            else if (playerSlot(raw)) player.add(playerStorageSlot(raw));
            else return null;
        }
        return input || !player.isEmpty() ? new DragTargets(input, List.copyOf(player)) : null;
    }

    static int progressStage(long elapsed, long total) {
        if (total <= 0) return 0;
        if (elapsed <= 0) return 0;
        if (elapsed >= total) return 24;
        int lower = 1, upper = 24;
        while (lower < upper) {
            int middle = (lower + upper) >>> 1;
            long threshold = (total / 24) * middle + (total % 24) * middle / 24;
            if (elapsed <= threshold) upper = middle; else lower = middle + 1;
        }
        return lower;
    }
    static int units(long amount, long unitSize) { return (int) Math.min(99, Math.max(0, amount) / unitSize); }
    static int displayAmount(int units) { return Math.max(1, units); }
}
