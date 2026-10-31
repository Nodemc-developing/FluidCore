/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;

/** One immutable checkpoint is published for CE's save workers, including both inventory slots. */
record TankSaveSnapshot(byte[] fluid, byte[] input, byte[] output, int glassColor,
                        Tag malformed, CompoundTag foreign) {
    TankSaveSnapshot {
        fluid = fluid.clone();
        input = input == null ? null : input.clone();
        output = output == null ? null : output.clone();
        malformed = malformed == null ? null : malformed.deepClone();
        foreign = foreign == null ? null : foreign.deepClone();
    }
    @Override public byte[] fluid() { return fluid.clone(); }
    @Override public byte[] input() { return input == null ? null : input.clone(); }
    @Override public byte[] output() { return output == null ? null : output.clone(); }
    @Override public Tag malformed() { return malformed == null ? null : malformed.deepClone(); }
    @Override public CompoundTag foreign() { return foreign == null ? null : foreign.deepClone(); }

    void write(CompoundTag target) {
        if (foreign != null) {
            foreign.entrySet().forEach(entry -> target.put(entry.getKey(), entry.getValue().deepClone()));
            return;
        }
        if (malformed == null) target.putByteArray(FluidTankController.DATA_KEY, fluid.clone());
        else target.put(FluidTankController.DATA_KEY, malformed.deepClone());
        target.putInt("fluidcore:glass_color", glassColor);
        if (input != null) target.putByteArray("fluidcore:input", input.clone());
        if (output != null) target.putByteArray("fluidcore:output", output.clone());
    }
}
