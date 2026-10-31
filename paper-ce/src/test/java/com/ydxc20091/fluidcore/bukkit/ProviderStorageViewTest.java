package com.ydxc20091.fluidcore.bukkit;

import com.ydxc20091.fluidcore.api.*;
import com.ydxc20091.fluidcore.core.FluidTank;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ProviderStorageViewTest {
    @Test void closingProviderDuringTransferRejectsCommitWithoutDisablingDelegateRollback() {
        var owner = new ParticipantTestSupport.Owner(); var live = new AtomicBoolean(true);
        var source = new FluidTank(1000, owner); var target = new FluidTank(1000, owner);
        var water = FluidVariant.of("minecraft:water"); source.fill(FluidStack.of(water, 1000), FluidAction.EXECUTE);
        var view = new ProviderStorageView(target, new StorageContext() {
            public void checkAccess() { owner.checkAccess(); if (!live.get()) throw new StorageAccessException("Provider handle closed"); }
            public long tick() { checkAccess(); return owner.tick(); }
        });
        try (var transaction = FluidTransaction.open()) {
            assertEquals(1000, source.extract(water, 1000, transaction));
            assertEquals(1000, view.insert(water, 1000, transaction)); live.set(false);
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertEquals(1000, source.amount()); assertEquals(0, target.amount());
        assertThrows(StorageAccessException.class, view::tanks);
    }

    @Test void tickChangesRejectCommitAndPreserveTheSourceAndDestination() {
        var owner = new ParticipantTestSupport.Owner(); var target = new FluidTank(1000, owner);
        var view = new ProviderStorageView(target, owner);
        try (var transaction = FluidTransaction.open()) {
            assertEquals(1000, view.insert(FluidVariant.of("minecraft:water"), 1000, transaction)); owner.tick++;
            assertThrows(StorageAccessException.class, transaction::commit);
        }
        assertTrue(target.isEmpty());
    }
}
