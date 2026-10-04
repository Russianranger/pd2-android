package com.winlator.pd2;

import android.app.Application;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class Pd2NativeFocusRecoveryTest {
    private static final class RecordingTarget implements Pd2NativeFocusRecovery.Target {
        boolean ready = true;
        int readinessChecks;
        final ArrayList<BooleanSupplier> guards = new ArrayList<>();
        final ArrayList<String> reasons = new ArrayList<>();
        @Override public boolean isReady() { readinessChecks++; return ready; }
        @Override public void restore(BooleanSupplier allowed, String reason) {
            guards.add(allowed); reasons.add(reason);
        }
    }

    private static void advance(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    @Test public void reopeningNativeRestoresOnceThenAfterWindowSettleWithoutRepeatedUpdates() {
        RecordingTarget target = new RecordingTarget();
        Pd2NativeFocusRecovery recovery = new Pd2NativeFocusRecovery(target);
        recovery.setActive(true);
        for (int index = 0; index < 100; index++) recovery.setActive(true);
        advance(74); assertTrue(target.reasons.isEmpty());
        advance(1); assertEquals(java.util.Arrays.asList("route"), target.reasons);
        assertTrue(target.guards.get(0).getAsBoolean());
        advance(249); assertEquals(1, target.reasons.size());
        advance(1); assertEquals(java.util.Arrays.asList("route", "settle"), target.reasons);
        advance(3000); assertEquals(2, target.reasons.size());
        recovery.setActive(false);
    }

    @Test public void resizeBurstsUseTheLatestWindowAndExpireEarlierQueuedRequests() {
        RecordingTarget target = new RecordingTarget();
        Pd2NativeFocusRecovery recovery = new Pd2NativeFocusRecovery(target);
        recovery.setActive(true); advance(75);
        recovery.windowChanged();
        assertFalse(target.guards.get(0).getAsBoolean());
        advance(50); recovery.windowChanged();
        advance(50); recovery.windowChanged();
        advance(74); assertEquals(1, target.reasons.size());
        advance(1); assertEquals(java.util.Arrays.asList("route", "window"), target.reasons);
        assertTrue(target.guards.get(1).getAsBoolean());
        advance(250); assertEquals(java.util.Arrays.asList("route", "window", "settle"), target.reasons);
        recovery.setActive(false);
    }

    @Test public void modalPauseOrModeChangeCancelsPendingAndAlreadyQueuedWindowsActions() {
        RecordingTarget target = new RecordingTarget();
        Pd2NativeFocusRecovery recovery = new Pd2NativeFocusRecovery(target);
        recovery.setActive(true); advance(75);
        assertTrue(target.guards.get(0).getAsBoolean());
        recovery.setActive(false); recovery.windowChanged(); advance(1000);
        assertEquals(1, target.reasons.size());
        assertFalse(target.guards.get(0).getAsBoolean());
        recovery.setActive(true); advance(75);
        assertTrue(target.guards.get(1).getAsBoolean());
        assertFalse(target.guards.get(0).getAsBoolean());
        recovery.setActive(false); advance(1000);
        assertEquals(2, target.reasons.size());
    }

    @Test public void helperReadinessRetriesAreBoundedAndAWindowMapCanStartAnotherAttempt() {
        RecordingTarget target = new RecordingTarget(); target.ready = false;
        Pd2NativeFocusRecovery recovery = new Pd2NativeFocusRecovery(target);
        recovery.setActive(true); advance(5000);
        assertEquals(8, target.readinessChecks);
        assertTrue(target.reasons.isEmpty());
        target.ready = true; advance(1000);
        assertTrue(target.reasons.isEmpty());
        recovery.windowChanged(); advance(75);
        assertEquals(java.util.Arrays.asList("window"), target.reasons);
        advance(250); assertEquals(java.util.Arrays.asList("window", "settle"), target.reasons);
        recovery.setActive(false);
    }

    @Test public void readinessRecoveryAndPendingRetriesCannotLeakIntoPointerMode() {
        RecordingTarget target = new RecordingTarget(); target.ready = false;
        Pd2NativeFocusRecovery recovery = new Pd2NativeFocusRecovery(target);
        recovery.setActive(true); advance(75);
        target.ready = true; advance(250);
        assertEquals(java.util.Arrays.asList("route"), target.reasons);
        recovery.setActive(false); advance(5000);
        assertEquals(1, target.reasons.size());
        assertFalse(target.guards.get(0).getAsBoolean());
    }
}
