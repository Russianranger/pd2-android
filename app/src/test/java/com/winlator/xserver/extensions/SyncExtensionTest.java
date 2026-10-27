package com.winlator.xserver.extensions;

import android.app.Application;

import com.winlator.xserver.errors.BadFence;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadValue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Exercises the production fence monitor and lifecycle without loading the native connector. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class SyncExtensionTest {
    private static SyncExtension extension() { return new SyncExtension(null, (byte)-100); }

    @Test public void initiallyTriggeredFenceCompletesWithoutBlocking() throws Exception {
        SyncExtension sync = extension();
        sync.createFence(1, true);
        sync.awaitFences(new int[]{1});
        assertEquals(1, sync.performanceSnapshot().getLong("completedAwaits"));
        assertEquals(0, sync.performanceSnapshot().getLong("blockingWaits"));
    }

    @Test public void presentTriggerWakesABlockedRequest() throws Exception {
        SyncExtension sync = extension();
        sync.createFence(1, false);
        Waiter waiter = new Waiter(sync, 1);
        waiter.awaitBlocked();
        assertFalse(waiter.completed.get());
        sync.setTriggered(1);
        waiter.join();
        assertNull(waiter.failure.get());
        assertTrue(waiter.completed.get());
        assertEquals(0, sync.performanceSnapshot().getInt("waitingClients"));
    }

    @Test public void protocolTriggerWakesAllWaiters() throws Exception {
        SyncExtension sync = extension();
        sync.createFence(1, false);
        Waiter first = new Waiter(sync, 1), second = new Waiter(sync, 1);
        first.awaitBlocked(); second.awaitBlocked();
        sync.triggerFence(1);
        first.join(); second.join();
        assertNull(first.failure.get()); assertNull(second.failure.get());
        assertEquals(2, sync.performanceSnapshot().getLong("completedAwaits"));
    }

    @Test public void awaitAnyPreservesTheExistingMultipleFenceCondition() throws Exception {
        SyncExtension sync = extension();
        sync.createFence(1, false); sync.createFence(2, false);
        Waiter waiter = new Waiter(sync, 1, 2);
        waiter.awaitBlocked();
        sync.triggerFence(2);
        waiter.join();
        assertNull(waiter.failure.get());
        assertTrue(waiter.completed.get());
    }

    @Test public void aSpuriousWakeDoesNotCompleteAnUntriggeredFence() throws Exception {
        SyncExtension sync = extension();
        sync.createFence(1, false);
        Waiter waiter = new Waiter(sync, 1);
        waiter.awaitBlocked();
        Object monitor = monitor(sync);
        synchronized (monitor) { monitor.notifyAll(); }
        waiter.awaitBlocked();
        assertFalse(waiter.completed.get());
        sync.triggerFence(1);
        waiter.join();
        assertNull(waiter.failure.get());
    }

    @Test public void triggerRacingThePredicateCannotBeLost() throws Exception {
        SyncExtension sync = extension();
        for (int id = 1; id <= 40; id++) {
            sync.createFence(id, false);
            Waiter waiter = new Waiter(sync, id);
            if ((id & 1) == 0) waiter.awaitBlocked();
            sync.setTriggered(id);
            waiter.join();
            assertNull("Trigger race for fence " + id, waiter.failure.get());
            assertTrue(waiter.completed.get());
        }
        assertEquals(40, sync.performanceSnapshot().getLong("completedAwaits"));
    }

    @Test public void destroyWakesTheRequestAndReportsBadFence() throws Exception {
        SyncExtension sync = extension();
        sync.createFence(7, false);
        Waiter waiter = new Waiter(sync, 7);
        waiter.awaitBlocked();
        sync.destroyFence(7);
        waiter.join();
        assertTrue(waiter.failure.get() instanceof BadFence);
        assertEquals(7, ((BadFence)waiter.failure.get()).getData());
        assertFalse(waiter.completed.get());
    }

    @Test public void stopCancelsBeforeTheCallerJoinsTheRequestThread() throws Exception {
        SyncExtension sync = extension();
        sync.createFence(1, false);
        Waiter waiter = new Waiter(sync, 1);
        waiter.awaitBlocked();
        sync.cancelAwaiters();
        waiter.join();
        assertTrue(waiter.failure.get() instanceof IOException);
        assertFalse(waiter.completed.get());
        assertEquals(1, sync.performanceSnapshot().getLong("cancelledAwaits"));
        assertFalse(sync.performanceSnapshot().getBoolean("running"));
        // A request arriving after cancellation must not enter a new wait.
        long waits = sync.performanceSnapshot().getLong("blockingWaits");
        try { sync.awaitFences(new int[]{1}); fail(); }
        catch (IOException expected) { }
        assertEquals(waits, sync.performanceSnapshot().getLong("blockingWaits"));
    }

    @Test public void rapidRestartCannotReviveAnOldWaiterButNewRequestsCanWait() throws Exception {
        SyncExtension sync = extension();
        sync.createFence(1, false);
        Waiter old = new Waiter(sync, 1);
        old.awaitBlocked();
        // Hold the actual predicate monitor so the old waiter cannot run between
        // cancellation and restart. It must observe the changed generation.
        synchronized (monitor(sync)) { sync.cancelAwaiters(); sync.startAwaiters(); }
        old.join();
        assertTrue(old.failure.get() instanceof IOException);
        assertFalse(old.completed.get());
        Waiter fresh = new Waiter(sync, 1);
        fresh.awaitBlocked();
        sync.triggerFence(1);
        fresh.join();
        assertNull(fresh.failure.get());
        assertTrue(fresh.completed.get());
    }

    @Test public void interruptionAbortsTheRequestAndRestoresItsFlag() throws Exception {
        SyncExtension sync = extension();
        sync.createFence(1, false);
        Waiter waiter = new Waiter(sync, 1);
        waiter.awaitBlocked();
        waiter.thread.interrupt();
        waiter.join();
        assertTrue(waiter.failure.get() instanceof IOException);
        assertTrue(waiter.interrupted.get());
        assertFalse(waiter.completed.get());
        assertEquals(1, sync.performanceSnapshot().getLong("interruptedAwaits"));
        assertEquals(0, sync.performanceSnapshot().getInt("waitingClients"));
    }

    @Test public void invalidAndEmptyListsFailInsteadOfSpinning() throws Exception {
        SyncExtension sync = extension();
        try { sync.awaitFences(new int[0]); fail(); }
        catch (BadValue expected) { }
        sync.createFence(1, true);
        try { sync.awaitFences(new int[]{1, 99}); fail(); }
        catch (BadFence expected) { assertEquals(99, expected.getData()); }
        assertEquals(0, sync.performanceSnapshot().getLong("blockingWaits"));
    }

    @Test public void resetRequiresATriggerAndAllowsANewBlockingWait() throws Exception {
        SyncExtension sync = extension();
        sync.createFence(1, false);
        try { sync.resetFence(1); fail(); }
        catch (BadMatch expected) { }
        sync.triggerFence(1);
        sync.resetFence(1);
        Waiter waiter = new Waiter(sync, 1);
        waiter.awaitBlocked();
        sync.triggerFence(1);
        waiter.join();
        assertNull(waiter.failure.get());
    }

    private static Object monitor(SyncExtension sync) throws Exception {
        Field field = SyncExtension.class.getDeclaredField("fences");
        field.setAccessible(true);
        return field.get(sync);
    }

    private static final class Waiter {
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean(), interrupted = new AtomicBoolean();
        final Thread thread;
        Waiter(SyncExtension sync, int... ids) {
            thread = new Thread(() -> {
                try { sync.awaitFences(ids); completed.set(true); }
                catch (Throwable problem) { failure.set(problem); }
                finally { interrupted.set(Thread.currentThread().isInterrupted()); }
            }, "sync-fence-test");
            thread.setDaemon(true);
            thread.start();
        }
        void awaitBlocked() throws Exception {
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (thread.isAlive() && System.nanoTime() < deadline) {
                if (thread.getState() == Thread.State.TIMED_WAITING) return;
                Thread.sleep(1);
            }
            thread.interrupt();
            fail("Request did not enter a blocking wait: " + failure.get());
        }
        void join() throws Exception {
            thread.join(2000);
            if (thread.isAlive()) { thread.interrupt(); fail("Fence request did not finish"); }
        }
    }
}
