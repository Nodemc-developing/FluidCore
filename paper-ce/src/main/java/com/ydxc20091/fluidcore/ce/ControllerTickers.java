/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.world.BlockPos;

/** One loading-time capability selection; controller ticks use typed calls only. */
public final class ControllerTickers {
    public interface Handle<C extends BlockEntityController> {
        BlockEntityTicker<C> ticker();
        void sleep();
        void wakeUp();
        boolean isSleeping();
    }
    public interface Provider {
        <C extends BlockEntityController> Handle<C> create(BlockEntityTicker<C> delegate);
        Runnable subscribe(FluidTankController controller, BlockPos position, Runnable notification, CraftEngineBridge bridge);
    }
    private static final Provider PROVIDER = loadProvider();
    private ControllerTickers() {}
    public static void initialize() { /* Initializes the provider before content registration. */ }
    public static <C extends BlockEntityController> Handle<C> create(BlockEntityTicker<C> delegate) { return PROVIDER.create(delegate); }
    static Runnable subscribe(FluidTankController controller, BlockPos position, Runnable notification, CraftEngineBridge bridge) {
        return PROVIDER.subscribe(controller, position, notification, bridge);
    }
    private static Provider loadProvider() {
        try {
            Class.forName("net.momirealms.craftengine.core.block.entity.tick.SleepingBlockEntityTicker", false,
                    ControllerTickers.class.getClassLoader());
        } catch (ClassNotFoundException older) {
            return new Provider() {
                @Override public <C extends BlockEntityController> Handle<C> create(BlockEntityTicker<C> delegate) { return new SoftwareHandle<>(delegate); }
                @Override public Runnable subscribe(FluidTankController controller, BlockPos position, Runnable notification, CraftEngineBridge bridge) {
                    return bridge.chunkLoads().subscribe(controller.location(), position, notification);
                }
            };
        }
        try {
            return (Provider) Class.forName("com.ydxc20091.fluidcore.ce.nativeapi.NativeTickerProvider", true,
                    ControllerTickers.class.getClassLoader()).getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("Cannot initialize the CraftEngine native sleeping ticker adapter", failure);
        }
    }
    static final class SoftwareHandle<C extends BlockEntityController> implements Handle<C> {
        private final BlockEntityTicker<C> delegate;
        private boolean sleeping;
        private final BlockEntityTicker<C> ticker;
        SoftwareHandle(BlockEntityTicker<C> delegate) {
            this.delegate = java.util.Objects.requireNonNull(delegate);
            ticker = (world, position, state, controller) -> { if (!sleeping) this.delegate.tick(world, position, state, controller); };
        }
        @Override public BlockEntityTicker<C> ticker() { return ticker; }
        @Override public void sleep() { sleeping = true; }
        @Override public void wakeUp() { sleeping = false; }
        @Override public boolean isSleeping() { return sleeping; }
    }
}
