package com.winlator.xserver.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PresentFramePacerTest {
    private static final long INTERVAL_60 = 16_666_667L;
    private static final long INTERVAL_30 = 33_333_334L;

    private static final class Fixture {
        final AtomicLong now = new AtomicLong();
        final List<Long> sleeps = new ArrayList<>();
        final PresentFramePacer pacer = new PresentFramePacer(now::get, nanos -> {
            sleeps.add(nanos);
            now.addAndGet(nanos);
        });
        Fixture(int fps) { pacer.configure(fps); }
    }

    @Test public void sixtyFpsLeavesTheFirstRequestImmediateAndPacesBurstsOneIntervalAtATime() {
        Fixture fixture = new Fixture(60);
        Object window = new Object();
        fixture.pacer.pace(window);
        assertTrue(fixture.sleeps.isEmpty());
        for (int i = 0; i < 120; i++) fixture.pacer.pace(window);
        assertEquals(120, fixture.sleeps.size());
        for (long delay : fixture.sleeps) assertEquals(INTERVAL_60, delay);
        PresentFramePacer.Snapshot stats = fixture.pacer.stats();
        assertEquals(121, stats.presents);
        assertEquals(120, stats.waits);
        assertEquals(INTERVAL_60 * 120, stats.requestedWaitNanos);
        assertEquals(stats.requestedWaitNanos, stats.actualWaitNanos);
        assertEquals(stats.actualWaitNanos, stats.elapsedNanos);
    }

    @Test public void thirtyFpsRequestsOnlyTheRemainingPartOfItsInterval() {
        Fixture fixture = new Fixture(30);
        Object window = new Object();
        fixture.pacer.pace(window);
        fixture.now.addAndGet(7_000_000);
        fixture.pacer.pace(window);
        assertEquals(Long.valueOf(INTERVAL_30 - 7_000_000), fixture.sleeps.get(0));
        fixture.pacer.pace(window);
        assertEquals(Long.valueOf(INTERVAL_30), fixture.sleeps.get(1));
    }

    @Test public void independentWindowsDoNotChargeEachOthersCadence() {
        Fixture fixture = new Fixture(60);
        Object first = new Object(), second = new Object();
        fixture.pacer.pace(first);
        fixture.now.addAndGet(4_000_000);
        fixture.pacer.pace(second);
        assertTrue(fixture.sleeps.isEmpty());
        fixture.pacer.pace(first);
        assertEquals(Long.valueOf(INTERVAL_60 - 4_000_000), fixture.sleeps.get(0));
        fixture.pacer.pace(second);
        assertEquals(Long.valueOf(4_000_000), fixture.sleeps.get(1));
        assertEquals(2, fixture.pacer.stats().trackedWindows);
    }

    @Test public void longStallsAndOversleepResetTheCadenceWithoutCatchupOrFutureQueues() {
        AtomicLong now = new AtomicLong();
        List<Long> sleeps = new ArrayList<>();
        PresentFramePacer pacer = new PresentFramePacer(now::get, nanos -> {
            sleeps.add(nanos);
            now.addAndGet(nanos + 500_000_000L);
        });
        pacer.configure(60);
        Object window = new Object();
        pacer.pace(window);
        now.set(10_000_000_000L);
        pacer.pace(window);
        assertTrue("A slow producer needs no extra wait", sleeps.isEmpty());
        pacer.pace(window);
        pacer.pace(window);
        assertEquals(2, sleeps.size());
        assertEquals(Long.valueOf(INTERVAL_60), sleeps.get(0));
        assertEquals(Long.valueOf(INTERVAL_60), sleeps.get(1));
        assertEquals(2 * (INTERVAL_60 + 500_000_000L), pacer.stats().actualWaitNanos);
    }

    @Test public void uncappedInvalidRatesAndMissingWindowNeverWaitOrRetainHistories() {
        for (int rate : new int[]{0, -1, 1, 59, 61, Integer.MAX_VALUE}) {
            Fixture fixture = new Fixture(rate);
            Object window = new Object();
            fixture.pacer.pace(window);
            fixture.pacer.pace(window);
            assertTrue(fixture.sleeps.isEmpty());
            assertEquals(0, fixture.pacer.stats().fps);
            assertEquals(0, fixture.pacer.stats().trackedWindows);
            assertEquals(2, fixture.pacer.stats().presents);
        }
        Fixture fixture = new Fixture(60);
        fixture.pacer.pace(null);
        fixture.pacer.pace(null);
        assertTrue(fixture.sleeps.isEmpty());
        assertEquals(0, fixture.pacer.stats().trackedWindows);
    }

    @Test public void configuringAndForgettingResetWindowHistoryAndConfigurationResetsCounters() {
        Fixture fixture = new Fixture(60);
        Object window = new Object();
        fixture.pacer.pace(window);
        fixture.pacer.pace(window);
        fixture.pacer.forget(window);
        fixture.pacer.pace(window);
        assertEquals(1, fixture.sleeps.size());
        fixture.pacer.configure(30);
        PresentFramePacer.Snapshot reset = fixture.pacer.stats();
        assertEquals(0, reset.presents);
        assertEquals(0, reset.waits);
        assertEquals(0, reset.requestedWaitNanos);
        assertEquals(0, reset.actualWaitNanos);
        assertEquals(0, reset.elapsedNanos);
        assertEquals(0, reset.trackedWindows);
        fixture.pacer.pace(window);
        fixture.pacer.pace(window);
        assertEquals(Long.valueOf(INTERVAL_30), fixture.sleeps.get(1));
    }

    @Test public void historyIsBoundedAndEvictionKeepsRecentlyUsedWindows() {
        Fixture fixture = new Fixture(60);
        Object oldest = new Object(), recent = new Object();
        fixture.pacer.pace(oldest);
        fixture.pacer.pace(recent);
        for (int i = 0; i < PresentFramePacer.MAX_WINDOWS - 2; i++) fixture.pacer.pace(new Object());
        fixture.pacer.pace(recent); // Keep this entry more recent than the oldest.
        fixture.pacer.pace(new Object());
        assertEquals(PresentFramePacer.MAX_WINDOWS, fixture.pacer.stats().trackedWindows);
        int sleeps = fixture.sleeps.size();
        fixture.pacer.pace(oldest);
        assertEquals("An evicted window starts fresh", sleeps, fixture.sleeps.size());
        for (int i = 0; i < 1000; i++) fixture.pacer.pace(new Object());
        assertEquals(PresentFramePacer.MAX_WINDOWS, fixture.pacer.stats().trackedWindows);
    }

    @Test public void interruptedSleepRestoresTheFlagAndDoesNotLoseTheRequest() {
        AtomicLong now = new AtomicLong();
        PresentFramePacer pacer = new PresentFramePacer(now::get, nanos -> {
            now.addAndGet(123);
            throw new InterruptedException();
        });
        pacer.configure(60);
        Object window = new Object();
        try {
            pacer.pace(window);
            pacer.pace(window);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, pacer.stats().presents);
            assertEquals(1, pacer.stats().waits);
            assertEquals(1, pacer.stats().interruptedWaits);
            assertEquals(123, pacer.stats().actualWaitNanos);
        } finally { Thread.interrupted(); }
    }

    @Test public void sleepingHoldsNoMonitorAndOldWaitCannotContaminateReconfiguration() throws Exception {
        AtomicLong now = new AtomicLong();
        CountDownLatch sleeping = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        PresentFramePacer pacer = new PresentFramePacer(now::get, nanos -> {
            sleeping.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("Test did not release sleeper");
            now.addAndGet(nanos);
        });
        pacer.configure(60);
        Object window = new Object();
        pacer.pace(window);
        Thread request = new Thread(() -> {
            try { pacer.pace(window); } catch (Throwable error) { failure.set(error); }
        });
        request.start();
        try {
            assertTrue(sleeping.await(1, TimeUnit.SECONDS));
            CountDownLatch configured = new CountDownLatch(1);
            Thread control = new Thread(() -> {
                try { pacer.stats(); pacer.forget(window); pacer.configure(30); }
                catch (Throwable error) { failure.set(error); }
                finally { configured.countDown(); }
            });
            control.start();
            assertTrue("Stats/reset must remain available while sleeping", configured.await(1, TimeUnit.SECONDS));
            control.join(1000);
        } finally { release.countDown(); request.join(2000); }
        assertFalse(request.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(30, pacer.stats().fps);
        assertEquals(0, pacer.stats().presents);
        assertEquals(0, pacer.stats().actualWaitNanos);
        assertEquals(0, pacer.stats().trackedWindows);
        pacer.pace(window);
        assertEquals(1, pacer.stats().presents);
        assertEquals(0, pacer.stats().waits);
    }

    @Test public void backwardsClockStartsANewIntervalInsteadOfRequestingAnOversizedWait() {
        Fixture fixture = new Fixture(60);
        fixture.now.set(1_000_000_000L);
        Object window = new Object();
        fixture.pacer.pace(window);
        fixture.now.set(10);
        fixture.pacer.pace(window);
        assertTrue(fixture.sleeps.isEmpty());
        fixture.pacer.pace(window);
        assertEquals(Long.valueOf(INTERVAL_60), fixture.sleeps.get(0));
    }
}
