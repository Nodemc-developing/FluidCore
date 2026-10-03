package com.ydxc20091.fluidcore.bukkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class RegionItemHandoffTest {
    @TempDir Path directory;
    private static final byte[] ITEM = new byte[]{1,2,3,4};
    private final AtomicInteger source = new AtomicInteger(1), target = new AtomicInteger();
    private RegionItemHandoff.Source source(boolean refund) {
        return new RegionItemHandoff.Source() {
            public <T> CompletableFuture<T> onOwner(Supplier<T> operation) { try { return CompletableFuture.completedFuture(operation.get()); } catch (Throwable failure) { return CompletableFuture.failedFuture(failure); } }
            public byte[] prepare() { return source.get() > 0 ? ITEM.clone() : null; }
            public boolean reserve() { return source.compareAndSet(1, 0); }
            public boolean compensate(byte[] item) { assertArrayEquals(ITEM, item); if (refund) source.incrementAndGet(); return refund; }
            public String address() { return "source"; }
        };
    }
    private RegionItemHandoff.Destination destination(boolean preview, boolean deliver, boolean throwAfterCommit) {
        return new RegionItemHandoff.Destination() {
            public <T> CompletableFuture<T> onOwner(Supplier<T> operation) { try { return CompletableFuture.completedFuture(operation.get()); } catch (Throwable failure) { return CompletableFuture.failedFuture(failure); } }
            public boolean canAccept(byte[] item) { return preview; }
            public boolean deliver(byte[] item) { assertArrayEquals(ITEM, item); if (deliver) target.incrementAndGet(); if (throwAfterCommit) throw new IllegalStateException("Uncertain destination completion"); return deliver; }
            public String address() { return "destination"; }
        };
    }
    @Test void confirmedDeliveryMovesExactlyOneAndReleasesBoundedAdmission() throws Exception {
        try (var handoff = new RegionItemHandoff(directory, 1)) {
            assertEquals(RegionItemHandoff.Outcome.DELIVERED, handoff.transfer(source(true), destination(true,true,false)).get(5,TimeUnit.SECONDS));
            assertEquals(0, source.get()); assertEquals(1,target.get()); assertEquals(0,handoff.active());
        }
    }
    @Test void capacityPreviewRejectsBeforeSourceReservationAndIo() throws Exception {
        try (var handoff = new RegionItemHandoff(directory, 1)) {
            assertEquals(RegionItemHandoff.Outcome.REJECTED, handoff.transfer(source(true), destination(false,false,false)).get(5,TimeUnit.SECONDS));
            assertEquals(1,source.get()); assertEquals(0,target.get());
            try (var files = Files.list(directory)) { assertEquals(0,files.count()); }
        }
    }
    @Test void destinationChangedAfterPreviewReturnsReservedItemExactlyOnce() throws Exception {
        try (var handoff = new RegionItemHandoff(directory, 1)) {
            assertEquals(RegionItemHandoff.Outcome.COMPENSATED, handoff.transfer(source(true), destination(true,false,false)).get(5,TimeUnit.SECONDS));
            assertEquals(1,source.get()); assertEquals(0,target.get());
        }
    }
    @Test void retiredOrFullSourceKeepsRecoverableSerializedItem() throws Exception {
        try (var handoff = new RegionItemHandoff(directory, 1)) {
            assertEquals(RegionItemHandoff.Outcome.RECOVERY_REQUIRED, handoff.transfer(source(false), destination(true,false,false)).get(5,TimeUnit.SECONDS));
            assertEquals(0,source.get()); assertEquals(0,target.get());
            try (var files = Files.list(directory)) { Path file = files.filter(path -> path.toString().endsWith(".escrow")).findFirst().orElseThrow(); assertTrue(Files.size(file) > ITEM.length); }
        }
    }
    @Test void uncertainDestinationNeverRefundsAndDuplicatesPotentiallyCommittedItem() throws Exception {
        try (var handoff = new RegionItemHandoff(directory, 1)) {
            assertEquals(RegionItemHandoff.Outcome.RECOVERY_REQUIRED, handoff.transfer(source(true), destination(true,true,true)).get(5,TimeUnit.SECONDS));
            assertEquals(0,source.get()); assertEquals(1,target.get());
        }
    }
    @Test void queuedOwnerWorkIsBoundedAndStoppedServiceRejectsFurtherAdmission() throws Exception {
        var pending = new CompletableFuture<byte[]>();
        var delayed = new RegionItemHandoff.Source() {
            @SuppressWarnings("unchecked") public <T> CompletableFuture<T> onOwner(Supplier<T> operation) { return (CompletableFuture<T>)(CompletableFuture<?>)pending; }
            public byte[] prepare() { return ITEM; } public boolean reserve() { return true; } public boolean compensate(byte[] item) { return true; } public String address() { return "delayed"; }
        };
        var handoff = new RegionItemHandoff(directory,1);
        var operation = handoff.transfer(delayed,destination(true,true,false));
        assertEquals(RegionItemHandoff.Outcome.BUSY,handoff.transfer(source(true),destination(true,true,false)).get());
        handoff.close(); pending.complete(ITEM);
        assertEquals(RegionItemHandoff.Outcome.REJECTED,operation.get());
        assertEquals(RegionItemHandoff.Outcome.BUSY,handoff.transfer(source(true),destination(true,true,false)).get());
    }
    @Test void recoveryInspectionReadsRealPreservedStateWithoutGrantingAnItemAndRetainsMalformedRecords() throws Exception {
        try (var handoff = new RegionItemHandoff(directory, 2)) {
            assertEquals(RegionItemHandoff.Outcome.RECOVERY_REQUIRED, handoff.transfer(source(false), destination(true,false,false)).get(5,TimeUnit.SECONDS));
            var entries = handoff.inspectRecoveries(10).get(5,TimeUnit.SECONDS);
            assertEquals(1,entries.size()); assertEquals("RECOVERY_REQUIRED",entries.getFirst().state());
            assertEquals(ITEM.length,entries.getFirst().itemBytes()); assertEquals(0,source.get()); assertEquals(0,target.get());
            Path corrupt = directory.resolve(java.util.UUID.randomUUID() + ".escrow"); Files.write(corrupt,new byte[]{1,2});
            var malformed = handoff.inspectRecoveries(10).get(5,TimeUnit.SECONDS);
            assertEquals(2,malformed.size()); assertTrue(malformed.stream().anyMatch(entry -> entry.state().equals("INVALID")));
            assertTrue(Files.exists(corrupt)); assertEquals(0,source.get()); assertEquals(0,target.get());
        }
    }
    @Test void aSchedulerThrowCannotStrandAdmissionOrMutateTheSource() throws Exception {
        var unavailable = new RegionItemHandoff.Source() {
            public <T> CompletableFuture<T> onOwner(Supplier<T> operation) { throw new IllegalStateException("owner stopped"); }
            public byte[] prepare() { fail("no preparation on a stopped owner"); return ITEM; }
            public boolean reserve() { fail("no reservation on a stopped owner"); return true; }
            public boolean compensate(byte[] item) { fail("no unreserved compensation"); return true; }
            public String address() { return "unavailable"; }
        };
        try (var handoff = new RegionItemHandoff(directory, 1)) {
            assertEquals(RegionItemHandoff.Outcome.REJECTED,handoff.transfer(unavailable,destination(true,true,false)).get());
            assertEquals(0,handoff.active()); assertEquals(1,source.get()); assertEquals(0,target.get());
        }
    }
    @Test void returnLegKeepsJournalAndAdmissionUntilOwnerConfirmsReceipt() throws Exception {
        var returned = new CompletableFuture<Boolean>();
        var arrived = new CountDownLatch(1);
        var destination = returningDestination(returned, arrived);
        try (var handoff = new RegionItemHandoff(directory, 1)) {
            var operation = handoff.transfer(source(true), destination);
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            assertFalse(operation.isDone()); assertEquals(1, handoff.active());
            assertEquals(0, source.get()); assertEquals(1, target.get());
            assertEquals(1, handoff.inspectRecoveries(10).get(5, TimeUnit.SECONDS).size());
            assertEquals(RegionItemHandoff.Outcome.BUSY, handoff.transfer(source(true), destination(true, true, false)).get());
            returned.complete(true);
            assertEquals(RegionItemHandoff.Outcome.DELIVERED, operation.get(5, TimeUnit.SECONDS));
            assertEquals(0, handoff.active());
        }
    }
    @Test void failedReturnLegNeverRefundsAnAlreadyCommittedDestination() throws Exception {
        try (var handoff = new RegionItemHandoff(directory, 1)) {
            var operation = handoff.transfer(source(true), returningDestination(CompletableFuture.completedFuture(false), new CountDownLatch(1)));
            assertEquals(RegionItemHandoff.Outcome.RECOVERY_REQUIRED, operation.get(5, TimeUnit.SECONDS));
            assertEquals(0, source.get()); assertEquals(1, target.get());
            var recovery = handoff.inspectRecoveries(10).get(5, TimeUnit.SECONDS);
            assertEquals(1, recovery.size()); assertEquals("RECOVERY_REQUIRED", recovery.getFirst().state());
        }
    }
    @Test void uncertainReturnLegRetainsPayloadWithoutCreatingASecondSourceItem() throws Exception {
        try (var handoff = new RegionItemHandoff(directory, 1)) {
            var returned = CompletableFuture.<Boolean>failedFuture(new IllegalStateException("retired returning owner"));
            assertEquals(RegionItemHandoff.Outcome.RECOVERY_REQUIRED,
                    handoff.transfer(source(true), returningDestination(returned, new CountDownLatch(1))).get(5, TimeUnit.SECONDS));
            assertEquals(0, source.get()); assertEquals(1, target.get());
            assertEquals(ITEM.length, handoff.inspectRecoveries(10).get(5, TimeUnit.SECONDS).getFirst().itemBytes());
        }
    }
    private RegionItemHandoff.Destination returningDestination(CompletableFuture<Boolean> returned, CountDownLatch arrived) {
        return new RegionItemHandoff.Destination() {
            public <T> CompletableFuture<T> onOwner(Supplier<T> operation) { return CompletableFuture.completedFuture(operation.get()); }
            public boolean deliver(byte[] payload) { assertArrayEquals(ITEM, payload); target.incrementAndGet(); return true; }
            public CompletableFuture<Boolean> afterDelivery(byte[] payload) { assertArrayEquals(ITEM, payload); arrived.countDown(); return returned; }
            public String address() { return "returning-destination"; }
        };
    }
}
