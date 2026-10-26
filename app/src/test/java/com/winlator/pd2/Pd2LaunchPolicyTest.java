package com.winlator.pd2;

import com.winlator.box64.Box64Preset;
import com.winlator.core.EnvVars;

import org.junit.Test;

import static org.junit.Assert.*;

public final class Pd2LaunchPolicyTest {
    @Test public void normalLaunchRetainsErrorsAndReaderWarningsWithoutInputTracing() {
        for (String arguments : new String[]{"-3dfx -dxnocompatmodefix", "-ddraw -w"}) {
            for (boolean interpreter : new boolean[]{false, true}) {
                EnvVars environment = new EnvVars(Pd2LaunchPolicy.environment(arguments, interpreter));
                assertEquals("-all,err+all,warn+all", environment.get("WINEDEBUG"));
                assertEquals("1", environment.get("BOX64_SHOWSEGV"));
                assertEquals(interpreter ? "0" : "1", environment.get("BOX64_DYNAREC"));
            }
        }
        assertEquals(Box64Preset.STABILITY, Pd2LaunchPolicy.cpuPreset());
    }

    @Test public void armedTraceAddsDetailedInputExceptionAndModuleChannelsToTheCapturedBase() {
        String base = Pd2LaunchPolicy.WINE_DEBUG;
        assertEquals("-all,err+all,warn+all,+seh,+loaddll,+xinput,+rawinput,+relay",
                Pd2LaunchPolicy.inputTraceDebug(base));
        assertEquals(base + ",+vulkan,+seh,+loaddll,+xinput,+rawinput,+relay",
                Pd2LaunchPolicy.inputTraceDebug(base + ",+vulkan"));
        assertEquals(Pd2LaunchPolicy.inputTraceDebug(base), Pd2LaunchPolicy.inputTraceDebug(null));
        assertEquals(Pd2LaunchPolicy.inputTraceDebug(base), Pd2LaunchPolicy.inputTraceDebug(""));
    }

    @Test public void tracedLaunchDoesNotEnableTracingOnTheNextFreshLaunch() {
        EnvVars captured = new EnvVars(Pd2LaunchPolicy.environment("-3dfx", false));
        captured.put("WINEDEBUG", Pd2LaunchPolicy.inputTraceDebug(captured.get("WINEDEBUG")));
        assertTrue(captured.get("WINEDEBUG").endsWith(",+relay"));
        assertEquals("-all,err+all,warn+all",
                new EnvVars(Pd2LaunchPolicy.environment("-3dfx", false)).get("WINEDEBUG"));
    }

    @Test public void graphicsSelectionAndRuntimeEnvironmentAreIndependentOfTracing() {
        EnvVars glide = new EnvVars(Pd2LaunchPolicy.environment("-3dfx", false));
        EnvVars directDraw = new EnvVars(Pd2LaunchPolicy.environment("-ddraw -w", false));
        assertEquals("ddraw,glide3x=n,b;mscoree,mshtml=d", glide.get("WINEDLLOVERRIDES"));
        assertEquals("ddraw=b;glide3x=n,b;mscoree,mshtml=d", directDraw.get("WINEDLLOVERRIDES"));
        String before = glide.toString();
        String trace = Pd2LaunchPolicy.inputTraceDebug(glide.get("WINEDEBUG"));
        assertNotEquals(glide.get("WINEDEBUG"), trace);
        assertEquals(before, glide.toString());
    }
}
