/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import com.ydxc20091.fluidcore.api.FluidStack;
import java.math.BigInteger;

record FluidTankVisualState(int level, String kind) {
    static FluidTankVisualState of(FluidStack content, long capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Tank capacity must be positive");
        if (content.isEmpty()) return new FluidTankVisualState(0, "empty");
        long amount = content.amount();
        int level;
        if (amount >= capacity) level = 16;
        else if (amount <= Long.MAX_VALUE / 16) {
            long scaled = amount * 16;
            level = (int) (scaled / capacity + (scaled % capacity == 0 ? 0 : 1));
        } else {
            BigInteger divisor = BigInteger.valueOf(capacity);
            level = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(16))
                    .add(divisor.subtract(BigInteger.ONE)).divide(divisor).intValueExact();
        }
        String kind = switch (content.variant().fluid().toString()) {
            case "minecraft:water" -> "water";
            case "minecraft:milk" -> "milk";
            case "minecraft:lava" -> "lava";
            case "minecraft:honey" -> "honey";
            default -> "other";
        };
        return new FluidTankVisualState(level, kind);
    }
}
