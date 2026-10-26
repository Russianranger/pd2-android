package com.winlator.pd2;

import android.content.SharedPreferences;

/** Display refresh preference only; this does not limit the Windows game's FPS. */
public final class Pd2DisplayPolicy {
    public static final String REQUEST_PREFERENCE = "pd2_display_60hz";
    private Pd2DisplayPolicy() {}

    public static float requestedFrameRate(SharedPreferences preferences) {
        return preferences.getBoolean(REQUEST_PREFERENCE, true) ? 60f : 0f;
    }
}
