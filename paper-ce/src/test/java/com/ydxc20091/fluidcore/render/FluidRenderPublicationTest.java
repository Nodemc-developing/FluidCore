package com.ydxc20091.fluidcore.render;

import com.ydxc20091.fluidcore.api.FluidKey;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FluidRenderPublicationTest {
    private static final FluidKey GAUGE = FluidKey.of("addon:gauge"), CELL = FluidKey.of("addon:cell");

    @Test void failedRebuildCannotReuseThePreviousPacksAvailabilityAtTheSameRegistryGeneration() {
        var state = new FluidRenderPublication();
        long successful = state.begin(); assertTrue(state.publish(successful, 5, List.of(GAUGE)));
        assertTrue(state.available(GAUGE, 5));
        state.begin(); // Resolver/I/O failure or an early return prevents publication.
        assertFalse(state.available(GAUGE, 5));
    }

    @Test void anOlderSuccessfulCompileCannotResurrectAssetsAfterANewerAttemptHasStarted() {
        var state = new FluidRenderPublication();
        long older = state.begin(), newer = state.begin();
        assertFalse(state.publish(older, 5, List.of(GAUGE)));
        assertFalse(state.available(GAUGE, 5));
        assertTrue(state.publish(newer, 5, List.of(CELL)));
        assertFalse(state.publish(older, 5, List.of(GAUGE)));
        assertTrue(state.available(CELL, 5)); assertFalse(state.available(GAUGE, 5));
    }

    @Test void aSuccessfulRetryPublishesOnlyItsOwnStylesAndRegistryGeneration() {
        var state = new FluidRenderPublication();
        long first = state.begin(); state.publish(first, 5, List.of(GAUGE, CELL));
        state.begin(); // Failed middle rebuild.
        long retry = state.begin(); assertTrue(state.publish(retry, 6, List.of(CELL)));
        assertFalse(state.available(GAUGE, 5)); assertFalse(state.available(CELL, 5));
        assertFalse(state.available(GAUGE, 6)); assertTrue(state.available(CELL, 6));
    }

    @Test void withdrawnProvidersCannotBeReintroducedByTheirCapturedPackAttempt() {
        var state = new FluidRenderPublication();
        long initial = state.begin(); state.publish(initial, 5, List.of(GAUGE, CELL));
        state.remove(GAUGE);
        assertFalse(state.available(GAUGE, 5)); assertTrue(state.available(CELL, 5));
        long inFlight = state.begin(); state.retain(List.of(CELL));
        assertFalse(state.publish(inFlight, 5, List.of(GAUGE, CELL)));
        assertFalse(state.available(GAUGE, 5)); assertFalse(state.available(CELL, 5));
    }
}
