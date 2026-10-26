package com.winlator.pd2;

import com.winlator.core.EnvVars;

/** Captured input-path choices for a fresh, fully stopped Wine launch. */
public final class Pd2ControllerBackendPolicy {
    public static final String PREFERENCE = "pd2_controller_backend";
    public static final int STANDARD = 0;
    public static final int XINPUT_COMPATIBILITY = 1;
    public static final int XINPUT_READER_REPAIR = 2;

    private Pd2ControllerBackendPolicy() {}

    public static int normalize(int mode) {
        return mode == XINPUT_COMPATIBILITY || mode == XINPUT_READER_REPAIR ? mode : STANDARD;
    }

    public static String modeName(int mode) {
        switch (normalize(mode)) {
            case XINPUT_COMPATIBILITY: return "xinput_compatibility";
            case XINPUT_READER_REPAIR: return "xinput_reader_repair";
            default: return "standard";
        }
    }

    public static boolean repairsReader(String capturedMode) {
        return "xinput_reader_repair".equals(capturedMode);
    }

    public static String displayName(String capturedMode) {
        if (repairsReader(capturedMode)) return "XInput reader repair";
        return "xinput_compatibility".equals(capturedMode) ? "XInput compatibility" : "Standard Native";
    }

    /** Force the verified builtin runtime path only for the installed experiment. */
    public static String readerOverrides(String existing, boolean applied) {
        if (!applied) return existing;
        String base = existing == null ? "" : existing;
        return base + (base.isEmpty() || base.endsWith(";") ? "" : ";") + "xinput1_4=b";
    }

    /**
     * Apply to the fresh launch's managed base environment. These SDL preferences
     * are an experiment; the game may ignore them, and recovery is not established.
     */
    public static String environment(String base, int mode) {
        // Reader repair is installed separately after successful exact-prefix cleanup.
        // It uses the original environment, without the previous consumer hints.
        if (normalize(mode) != XINPUT_COMPATIBILITY) return base;
        return new EnvVars(base)
                .put("SDL_JOYSTICK_RAWINPUT", "0")
                .put("SDL_XINPUT_ENABLED", "1")
                .put("SDL_JOYSTICK_HIDAPI_XBOX", "0")
                .toString();
    }
}
