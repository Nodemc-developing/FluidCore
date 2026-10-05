/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce.nativeapi;

import com.ydxc20091.fluidcore.ce.ControllerTickers;
import com.ydxc20091.fluidcore.ce.CraftEngineBridge;
import com.ydxc20091.fluidcore.ce.FluidTankController;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.block.entity.tick.SleepingBlockEntityTicker;
import net.momirealms.craftengine.core.world.BlockPos;

/** Loaded only when CE provides native inactive-list sleep and chunk subscriptions. */
public final class NativeTickerProvider implements ControllerTickers.Provider {
    public NativeTickerProvider() {}
    @Override public <C extends BlockEntityController> ControllerTickers.Handle<C> create(BlockEntityTicker<C> delegate) {
        SleepingBlockEntityTicker<C> nativeTicker = new SleepingBlockEntityTicker<>(delegate);
        return new ControllerTickers.Handle<>() {
            @Override public BlockEntityTicker<C> ticker() { return nativeTicker; }
            @Override public void sleep() { nativeTicker.sleep(); }
            @Override public void wakeUp() { nativeTicker.wakeUp(); }
            @Override public boolean isSleeping() { return nativeTicker.isSleeping(); }
        };
    }
    @Override public Runnable subscribe(FluidTankController controller, BlockPos position, Runnable notification, CraftEngineBridge bridge) {
        var subscription = controller.subscribeChunkLoad(position, notification);
        return subscription::cancel;
    }
}
