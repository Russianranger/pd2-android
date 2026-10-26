package com.winlator.pd2;

import com.winlator.core.EnvVars;

/** Per-launch consumer hints; does not change Wine's exposed controller or runtime files. */
public final class Pd2ControllerBackendPolicy {
    public static final String PREFERENCE = "pd2_controller_backend";
    public static final int STANDARD = 0;
    public static final int XINPUT_COMPATIBILITY = 1;

    private Pd2ControllerBackendPolicy() {}

    public static int normalize(int mode) {
        return mode == XINPUT_COMPATIBILITY ? XINPUT_COMPATIBILITY : STANDARD;
    }

    public static String modeName(int mode) {
        return normalize(mode) == XINPUT_COMPATIBILITY ? "xinput_compatibility" : "standard";
    }

    /**
     * Apply to the fresh launch's managed base environment. These SDL preferences
     * are an experiment; the game may ignore them, and recovery is not established.
     */
    public static String environment(String base, int mode) {
        if (normalize(mode) == STANDARD) return base;
        return new EnvVars(base)
                .put("SDL_JOYSTICK_RAWINPUT", "0")
                .put("SDL_XINPUT_ENABLED", "1")
                .put("SDL_JOYSTICK_HIDAPI_XBOX", "0")
                .toString();
    }
}
