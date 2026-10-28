package com.winlator.pd2;

import android.content.SharedPreferences;

/** Requested D2GL producer limit and Android content redraw budget; independent of display refresh. */
public final class Pd2FramePacingPolicy {
    // Retain the existing preference so explicit 30/60/Unlimited selections survive updates.
    public static final String PREFERENCE = "pd2_present_fps";
    public static final String LIGHT_PREFERENCE = "pd2_light_graphics";
    public static final int GAME_SETTINGS = -1;
    private Pd2FramePacingPolicy() {}

    public static int requestedFps(SharedPreferences preferences) {
        try {
            int value = preferences.getInt(PREFERENCE, 60);
            return normalize(value);
        } catch (ClassCastException invalid) { return 60; }
    }

    public static int normalize(int value) {
        return value == GAME_SETTINGS || value == 0 || value == 30 || value == 60 ? value : 60;
    }

    public static boolean lightGraphics(SharedPreferences preferences) {
        try { return preferences.getBoolean(LIGHT_PREFERENCE, true); }
        catch (ClassCastException invalid) { return true; }
    }
}
