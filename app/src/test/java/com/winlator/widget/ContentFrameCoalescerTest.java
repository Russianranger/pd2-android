package com.winlator.widget;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public final class ContentFrameCoalescerTest {
    private static final class Fixture implements ContentFrameCoalescer.Scheduler {
        long now;
        int version, maxPending;
        boolean acceptPosts = true;
        final List<Long> draws = new ArrayList<>();
        final List<Integer> drawnVersions = new ArrayList<>();
        final LinkedHashMap<Runnable, Long> jobs = new LinkedHashMap<>();
        final ContentFrameCoalescer frames;

        Fixture(int fps) {
            frames = new ContentFrameCoalescer(fps, () -> now, this, this::draw);
            frames.setAttached(true);
        }

        private void draw() { draws.add(now); drawnVersions.add(version); }

        @Override public boolean postDelayed(Runnable callback, long milliseconds) {
            assertTrue("Callbacks must never spin at a zero delay", milliseconds > 0);
            if (!acceptPosts) return false;
            assertFalse("The same callback must not be posted twice", jobs.containsKey(callback));
            jobs.put(callback, now + milliseconds * 1_000_000L);
            maxPending = Math.max(maxPending, jobs.size());
            assertEquals("At most one content callback may be queued", 1, jobs.size());
            return true;
        }

        @Override public void remove(Runnable callback) { jobs.remove(callback); }

        Runnable pending() { assertEquals(1, jobs.size()); return jobs.keySet().iterator().next(); }

        void next() {
            Map.Entry<Runnable, Long> entry = jobs.entrySet().iterator().next();
            Runnable callback = entry.getKey();
            now = Math.max(now, entry.getValue());
            jobs.remove(callback);
            callback.run();
        }

        void request(int value) { version = value; frames.request(); }
    }

    @Test public void aBurstKeepsOneCallbackAndRendersTheLatestContentWithoutAnIdleLoop() {
        Fixture fixture = new Fixture(60);
        for (int i = 1; i <= 1000; i++) fixture.request(i);
        assertEquals(1, fixture.draws.size());
        assertEquals(1, fixture.jobs.size());
        fixture.next();
        assertEquals(2, fixture.draws.size());
        assertEquals(Integer.valueOf(1000), fixture.drawnVersions.get(1));
        assertTrue(fixture.jobs.isEmpty());
        ContentFrameCoalescer.Snapshot stats = fixture.frames.snapshot();
        assertEquals(1000, stats.requests);
        assertEquals(998, stats.coalesced);
        assertEquals(2, stats.forwarded);
        assertEquals(1, stats.deferredCallbacks);
        assertEquals(1, fixture.maxPending);
        assertFalse(stats.dirty);
    }

    @Test public void handlerMillisecondRoundingRetainsSixtyFpsPhaseInsteadOfDriftingToSeventeenMs() {
        Fixture fixture = new Fixture(60);
        fixture.request(0);
        for (int i = 1; i <= 60; i++) { fixture.request(i); fixture.next(); }
        assertEquals(61, fixture.draws.size());
        assertTrue(fixture.now >= 1_000_000_000L);
        assertTrue("Rounding must not turn 60 intervals into 1020ms", fixture.now <= 1_001_000_000L);
        assertTrue(fixture.jobs.isEmpty());
    }

    @Test public void thirtyFpsSchedulesOnlyTheRemainingInterval() {
        Fixture fixture = new Fixture(30);
        fixture.request(1);
        fixture.now = 10_000_000L;
        fixture.request(2);
        assertEquals(Long.valueOf(34_000_000L), fixture.jobs.get(fixture.pending()));
        fixture.next();
        assertEquals(Integer.valueOf(2), fixture.drawnVersions.get(1));
        assertEquals(2, fixture.draws.size());
    }

    @Test public void pausedRequestsKeepLatestContentAndOldCallbacksCannotStealAResumedRequest() {
        Fixture fixture = new Fixture(60);
        fixture.request(1);
        fixture.request(2);
        Runnable obsolete = fixture.pending();
        fixture.frames.setResumed(false);
        assertTrue(fixture.jobs.isEmpty());
        for (int i = 3; i <= 100; i++) fixture.request(i);
        assertEquals(1, fixture.draws.size());
        fixture.frames.setResumed(true);
        assertEquals(2, fixture.draws.size());
        assertEquals(Integer.valueOf(100), fixture.drawnVersions.get(1));
        fixture.request(101);
        Runnable current = fixture.pending();
        obsolete.run();
        assertEquals(2, fixture.draws.size());
        assertSame(current, fixture.pending());
        fixture.next();
        assertEquals(Integer.valueOf(101), fixture.drawnVersions.get(2));
        assertTrue(fixture.jobs.isEmpty());
    }

    @Test public void bothAttachmentAndResumeAreRequiredAndReattachmentRedrawsOnlyOnce() {
        Fixture fixture = new Fixture(60);
        fixture.frames.setAttached(false);
        fixture.request(1);
        fixture.frames.setResumed(false);
        fixture.frames.setAttached(true);
        assertTrue(fixture.draws.isEmpty());
        assertTrue(fixture.jobs.isEmpty());
        fixture.frames.setResumed(true);
        assertEquals(1, fixture.draws.size());
        fixture.frames.setResumed(true);
        fixture.frames.setAttached(true);
        assertEquals(1, fixture.draws.size());
        fixture.request(2);
        Runnable obsolete = fixture.pending();
        fixture.frames.setAttached(false);
        fixture.request(3);
        fixture.frames.setAttached(true);
        obsolete.run();
        assertEquals(2, fixture.draws.size());
        assertEquals(Integer.valueOf(3), fixture.drawnVersions.get(1));
        assertTrue(fixture.jobs.isEmpty());
    }

    @Test public void anImmediateCursorOrControlFrameCancelsItsDelayedContentDuplicate() {
        Fixture fixture = new Fixture(30);
        fixture.request(1);
        fixture.request(2);
        Runnable obsolete = fixture.pending();
        fixture.now = 5_000_000L;
        fixture.frames.immediate();
        fixture.draw(); // XServerView forwards this urgent frame directly to GLSurfaceView.
        assertEquals(Integer.valueOf(2), fixture.drawnVersions.get(1));
        assertTrue(fixture.jobs.isEmpty());
        obsolete.run();
        assertEquals(2, fixture.draws.size());
        fixture.request(3);
        fixture.next();
        assertEquals(3, fixture.draws.size());
        assertEquals(1, fixture.frames.snapshot().immediateRequests);
        assertEquals(2, fixture.frames.snapshot().forwarded);
    }

    @Test public void gameSettingsUnlimitedAndUnknownModesDoNotDelayContent() {
        for (int fps : new int[]{-1, 0, -100, 25, 120, Integer.MAX_VALUE}) {
            Fixture fixture = new Fixture(fps);
            for (int i = 0; i < 5; i++) fixture.request(i);
            assertEquals(5, fixture.draws.size());
            assertEquals(0, fixture.frames.snapshot().fps);
            assertTrue(fixture.jobs.isEmpty());
        }
    }

    @Test public void disablingTheLimitFlushesPendingContentAndInvalidatesItsOldCallback() {
        Fixture fixture = new Fixture(60);
        fixture.request(1);
        fixture.request(2);
        Runnable obsolete = fixture.pending();
        fixture.frames.setLimit(-1);
        assertEquals(2, fixture.draws.size());
        assertEquals(Integer.valueOf(2), fixture.drawnVersions.get(1));
        obsolete.run();
        fixture.request(3);
        assertEquals(3, fixture.draws.size());
        assertTrue(fixture.jobs.isEmpty());
    }

    @Test public void earlyCallbacksRescheduleOnceAndLongStallsDoNotBuildCatchupWork() {
        Fixture fixture = new Fixture(60);
        fixture.request(1);
        fixture.request(2);
        Runnable early = fixture.pending();
        fixture.jobs.remove(early);
        fixture.now = 1_000_000L;
        early.run();
        assertEquals(1, fixture.draws.size());
        assertEquals(1, fixture.jobs.size());
        fixture.now = 1_000_000_000L;
        fixture.next();
        assertEquals(2, fixture.draws.size());
        assertTrue(fixture.jobs.isEmpty());
        fixture.request(3);
        assertEquals(2, fixture.draws.size());
        fixture.next();
        assertEquals(3, fixture.draws.size());
        assertTrue(fixture.draws.get(2) > 1_000_000_000L);
    }

    @Test public void rejectedPostsKeepTheLatestDirtyContentForARetry() {
        Fixture fixture = new Fixture(60);
        fixture.request(1);
        fixture.acceptPosts = false;
        fixture.request(2);
        assertTrue(fixture.jobs.isEmpty());
        assertTrue(fixture.frames.snapshot().dirty);
        assertEquals(1, fixture.frames.snapshot().postFailures);
        fixture.acceptPosts = true;
        fixture.request(3);
        fixture.next();
        assertEquals(Integer.valueOf(3), fixture.drawnVersions.get(1));
    }

    @Test public void closeCancelsADequeuedCallbackAndPreventsAllLaterLifecycleRequests() {
        Fixture fixture = new Fixture(60);
        fixture.request(1);
        fixture.request(2);
        Runnable obsolete = fixture.pending();
        fixture.frames.close();
        assertTrue(fixture.jobs.isEmpty());
        obsolete.run();
        fixture.frames.setAttached(false);
        fixture.frames.setResumed(false);
        fixture.frames.setAttached(true);
        fixture.frames.setResumed(true);
        fixture.frames.setLimit(30);
        fixture.frames.immediate();
        fixture.request(3);
        fixture.frames.close();
        assertEquals(1, fixture.draws.size());
        assertTrue(fixture.jobs.isEmpty());
        assertTrue(fixture.frames.snapshot().closed);
        assertFalse(fixture.frames.snapshot().dirty);
    }

    @Test public void aBackwardsClockCannotCreateAnOversizedDelay() {
        Fixture fixture = new Fixture(60);
        fixture.now = 1_000_000_000L;
        fixture.request(1);
        fixture.request(2);
        Runnable queued = fixture.pending();
        fixture.jobs.remove(queued);
        fixture.now = 1;
        queued.run();
        assertEquals(2, fixture.draws.size());
        fixture.request(3);
        assertTrue(fixture.jobs.get(fixture.pending()) < 18_000_000L);
    }

    @Test public void concurrentProducerBurstsStillHaveOnePendingCallback() throws Exception {
        Fixture fixture = new Fixture(60);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> producers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread producer = new Thread(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 100; n++) fixture.frames.request();
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            producers.add(producer);
            producer.start();
        }
        start.countDown();
        for (Thread producer : producers) { producer.join(3000); assertFalse(producer.isAlive()); }
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(800, fixture.frames.snapshot().requests);
        assertEquals(1, fixture.draws.size());
        assertEquals(1, fixture.jobs.size());
        fixture.next();
        assertEquals(2, fixture.draws.size());
        assertTrue(fixture.jobs.isEmpty());
    }
}
