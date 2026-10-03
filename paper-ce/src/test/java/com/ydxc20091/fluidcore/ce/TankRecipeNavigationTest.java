package com.ydxc20091.fluidcore.ce;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TankRecipeNavigationTest {
    @Test void closingAnEarlierRegistrationCannotRemoveTheCurrentIntegration() {
        var registry = new TankRecipeNavigation(); var first = new Object(); var second = new Object();
        var old = registry.register(first, (player, position, back) -> {});
        var latest = registry.register(second, (player, position, back) -> {});
        old.close(); assertSame(second, registry.current().owner());
        latest.close(); assertNull(registry.current()); latest.close();
    }
    @Test void UnregisteringAnUnrelatedOwnerPreservesTheCallback() {
        var registry = new TankRecipeNavigation(); var owner = new Object(); registry.register(owner, (player, position, back) -> {});
        registry.unregisterOwner(new Object()); assertSame(owner, registry.current().owner());
        registry.unregisterOwner(owner); assertNull(registry.current());
    }
}
