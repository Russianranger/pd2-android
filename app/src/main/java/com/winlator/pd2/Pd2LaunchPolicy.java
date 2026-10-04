package com.winlator.pd2;

import com.winlator.box64.Box64Preset;
import com.winlator.container.Container;

/** Runtime-only choices; never change DLLs in the imported installation. */
public final class Pd2LaunchPolicy {
    public static final String WINE_DEBUG = "-all,err+all,warn+all,+seh,+loaddll,+xinput,+rawinput,+hid";
    public static final String CPU_PREFERENCE = "pd2_interpreter";
    public static final String GAMENATIVE_ARGUMENTS = "-3dfx -dxnocompatmodefix";

    private Pd2LaunchPolicy() {}

    public static String cpuPreset() { return Box64Preset.STABILITY; }

    public static String environment(String arguments, boolean interpreter) {
        // Native-first DirectDraw can select the same D2GL hooks as Glide.
        // The compatibility choice must actually bypass that imported wrapper.
        boolean directDraw = false;
        if (arguments != null) for (String token : arguments.trim().split("\\s+")) {
            if ("-ddraw".equalsIgnoreCase(token)) directDraw = true;
        }
        return Container.DEFAULT_ENV_VARS
                + " WINEDLLOVERRIDES=" + (directDraw ? "ddraw=b;glide3x=n,b" : "ddraw,glide3x=n,b")
                + ";mscoree,mshtml=d WINEDEBUG=" + WINE_DEBUG
                + " BOX64_SHOWSEGV=1 BOX64_DYNAREC=" + (interpreter ? "0" : "1");
    }
}
