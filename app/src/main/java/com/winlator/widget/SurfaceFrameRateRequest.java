package com.winlator.widget;

import android.os.Build;
import android.view.Surface;
import org.json.JSONException;
import org.json.JSONObject;

/** Best-effort display vote, reapplied for each surface. It does not pace game frames. */
final class SurfaceFrameRateRequest {
    interface Target {
        boolean isValid();
        void request(float rate, int compatibility, int changeStrategy);
    }

    private final float requestedHz;
    private final int apiLevel;
    private String state;

    SurfaceFrameRateRequest(float requestedHz) { this(requestedHz, Build.VERSION.SDK_INT); }
    SurfaceFrameRateRequest(float requestedHz, int apiLevel) {
        this.requestedHz = Float.isFinite(requestedHz) && requestedHz > 0 ? requestedHz : 0;
        this.apiLevel = apiLevel;
        state = this.requestedHz == 0 ? "disabled" : apiLevel < 30 ? "unsupported-api" : "awaiting-surface";
    }

    void surfaceAvailable(Surface surface) {
        apply(new Target() {
            public boolean isValid() { return surface != null && surface.isValid(); }
            public void request(float rate, int compatibility, int changeStrategy) {
                if (Build.VERSION.SDK_INT >= 31) Api31.request(surface, rate, compatibility, changeStrategy);
                else if (Build.VERSION.SDK_INT >= 30) Api30.request(surface, rate, compatibility);
            }
        });
    }

    synchronized void apply(Target target) {
        if (requestedHz == 0 || apiLevel < 30) return;
        try {
            if (!target.isValid()) { state = "invalid-surface"; return; }
            target.request(requestedHz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            // Returning without an error means accepted, not that Android honored the vote.
            state = "request-accepted";
        } catch (RuntimeException | LinkageError failure) {
            state = "request-unavailable";
        }
    }

    synchronized void surfaceDestroyed() {
        if (requestedHz > 0 && apiLevel >= 30) state = "surface-destroyed";
    }

    synchronized JSONObject snapshot() {
        JSONObject report = new JSONObject();
        try {
            report.put("requestedHz", requestedHz).put("apiLevel", apiLevel)
                    .put("apiSupported", apiLevel >= 30).put("state", state)
                    .put("compatibility", "default").put("changeStrategy", "seamless/default")
                    .put("scope", "Display request only; Android may ignore it. Not measured refresh rate or a game FPS cap.");
        } catch (JSONException ignored) { }
        return report;
    }

    private static final class Api30 {
        static void request(Surface surface, float rate, int compatibility) {
            surface.setFrameRate(rate, compatibility);
        }
    }
    private static final class Api31 {
        static void request(Surface surface, float rate, int compatibility, int strategy) {
            surface.setFrameRate(rate, compatibility, strategy);
        }
    }
}
