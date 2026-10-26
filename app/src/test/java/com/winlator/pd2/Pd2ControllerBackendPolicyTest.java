package com.winlator.pd2;

import com.winlator.core.EnvVars;

import org.junit.Test;

import static org.junit.Assert.*;

public final class Pd2ControllerBackendPolicyTest {
    private static final String BASE = "ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact "
            + "MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB "
            + "mesa_glthread=true WINEESYNC=1 TU_DEBUG=noconform "
            + "WINEDLLOVERRIDES=ddraw,glide3x=n,b;mscoree,mshtml=d "
            + "WINEDEBUG=-all,err+all,warn+all,+seh,+loaddll,+xinput,+rawinput "
            + "BOX64_SHOWSEGV=1 BOX64_DYNAREC=1";

    @Test public void standardReturnsTheOriginalBaseWithoutEvenNormalizingWhitespace() {
        String base = "  " + BASE + "  ";
        assertSame(base, Pd2ControllerBackendPolicy.environment(base, Pd2ControllerBackendPolicy.STANDARD));
        assertNull(Pd2ControllerBackendPolicy.environment(null, Pd2ControllerBackendPolicy.STANDARD));
        assertEquals("standard", Pd2ControllerBackendPolicy.modeName(Pd2ControllerBackendPolicy.STANDARD));
    }

    @Test public void selectedCompatibilityAddsOnlyTheThreeConsumerHints() {
        String selected = Pd2ControllerBackendPolicy.environment(BASE, Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY);
        EnvVars variables = new EnvVars(selected);
        assertEquals("0", variables.get("SDL_JOYSTICK_RAWINPUT"));
        assertEquals("1", variables.get("SDL_XINPUT_ENABLED"));
        assertEquals("0", variables.get("SDL_JOYSTICK_HIDAPI_XBOX"));
        assertEquals("xinput_compatibility", Pd2ControllerBackendPolicy.modeName(Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY));
        EnvVars baseline = new EnvVars(BASE);
        for (String key : baseline) assertEquals(key, baseline.get(key), variables.get(key));
        variables.remove("SDL_JOYSTICK_RAWINPUT");
        variables.remove("SDL_XINPUT_ENABLED");
        variables.remove("SDL_JOYSTICK_HIDAPI_XBOX");
        assertEquals(BASE, variables.toString());
    }

    @Test public void existingConsumerHintsAreReplacedWithoutDuplicateAssignments() {
        String base = BASE + " SDL_JOYSTICK_RAWINPUT=1 SDL_XINPUT_ENABLED=0 SDL_JOYSTICK_HIDAPI_XBOX=1"
                + " SDL_XINPUT_ENABLED=0";
        String selected = Pd2ControllerBackendPolicy.environment(base, Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY);
        assertEquals(BASE + " SDL_JOYSTICK_RAWINPUT=0 SDL_XINPUT_ENABLED=1 SDL_JOYSTICK_HIDAPI_XBOX=0", selected);
        assertTrue(base.endsWith("SDL_XINPUT_ENABLED=0"));
    }

    @Test public void returningToStandardOnAFreshBaseRestoresEveryBaselineByte() {
        String selected = Pd2ControllerBackendPolicy.environment(BASE, Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY);
        assertNotEquals(BASE, selected);
        assertSame(BASE, Pd2ControllerBackendPolicy.environment(BASE, Pd2ControllerBackendPolicy.STANDARD));
        assertFalse(new EnvVars(BASE).has("SDL_JOYSTICK_RAWINPUT"));
        assertFalse(new EnvVars(BASE).has("SDL_XINPUT_ENABLED"));
        assertFalse(new EnvVars(BASE).has("SDL_JOYSTICK_HIDAPI_XBOX"));
    }

    @Test public void unknownPreferencesUseTheStandardModeAndUnchangedBase() {
        for (int value : new int[]{-1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertEquals(Pd2ControllerBackendPolicy.STANDARD, Pd2ControllerBackendPolicy.normalize(value));
            assertEquals("standard", Pd2ControllerBackendPolicy.modeName(value));
            assertSame(BASE, Pd2ControllerBackendPolicy.environment(BASE, value));
        }
        assertEquals(Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY,
                Pd2ControllerBackendPolicy.normalize(Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY));
    }
}
