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
public final class Pd2DisplayPolicyTest {
    @Test public void newAndExistingUsersRequest60HzUnlessTheyExplicitlyDisableIt() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication());
        assertEquals(60f, Pd2DisplayPolicy.requestedFrameRate(preferences), 0f);
        assertFalse("Reading defaults must not write preferences", preferences.contains(Pd2DisplayPolicy.REQUEST_PREFERENCE));
        preferences.edit().putBoolean(Pd2DisplayPolicy.REQUEST_PREFERENCE, false).commit();
        assertEquals(0f, Pd2DisplayPolicy.requestedFrameRate(preferences), 0f);
        preferences.edit().putBoolean(Pd2DisplayPolicy.REQUEST_PREFERENCE, true).commit();
        assertEquals(60f, Pd2DisplayPolicy.requestedFrameRate(preferences), 0f);
    }
}
