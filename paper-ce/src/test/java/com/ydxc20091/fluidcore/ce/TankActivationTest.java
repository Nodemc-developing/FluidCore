package com.ydxc20091.fluidcore.ce;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TankActivationTest {
    @Test void cachedControllerReactivationCannotReviveOldLease() {
        TankActivation controller = new TankActivation();
        assertFalse(controller.matches(controller.version()));
        controller.activate();
        long oldLease = controller.version();
        assertTrue(controller.matches(oldLease));
        controller.retire();
        assertFalse(controller.matches(oldLease));
        long unloaded = controller.version();
        controller.activate();
        assertTrue(controller.version() > unloaded);
        assertTrue(controller.matches(controller.version()));
        assertFalse(controller.matches(oldLease));
        assertFalse(controller.matches(unloaded));
    }

    @Test void repeatedLifecycleNotificationsInvalidateEveryPriorLease() {
        TankActivation controller = new TankActivation();
        controller.activate();
        long first = controller.version();
        controller.activate();
        assertFalse(controller.matches(first));
        long second = controller.version();
        controller.retire();
        controller.retire();
        assertFalse(controller.matches(second));
        assertFalse(controller.active());
        assertTrue(controller.version() > second);
    }
}
