package com.ydxc20091.fluidcore.async;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotWorkQueueTest {
    @Test void saturationDoesNotRunWorkOnCaller() throws Exception {
        try (var queue = new SnapshotWorkQueue(1, 1)) {
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var first = queue.submit(() -> { started.countDown(); release.await(); return 1; });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            var second = queue.submit(() -> 2);
            var ran = new AtomicBoolean();
            var rejected = queue.submit(() -> { ran.set(true); return 3; });
            assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class,
                    () -> rejected.get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(ran.get());
            release.countDown();
            assertEquals(1, first.get(3, TimeUnit.SECONDS));
            assertEquals(2, second.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void shutdownCompletesQueuedFutures() throws Exception {
        var queue = new SnapshotWorkQueue(1, 1);
        var started = new CountDownLatch(1);
        var first = queue.submit(() -> { started.countDown(); new CountDownLatch(1).await(); return 1; });
        assertTrue(started.await(3, TimeUnit.SECONDS));
        var queued = queue.submit(() -> 2);
        queue.close();
        assertTrue(first.isDone());
        assertTrue(queued.isDone());
        assertTrue(queue.submit(() -> 3).isCompletedExceptionally());
    }

    @Test void staleSnapshotNeverApplies() throws Exception {
        try (var queue = new SnapshotWorkQueue(1, 4)) {
            var applied = new AtomicBoolean();
            var future = SnapshotCoordinator.computeAndApply(queue, () -> "encoded", Runnable::run,
                    1, 1, () -> 2, () -> 1, value -> applied.set(true));
            assertInstanceOf(SnapshotCoordinator.StaleSnapshotException.class,
                    assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS)).getCause());
            assertFalse(applied.get());
        }
    }

    @Test void validSnapshotAppliesThroughOwnerExecutor() throws Exception {
        try (var queue = new SnapshotWorkQueue(1, 4)) {
            var ownerTasks = new LinkedBlockingQueue<Runnable>();
            var appliedThread = new AtomicReference<Thread>();
            var future = SnapshotCoordinator.computeAndApply(queue, () -> "encoded", ownerTasks::add,
                    1, 1, () -> 1, () -> 1, value -> appliedThread.set(Thread.currentThread()));
            Runnable task = ownerTasks.poll(3, TimeUnit.SECONDS);
            assertNotNull(task);
            task.run();
            assertEquals("encoded", future.get(3, TimeUnit.SECONDS));
            assertSame(Thread.currentThread(), appliedThread.get());
        }
    }

    @Test void shutdownCancelsResultWaitingForOwner() throws Exception {
        var queue = new SnapshotWorkQueue(1, 4);
        var ownerTasks = new LinkedBlockingQueue<Runnable>();
        var applied = new AtomicBoolean();
        var future = SnapshotCoordinator.computeAndApply(queue, () -> "encoded", ownerTasks::add,
                1, 1, () -> 1, () -> 1, value -> applied.set(true));
        Runnable task = ownerTasks.poll(3, TimeUnit.SECONDS);
        assertNotNull(task);
        queue.close();
        assertTrue(future.isDone());
        task.run();
        assertFalse(applied.get());
    }
}
