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

    @Test public void simpleScalingDefaultsOnAndExplicitGameShaderSurvivesWithoutChangingAcceptedChoices() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        preferences.edit().putInt(Pd2FramePacingPolicy.PREFERENCE, 60)
                .putBoolean(Pd2FramePacingPolicy.CLASSIC_CURSOR_PREFERENCE, true)
                .putBoolean(Pd2AudioPolicy.ECO_PREFERENCE, true).commit();
        assertTrue(Pd2FramePacingPolicy.simpleScaling(preferences));
        preferences.edit().putBoolean(Pd2FramePacingPolicy.SIMPLE_SCALING_PREFERENCE, false).commit();
        assertFalse(Pd2FramePacingPolicy.simpleScaling(preferences));
        assertEquals(60, Pd2FramePacingPolicy.requestedFps(preferences));
        assertTrue(Pd2FramePacingPolicy.classicCursor(preferences));
        assertTrue(Pd2AudioPolicy.ecoMode(preferences));
        preferences.edit().putString(Pd2FramePacingPolicy.SIMPLE_SCALING_PREFERENCE, "invalid").commit();
        assertTrue(Pd2FramePacingPolicy.simpleScaling(preferences));
    }
}
