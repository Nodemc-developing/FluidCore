package com.ydxc20091.fluidcore.ce;

import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ControllerTickersTest {
    @Test void factoryUsesNativeRegistrationOnlyWhenTheRuntimeActuallyProvidesIt() {
        AtomicInteger executions = new AtomicInteger();
        var handle = ControllerTickers.<BlockEntityController>create((world, position, state, controller) -> executions.incrementAndGet());
        boolean nativeAvailable;
        try { Class.forName("net.momirealms.craftengine.core.block.entity.tick.SleepingBlockEntityTicker"); nativeAvailable = true; }
        catch (ClassNotFoundException absent) { nativeAvailable = false; }
        assertEquals(nativeAvailable, handle.ticker().getClass().getName().equals("net.momirealms.craftengine.core.block.entity.tick.SleepingBlockEntityTicker"));
        handle.sleep(); handle.ticker().tick(null, null, null, null); assertEquals(0, executions.get());
        handle.wakeUp(); handle.ticker().tick(null, null, null, null); assertEquals(1, executions.get());
    }
    @Test void anOlderCeTickerSkipsBusinessUntilAnExternalMutationWakesIt() {
        AtomicInteger executions = new AtomicInteger();
        var handle = new ControllerTickers.SoftwareHandle<BlockEntityController>((world, position, state, controller) -> executions.incrementAndGet());
        handle.ticker().tick(null, null, null, null);
        assertEquals(1, executions.get());
        handle.sleep(); handle.sleep();
        for (int tick = 0; tick < 100; tick++) handle.ticker().tick(null, null, null, null);
        assertTrue(handle.isSleeping()); assertEquals(1, executions.get());
        handle.wakeUp(); handle.wakeUp();
        handle.ticker().tick(null, null, null, null);
        assertFalse(handle.isSleeping()); assertEquals(2, executions.get());
    }
}
