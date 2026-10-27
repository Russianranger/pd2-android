package com.winlator.pd2;

import android.content.SharedPreferences;

/** Limits X Present image-copy requests; independent of the Android display refresh vote. */
public final class Pd2FramePacingPolicy {
    public static final String PREFERENCE = "pd2_present_fps";
    private Pd2FramePacingPolicy() {}

    public static int requestedFps(SharedPreferences preferences) {
        int value = preferences.getInt(PREFERENCE, 60);
        return value == 0 || value == 30 || value == 60 ? value : 60;
    }
}
