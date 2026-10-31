package com.ydxc20091.fluidcore.ce;

import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegionChangeBatcherTest {
    @Test void repeatedChangesShareOneRegionTaskAndKeepLatestActivation() {
        var jobs = new ArrayDeque<Runnable>();
        var seen = new ArrayList<String>();
        var queue = new RegionChangeBatcher<String, String>(2, (region, job) -> jobs.add(job),
                (value, activation) -> seen.add(value + ":" + activation), failure -> fail(failure));
        queue.mark("chunk", "tank", 1);
        queue.mark("chunk", "tank", 2);
        queue.mark("chunk", "other", 3);
        assertEquals(1, jobs.size()); assertTrue(seen.isEmpty());
        jobs.remove().run();
        assertEquals(List.of("tank:2", "other:3"), seen);
        assertEquals(0, queue.pendingRegions()); assertTrue(jobs.isEmpty());
    }

    @Test void drainingIsBoundedAndReentrantChangesAreNotLost() {
        var jobs = new ArrayDeque<Runnable>();
        var seen = new ArrayList<String>();
        @SuppressWarnings("unchecked") RegionChangeBatcher<String, String>[] holder = new RegionChangeBatcher[1];
        holder[0] = new RegionChangeBatcher<>(1, (region, job) -> jobs.add(job), (value, activation) -> {
            seen.add(value);
            if (value.equals("first")) holder[0].mark("chunk", "late", 1);
        }, failure -> fail(failure));
        holder[0].mark("chunk", "first", 1); holder[0].mark("chunk", "second", 1);
        jobs.remove().run();
        assertEquals(List.of("first"), seen); assertEquals(1, jobs.size());
        jobs.remove().run(); jobs.remove().run();
        assertEquals(List.of("first", "second", "late"), seen);
        assertEquals(0, holder[0].pendingRegions());
    }

    @Test void closeInvalidatesQueuedCallbacksAndFailuresDoNotSkipOtherTanks() {
        var jobs = new ArrayDeque<Runnable>(); var seen = new ArrayList<String>();
        var errors = new ArrayList<RuntimeException>();
        var queue = new RegionChangeBatcher<String, String>(8, (region, job) -> jobs.add(job), (value, activation) -> {
            if (value.equals("broken")) throw new IllegalStateException("fixture");
            seen.add(value);
        }, errors::add);
        queue.mark("a", "broken", 1); queue.mark("a", "good", 1);
        jobs.remove().run(); assertEquals(List.of("good"), seen); assertEquals(1, errors.size());
        queue.mark("a", "closed", 1); queue.close(); jobs.remove().run();
        assertEquals(List.of("good"), seen); assertEquals(0, queue.pendingRegions());
    }
}
