package com.winlator.renderer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PointerRenderPolicyTest {
    @Test public void hiddenWhiteCursorsAvoidMotionFramesWithoutHidingTheMenuReticle() {
        assertFalse(PointerRenderPolicy.needsMotionFrame(true, false, false, false));
        assertTrue(PointerRenderPolicy.needsMotionFrame(true, false, true, false));
        assertTrue(PointerRenderPolicy.needsMotionFrame(true, true, false, false));
    }

    @Test public void globalCursorHideSkipsMotionFramesForEveryCursorSource() {
        for (boolean overlays : new boolean[]{false, true}) {
            for (boolean reticle : new boolean[]{false, true}) {
                assertFalse(PointerRenderPolicy.needsMotionFrame(false, overlays, reticle, false));
            }
        }
    }

    @Test public void magnifierAndKeyboardOffsetStillFollowTheHiddenPointer() {
        for (boolean cursor : new boolean[]{false, true}) {
            for (boolean overlays : new boolean[]{false, true}) {
                for (boolean reticle : new boolean[]{false, true}) {
                    assertTrue(PointerRenderPolicy.needsMotionFrame(cursor, overlays, reticle, true));
                }
            }
        }
    }
}
