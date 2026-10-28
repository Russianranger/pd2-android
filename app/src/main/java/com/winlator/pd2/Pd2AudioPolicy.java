package com.winlator.pd2;

import android.content.SharedPreferences;

import com.winlator.core.KeyValueSet;

/** PD2 buffering is independent of the PCM format negotiated by Wine/ALSA. */
public final class Pd2AudioPolicy {
    public static final String PREFERENCE = "pd2_audio_latency_millis";
    public static final String ECO_PREFERENCE = "pd2_audio_eco";
    public static final int DEFAULT_LATENCY_MILLIS = 32;

    private Pd2AudioPolicy() {}

    public static boolean ecoMode(SharedPreferences preferences) {
        try { return preferences.getBoolean(ECO_PREFERENCE, false); }
        catch (ClassCastException invalid) { return false; }
    }

    public static int latencyMillis(SharedPreferences preferences) {
        int value;
        try { value = preferences.getInt(PREFERENCE, DEFAULT_LATENCY_MILLIS); }
        catch (ClassCastException invalid) { value = DEFAULT_LATENCY_MILLIS; }
        return value == 16 || value == 32 || value == 64 ? value : DEFAULT_LATENCY_MILLIS;
    }

    public static String configuration(SharedPreferences preferences) {
        return new KeyValueSet().put("latencyMillis", latencyMillis(preferences))
                .put("performanceMode", 1).toString();
    }
}
