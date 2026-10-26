package com.winlator.pd2;

import android.content.SharedPreferences;

/** One-time adoption of the accepted reader repair, followed by persistent manual choices. */
public final class Pd2ControllerBackendPreferences {
    public static final String MIGRATION_PREFERENCE = "pd2_controller_reader_default_v1";

    private Pd2ControllerBackendPreferences() {}

    public static synchronized int get(SharedPreferences preferences) {
        boolean migrated;
        try {
            migrated = preferences.getBoolean(MIGRATION_PREFERENCE, false);
        } catch (ClassCastException invalidMarker) {
            migrated = false;
        }
        if (!migrated) {
            // Persist both fields in the same edit. Do not touch renderer, input mode,
            // controller notifications, driver selection or manually armed diagnostics.
            set(preferences, Pd2ControllerBackendPolicy.DEFAULT);
            return Pd2ControllerBackendPolicy.DEFAULT;
        }
        try {
            return Pd2ControllerBackendPolicy.normalize(preferences.getInt(
                    Pd2ControllerBackendPolicy.PREFERENCE, Pd2ControllerBackendPolicy.DEFAULT));
        } catch (ClassCastException invalidMode) {
            return Pd2ControllerBackendPolicy.DEFAULT;
        }
    }

    public static synchronized void set(SharedPreferences preferences, int mode) {
        // An explicit choice also completes migration, including a fallback selected
        // before the first launch. Later reads must not silently replace that choice.
        preferences.edit()
                .putInt(Pd2ControllerBackendPolicy.PREFERENCE, Pd2ControllerBackendPolicy.normalize(mode))
                .putBoolean(MIGRATION_PREFERENCE, true)
                .apply();
    }
}
