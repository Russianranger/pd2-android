package com.winlator.pd2;

import android.app.Application;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2FramePacingPolicyTest {
    @Test public void implicitDefaultsRestoreGameFrameSettingsWithoutChangingDisplayRefreshPreferences() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        assertEquals(Pd2FramePacingPolicy.GAME_SETTINGS, Pd2FramePacingPolicy.requestedFps(preferences));
        assertFalse(preferences.contains(Pd2FramePacingPolicy.PREFERENCE));
        preferences.edit().putBoolean(Pd2DisplayPolicy.REQUEST_PREFERENCE, false).commit();
        assertEquals(Pd2FramePacingPolicy.GAME_SETTINGS, Pd2FramePacingPolicy.requestedFps(preferences));
    }

    @Test public void thirtyAndUncappedPersistWhileInvalidChoicesUseTheDefault() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        for (int fps : new int[]{30, 0, 60, -1}) {
            preferences.edit().putInt(Pd2FramePacingPolicy.PREFERENCE, fps).commit();
            assertEquals(fps, Pd2FramePacingPolicy.requestedFps(preferences));
        }
        for (int invalid : new int[]{-2, 25, 120, Integer.MAX_VALUE}) {
            preferences.edit().putInt(Pd2FramePacingPolicy.PREFERENCE, invalid).commit();
            assertEquals(Pd2FramePacingPolicy.GAME_SETTINGS, Pd2FramePacingPolicy.requestedFps(preferences));
        }
        assertEquals(60f, Pd2DisplayPolicy.requestedFrameRate(preferences), 0f);
    }

    @Test public void invalidPreferenceTypesRecoverAndLightEffectsCanBeRestored() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        preferences.edit().putString(Pd2FramePacingPolicy.PREFERENCE, "invalid")
                .putString(Pd2FramePacingPolicy.LIGHT_PREFERENCE, "invalid").commit();
        assertEquals(Pd2FramePacingPolicy.GAME_SETTINGS, Pd2FramePacingPolicy.requestedFps(preferences));
        assertTrue(Pd2FramePacingPolicy.lightGraphics(preferences));
        preferences.edit().putBoolean(Pd2FramePacingPolicy.LIGHT_PREFERENCE, false).commit();
        assertFalse(Pd2FramePacingPolicy.lightGraphics(preferences));
        assertFalse(Pd2FramePacingPolicy.classicCursor(preferences));
        preferences.edit().putBoolean(Pd2FramePacingPolicy.CLASSIC_CURSOR_PREFERENCE, true).commit();
        assertTrue(Pd2FramePacingPolicy.classicCursor(preferences));
        preferences.edit().putString(Pd2FramePacingPolicy.CLASSIC_CURSOR_PREFERENCE, "invalid").commit();
        assertFalse(Pd2FramePacingPolicy.classicCursor(preferences));
    }
}
