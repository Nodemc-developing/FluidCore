package com.ydxc20091.fluidcore.ui;

import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyClickSnapshotTest {
    @Test void replacementBeforeTheDeferredClickRejectsTheAction() {
        ItemStack[] inventory = { new UiActionTestStack(4) };
        LegacyClickSnapshot snapshot = LegacyClickSnapshot.capture(null, slot -> inventory[slot], 0);
        assertTrue(snapshot.matches(null, slot -> inventory[slot]));
        inventory[0] = new UiActionTestStack(1);
        assertFalse(snapshot.matches(null, slot -> inventory[slot]));
    }
    @Test void aMutableSourceAndCursorCannotChangeTheCapturedObservation() {
        UiActionTestStack source = new UiActionTestStack(4), cursor = new UiActionTestStack(1);
        LegacyClickSnapshot snapshot = LegacyClickSnapshot.capture(cursor, slot -> source, 0);
        source.payload[0] = 99;
        assertFalse(snapshot.matches(cursor, slot -> source));
        source.payload[0] = 1;
        cursor.setAmount(2);
        assertFalse(snapshot.matches(cursor, slot -> source));
    }
    @Test void anOwnerUpdateQueuedBeforeRenderingCannotRedirectAnOldIconClick() {
        UiActionTestStack displayedInput = new UiActionTestStack(4), displayedOutput = new UiActionTestStack(2);
        LegacyClickSnapshot click = LegacyClickSnapshot.capture(null, slot -> null, displayedInput, displayedOutput);
        assertTrue(click.matchesTank(displayedInput, displayedOutput));
        UiActionTestStack pendingInput = new UiActionTestStack(1);
        assertFalse(click.matchesTank(pendingInput, displayedOutput));
        UiActionTestStack pendingOutput = new UiActionTestStack(3);
        assertFalse(click.matchesTank(displayedInput, pendingOutput));
    }
}
