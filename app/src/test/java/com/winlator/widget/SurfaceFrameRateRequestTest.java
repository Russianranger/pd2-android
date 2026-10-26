package com.winlator.widget;

import android.app.Application;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class SurfaceFrameRateRequestTest {
    private static final class Target implements SurfaceFrameRateRequest.Target {
        boolean valid = true, fail;
        int calls, validityChecks, compatibility, strategy;
        float rate;
        public boolean isValid() { validityChecks++; return valid; }
        public void request(float rate, int compatibility, int strategy) {
            calls++;
            if (fail) throw new IllegalStateException("Surface was destroyed concurrently");
            this.rate = rate; this.compatibility = compatibility; this.strategy = strategy;
        }
    }

    @Test public void defaultDeviceRateAndPreAndroid11NeverTouchTheSurface() throws Exception {
        for (float rate : new float[]{0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            Target target = new Target();
            SurfaceFrameRateRequest request = new SurfaceFrameRateRequest(rate, 33);
            request.apply(target);
            assertEquals(0, target.validityChecks);
            assertEquals(0, target.calls);
            assertEquals("disabled", request.snapshot().getString("state"));
        }
        Target target = new Target();
        SurfaceFrameRateRequest request = new SurfaceFrameRateRequest(60, 29);
        request.apply(target);
        assertEquals(0, target.validityChecks);
        assertFalse(request.snapshot().getBoolean("apiSupported"));
        assertEquals("unsupported-api", request.snapshot().getString("state"));
    }

    @Test public void surfaceRecreationAndSizeChangesReapplyASeamlessDefaultVote() throws Exception {
        SurfaceFrameRateRequest request = new SurfaceFrameRateRequest(60, 33);
        Target target = new Target();
        request.apply(target);
        assertEquals(60f, target.rate, 0f);
        assertEquals(Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, target.compatibility);
        assertEquals(Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS, target.strategy);
        assertEquals("request-accepted", request.snapshot().getString("state"));
        request.apply(target); // Surface format or dimensions changed.
        request.surfaceDestroyed();
        assertEquals("surface-destroyed", request.snapshot().getString("state"));
        Target replacement = new Target();
        request.apply(replacement);
        assertEquals(2, target.calls);
        assertEquals(1, replacement.calls);
        assertEquals("request-accepted", request.snapshot().getString("state"));
        assertTrue(request.snapshot().getString("scope").contains("Not measured refresh rate"));
    }

    @Test public void invalidOrRacingSurfacesCannotCrashAndAReplacementCanRecover() throws Exception {
        SurfaceFrameRateRequest request = new SurfaceFrameRateRequest(60, 33);
        Target target = new Target(); target.valid = false;
        request.apply(target);
        assertEquals(0, target.calls);
        assertEquals("invalid-surface", request.snapshot().getString("state"));
        target.valid = true; target.fail = true;
        request.apply(target);
        assertEquals("request-unavailable", request.snapshot().getString("state"));
        target.fail = false;
        request.apply(target);
        assertEquals("request-accepted", request.snapshot().getString("state"));
    }

    private static final class RecordingSurface extends Surface {
        private final SurfaceTexture texture;
        int twoArgumentCalls, threeArgumentCalls, compatibility, strategy;
        float rate;
        RecordingSurface() { this(new SurfaceTexture(0)); }
        private RecordingSurface(SurfaceTexture texture) { super(texture); this.texture = texture; }
        void close() { release(); texture.release(); }
        @Override public boolean isValid() { return true; }
        @Override public void setFrameRate(float rate, int compatibility) {
            twoArgumentCalls++; this.rate = rate; this.compatibility = compatibility;
        }
        @Override public void setFrameRate(float rate, int compatibility, int strategy) {
            threeArgumentCalls++; this.rate = rate; this.compatibility = compatibility; this.strategy = strategy;
        }
    }

    @Test @Config(sdk = 30) public void android11UsesTheSupportedTwoArgumentSurfaceApi() {
        RecordingSurface surface = new RecordingSurface();
        try {
            new SurfaceFrameRateRequest(60).surfaceAvailable(surface);
            assertEquals(1, surface.twoArgumentCalls);
            assertEquals(0, surface.threeArgumentCalls);
            assertEquals(60f, surface.rate, 0f);
            assertEquals(Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, surface.compatibility);
        } finally { surface.close(); }
    }

    @Test public void android12AndLaterExplicitlyAvoidNonSeamlessModeSwitches() {
        RecordingSurface surface = new RecordingSurface();
        try {
            new SurfaceFrameRateRequest(60).surfaceAvailable(surface);
            assertEquals(0, surface.twoArgumentCalls);
            assertEquals(1, surface.threeArgumentCalls);
            assertEquals(60f, surface.rate, 0f);
            assertEquals(Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, surface.compatibility);
            assertEquals(Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS, surface.strategy);
        } finally { surface.close(); }
    }
}
