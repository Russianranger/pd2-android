package com.winlator.pd2;

import android.app.Application;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.winlator.core.KeyValueSet;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2AudioPolicyTest {
    private SharedPreferences preferences() {
        return PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
    }

    @Test public void balancedBufferIsDefaultAndEcoRemainsOptIn() {
        SharedPreferences preferences = preferences();
        assertEquals(32, Pd2AudioPolicy.latencyMillis(preferences));
        assertFalse(Pd2AudioPolicy.ecoMode(preferences));
        assertFalse(preferences.contains(Pd2AudioPolicy.PREFERENCE));
        KeyValueSet configuration = new KeyValueSet(Pd2AudioPolicy.configuration(preferences));
        assertEquals(32, configuration.getInt("latencyMillis"));
        assertEquals(1, configuration.getInt("performanceMode"));
    }

    @Test public void supportedChoicesPersistAndMalformedPreferencesHaveSafeDefaults() {
        SharedPreferences preferences = preferences();
        for (int latency : new int[]{16, 32, 64}) {
            preferences.edit().putInt(Pd2AudioPolicy.PREFERENCE, latency).commit();
            assertEquals(latency, Pd2AudioPolicy.latencyMillis(preferences));
        }
        for (int latency : new int[]{0, -1, 65568, Integer.MAX_VALUE}) {
            preferences.edit().putInt(Pd2AudioPolicy.PREFERENCE, latency).commit();
            assertEquals(32, Pd2AudioPolicy.latencyMillis(preferences));
        }
        preferences.edit().putString(Pd2AudioPolicy.PREFERENCE, "32")
                .putString(Pd2AudioPolicy.ECO_PREFERENCE, "true").commit();
        assertEquals(32, Pd2AudioPolicy.latencyMillis(preferences));
        assertFalse(Pd2AudioPolicy.ecoMode(preferences));
        preferences.edit().putBoolean(Pd2AudioPolicy.ECO_PREFERENCE, true).commit();
        assertTrue(Pd2AudioPolicy.ecoMode(preferences));
    }
}
