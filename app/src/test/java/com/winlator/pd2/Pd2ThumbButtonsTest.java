package com.winlator.pd2;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.*;

public final class Pd2ThumbButtonsTest {
    private static final class Target {
        final Pd2ThumbButtons thumbs = new Pd2ThumbButtons();
        final HashSet<String> nativeHeld = new HashSet<>();
        final ArrayList<String> edges = new ArrayList<>();
        boolean leftTrigger;
        int runWalkToggles, menus, taps;

        Pd2ThumbButtons.Action event(int device, boolean left, boolean down, boolean repeat,
                                     boolean pointer, boolean available, long at) {
            Pd2ThumbButtons.Action action = thumbs.event(device, left, down, repeat, pointer, available);
            String button = device + (left ? ":L3" : ":R3");
            switch (action) {
                case NATIVE_DOWN:
                    nativeHeld.add(button); edges.add(at + ":+" + button);
                    if (left && leftTrigger) runWalkToggles++;
                    break;
                case NATIVE_UP: nativeHeld.remove(button); edges.add(at + ":-" + button); break;
                case POINTER_TAP: taps++; break;
                case OPEN_MENU: menus++; release(); break;
                case NONE: break;
            }
            return action;
        }

        void release() { nativeHeld.clear(); thumbs.clear(); }
    }

    @Test public void nativeLtAndL3OverlapIsDeliveredBeforeEitherPhysicalRelease() {
        Target target = new Target(); target.leftTrigger = true;
        assertEquals(Pd2ThumbButtons.Action.NATIVE_DOWN, target.event(1, true, true, false, false, true, 20));
        assertTrue(target.nativeHeld.contains("1:L3"));
        assertEquals(1, target.runWalkToggles);
        // LT is released before L3. Deferring the L3 down until this release
        // would destroy the combination and its physical 50 ms hold.
        target.leftTrigger = false;
        assertEquals(Pd2ThumbButtons.Action.NATIVE_UP, target.event(1, true, false, false, false, true, 70));
        assertEquals(Arrays.asList("20:+1:L3", "70:-1:L3"), target.edges);
        assertTrue(target.nativeHeld.isEmpty());
    }

    @Test public void nativeRightThumbPreservesHoldAndIgnoresRepeatedDowns() {
        Target target = new Target();
        assertEquals(Pd2ThumbButtons.Action.NATIVE_DOWN, target.event(1, false, true, false, false, true, 10));
        assertEquals(Pd2ThumbButtons.Action.NONE, target.event(1, false, true, true, false, true, 20));
        assertEquals(Pd2ThumbButtons.Action.NONE, target.event(1, false, true, false, false, true, 30));
        assertTrue(target.nativeHeld.contains("1:R3"));
        assertEquals(Pd2ThumbButtons.Action.NATIVE_UP, target.event(1, false, false, false, false, true, 300));
        assertEquals(Arrays.asList("10:+1:R3", "300:-1:R3"), target.edges);
        assertEquals(Pd2ThumbButtons.Action.NONE, target.event(1, false, false, false, false, true, 301));
    }

    @Test public void eitherChordOrderOpensOnceAndReleasesTheFirstNativeThumb() {
        for (boolean firstLeft : new boolean[]{true, false}) {
            Target target = new Target();
            assertEquals(Pd2ThumbButtons.Action.NATIVE_DOWN, target.event(1, firstLeft, true, false, false, true, 10));
            assertEquals(Pd2ThumbButtons.Action.OPEN_MENU, target.event(1, !firstLeft, true, false, false, true, 20));
            assertEquals(1, target.menus); assertEquals(1, target.edges.size());
            assertTrue(target.nativeHeld.isEmpty());
            target.event(1, firstLeft, true, true, false, false, 30);
            target.event(1, firstLeft, false, false, false, false, 40);
            target.event(1, !firstLeft, false, false, false, true, 50);
            assertEquals(1, target.menus); assertEquals(1, target.edges.size());
            assertEquals(Pd2ThumbButtons.Action.NATIVE_DOWN, target.event(1, firstLeft, true, false, false, true, 60));
        }
    }

    @Test public void pointerSingleTapsStayOnReleaseAndItsChordDoesNotTapEitherThumb() {
        Target target = new Target();
        for (boolean left : new boolean[]{true, false}) {
            assertEquals(Pd2ThumbButtons.Action.NONE, target.event(1, left, true, false, true, true, 10));
            assertEquals(Pd2ThumbButtons.Action.POINTER_TAP, target.event(1, left, false, false, true, true, 20));
        }
        assertEquals(2, target.taps); assertTrue(target.edges.isEmpty());
        target.event(1, true, true, false, true, true, 30);
        assertEquals(Pd2ThumbButtons.Action.OPEN_MENU, target.event(1, false, true, false, true, true, 40));
        target.event(1, true, false, false, true, false, 50);
        target.event(1, false, false, false, true, true, 60);
        assertEquals(2, target.taps); assertEquals(1, target.menus); assertTrue(target.edges.isEmpty());
    }

    @Test public void focusModalAndModeReleaseExpireDeferredAndDeliveredOwners() {
        Target target = new Target();
        target.event(1, true, true, false, true, true, 10);
        target.release(); // Same release path used by modal, focus and pause.
        target.event(1, true, false, false, true, true, 20);
        assertEquals(0, target.taps);
        target.event(1, true, true, false, false, true, 30);
        target.release();
        assertEquals(Pd2ThumbButtons.Action.NONE, target.event(1, true, false, false, false, true, 40));
        assertEquals(1, target.edges.size()); assertTrue(target.nativeHeld.isEmpty());
        target.event(1, true, true, false, true, true, 50);
        target.event(1, true, false, false, false, true, 60); // Defensive route-change expiry.
        assertEquals(0, target.taps); assertEquals(1, target.edges.size());
        target.event(1, true, true, false, true, true, 70);
        target.event(1, true, false, false, true, false, 80); // Gate rejects old deferred tap.
        target.event(1, true, false, false, true, true, 90);
        assertEquals(0, target.taps);
    }

    @Test public void differentControllersCannotFormTheReservedChordTogether() {
        Target target = new Target();
        assertEquals(Pd2ThumbButtons.Action.NATIVE_DOWN, target.event(1, true, true, false, false, true, 10));
        assertEquals(Pd2ThumbButtons.Action.NATIVE_DOWN, target.event(2, false, true, false, false, true, 20));
        assertEquals(0, target.menus); assertEquals(2, target.nativeHeld.size());
        assertEquals(Pd2ThumbButtons.Action.NATIVE_UP, target.event(2, false, false, false, false, true, 30));
        assertTrue(target.nativeHeld.contains("1:L3"));
        assertEquals(Pd2ThumbButtons.Action.OPEN_MENU, target.event(1, false, true, false, false, true, 40));
        assertEquals(1, target.menus); assertTrue(target.nativeHeld.isEmpty());
    }
}
