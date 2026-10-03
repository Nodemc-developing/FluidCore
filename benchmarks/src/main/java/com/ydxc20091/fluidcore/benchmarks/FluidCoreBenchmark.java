/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.benchmarks;

import com.ydxc20091.fluidcore.api.ComponentValue;
import com.ydxc20091.fluidcore.api.ComponentCodec;
import com.ydxc20091.fluidcore.api.FluidAction;
import com.ydxc20091.fluidcore.api.FluidKey;
import com.ydxc20091.fluidcore.api.FluidStack;
import com.ydxc20091.fluidcore.api.FluidVariant;
import com.ydxc20091.fluidcore.api.StorageContext;
import com.ydxc20091.fluidcore.core.FluidRegistry;
import com.ydxc20091.fluidcore.core.FluidStackCodec;
import com.ydxc20091.fluidcore.core.FluidTank;
import com.ydxc20091.fluidcore.core.FluidTransfers;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Measures fixed-quantity operations; execution benchmarks include their restoring operation. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class FluidCoreBenchmark {
    @State(Scope.Thread)
    public static class Data {
        @Param({"0", "4", "16"}) public int components;
        @Param({"success", "rejected", "partial"}) public String scenario;
        FluidVariant variant;
        FluidVariant equalVariant;
        FluidVariant differentVariant;
        FluidStack requested;
        FluidTank fill;
        FluidTank drain;
        FluidTank source;
        FluidTank destination;
        FluidStackCodec codec;
        byte[] encoded;

        @Setup(Level.Trial)
        public void setup() {
            var values = new HashMap<FluidKey, ComponentValue>();
            for (int i = 0; i < components; i++) {
                values.put(FluidKey.of("benchmark:component_" + i), ComponentValue.of(new byte[]{(byte) i, 1, 2, 3}));
            }
            variant = FluidVariant.of(FluidKey.of("minecraft:water"), values);
            equalVariant = FluidVariant.of(FluidKey.of("minecraft:water"), new HashMap<>(values));
            differentVariant = components == 0 ? FluidVariant.of("minecraft:lava")
                    : variant.withComponent(FluidKey.of("benchmark:component_0"), ComponentValue.of(new byte[]{127}));
            requested = FluidStack.of(variant, 1000);
            var context = StorageContext.confinedToCurrentThread();
            fill = new FluidTank(4000, context);
            drain = new FluidTank(4000, context);
            source = new FluidTank(4000, context);
            destination = new FluidTank(4000, context);
            long occupied = switch (scenario) { case "success" -> 0; case "partial" -> 3500; default -> 4000; };
            long drainable = switch (scenario) { case "success" -> 4000; case "partial" -> 500; default -> 0; };
            fill.restore(FluidStack.of(variant, occupied));
            drain.restore(FluidStack.of(variant, drainable));
            source.restore(FluidStack.of(variant, 4000));
            destination.restore(FluidStack.of(variant, occupied));
            var registry = new FluidRegistry();
            for (var component : values.keySet()) {
                registry.registerComponentCodec("benchmark", component, new ComponentCodec<byte[]>() {
                    @Override public byte[] encode(byte[] value) { return value.clone(); }
                    @Override public byte[] decode(byte[] encoded) { return encoded.clone(); }
                });
            }
            codec = new FluidStackCodec(registry);
            encoded = codec.encode(requested);
            if (!codec.decode(encoded).usable()) throw new IllegalStateException("Benchmark must decode known component values successfully");
        }

        @TearDown(Level.Trial)
        public void checkConservation() {
            long expected = switch (scenario) { case "success" -> 0; case "partial" -> 3500; default -> 4000; };
            if (fill.content(0).amount() != expected || destination.content(0).amount() != expected
                    || source.content(0).amount() != 4000) {
                throw new IllegalStateException("Execution benchmark failed to restore its initial state");
            }
        }
    }

    @Benchmark public long fillSimulation(Data data) {
        return data.fill.fill(data.requested, FluidAction.SIMULATE);
    }

    @Benchmark public long fillAndRestore(Data data) {
        long inserted = data.fill.fill(data.requested, FluidAction.EXECUTE);
        data.fill.drain(data.variant, inserted, FluidAction.EXECUTE);
        return inserted;
    }

    @Benchmark public long drainSimulation(Data data) {
        return data.drain.drain(data.variant, 1000, FluidAction.SIMULATE).amount();
    }

    @Benchmark public long drainAndRestore(Data data) {
        FluidStack extracted = data.drain.drain(data.variant, 1000, FluidAction.EXECUTE);
        data.drain.fill(extracted, FluidAction.EXECUTE);
        return extracted.amount();
    }

    @Benchmark public long transferSimulation(Data data) {
        return FluidTransfers.move(data.source, data.destination, data.variant, 1000, FluidAction.SIMULATE).amount();
    }

    @Benchmark public long transferAndRestore(Data data) {
        long moved = FluidTransfers.move(data.source, data.destination, data.variant, 1000, FluidAction.EXECUTE).amount();
        FluidTransfers.move(data.destination, data.source, data.variant, moved, FluidAction.EXECUTE);
        return moved;
    }

    @Benchmark public boolean equalComponents(Data data) { return data.variant.equals(data.equalVariant); }
    @Benchmark public boolean unequalComponents(Data data) { return data.variant.equals(data.differentVariant); }
    @Benchmark public byte[] encode(Data data) { return data.codec.encode(data.requested); }
    @Benchmark public Object decode(Data data) { return data.codec.decode(data.encoded); }
}
