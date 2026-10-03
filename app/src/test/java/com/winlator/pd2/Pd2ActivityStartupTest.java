package com.winlator.pd2;

import android.app.Application;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real launcher creation using Android 13 framework/resources, without launching Wine. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public final class Pd2ActivityStartupTest {
    private static Thread.UncaughtExceptionHandler hostHandler;

    @BeforeClass public static void preserveHostExceptionHandler() {
        hostHandler = Thread.getDefaultUncaughtExceptionHandler();
    }
    @After public void restoreExceptionHandler() { Thread.setDefaultUncaughtExceptionHandler(hostHandler); }
    @AfterClass public static void restoreHostExceptionHandler() { Thread.setDefaultUncaughtExceptionHandler(hostHandler); }

    @Test public void firstLaunchBuildsUiWithoutStartingForegroundService() {
        Application application = RuntimeEnvironment.getApplication();
        try (ActivityController<Pd2Activity> controller = Robolectric.buildActivity(Pd2Activity.class)) {
            Pd2Activity activity = controller.setup().get();
            assertFalse("First launch must remain on the launcher", activity.isFinishing());
            assertTrue("Launcher title must be displayed", containsText(
                    activity.findViewById(android.R.id.content), "PROJECT DIABLO II"));
            Intent service = shadowOf(application).getNextStartedService();
            assertNull("Automatic installation validation must not request a foreground service", service);
        }
    }

    private static boolean containsText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsText(group.getChildAt(i), text)) return true;
            }
        }
        return false;
    }
}
