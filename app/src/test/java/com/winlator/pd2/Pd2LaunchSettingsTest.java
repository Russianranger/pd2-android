package com.winlator.pd2;

import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public final class Pd2LaunchSettingsTest {
    private static Thread.UncaughtExceptionHandler hostHandler;
    @BeforeClass public static void preserveHandler() { hostHandler = Thread.getDefaultUncaughtExceptionHandler(); }
    @After public void restoreHandler() { Thread.setDefaultUncaughtExceptionHandler(hostHandler); }

    @Test public void gameNativeArgumentsPersistAndKeepTheCorrectRendererSelection() {
        try (ActivityController<Pd2Activity> controller = Robolectric.buildActivity(Pd2Activity.class)) {
            Pd2Activity activity = controller.setup().get();
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
            View settings = findText(activity.findViewById(android.R.id.content), "Launch settings");
            assertNotNull(settings);
            settings.performClick();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getListView().performItemClick(null, 4, 4);
            assertEquals("turnip,zink", preferences.getString("pd2_renderer", ""));
            assertEquals("-3dfx -dxnocompatmodefix", preferences.getString("pd2_arguments", ""));
            settings.performClick();
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(4, dialog.getListView().getCheckedItemPosition());
            dialog.getListView().performItemClick(null, 3, 3);
            assertEquals("turnip,virgl", preferences.getString("pd2_renderer", ""));
            assertEquals("-ddraw -w", preferences.getString("pd2_arguments", ""));
        }
    }

    @Test public void framePacingAndDisplayChoicesPersistIndependentlyWithoutChangingController() throws Exception {
        try (ActivityController<Pd2Activity> controller = Robolectric.buildActivity(Pd2Activity.class)) {
            Pd2Activity activity = controller.setup().get();
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
            preferences.edit().putBoolean(Pd2DisplayPolicy.REQUEST_PREFERENCE, false).commit();
            int backend = Pd2ControllerBackendPreferences.get(preferences);
            java.lang.reflect.Method open = Pd2Activity.class.getDeclaredMethod("showPerformanceSettings");
            open.setAccessible(true);
            open.invoke(activity);
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(3, dialog.getListView().getCheckedItemPosition());
            dialog.getListView().performItemClick(null, 1, 1);
            assertEquals(30, Pd2FramePacingPolicy.requestedFps(preferences));
            assertEquals(0f, Pd2DisplayPolicy.requestedFrameRate(preferences), 0f);
            open.invoke(activity);
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(1, dialog.getListView().getCheckedItemPosition());
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(1, dialog.getListView().getCheckedItemPosition());
            dialog.getListView().performItemClick(null, 0, 0);
            assertEquals(60f, Pd2DisplayPolicy.requestedFrameRate(preferences), 0f);
            assertEquals(30, Pd2FramePacingPolicy.requestedFps(preferences));
            open.invoke(activity);
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getListView().performItemClick(null, 2, 2);
            assertEquals(0, Pd2FramePacingPolicy.requestedFps(preferences));
            open.invoke(activity);
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getListView().performItemClick(null, 3, 3);
            assertEquals(Pd2FramePacingPolicy.GAME_SETTINGS, Pd2FramePacingPolicy.requestedFps(preferences));
            assertEquals(60f, Pd2DisplayPolicy.requestedFrameRate(preferences), 0f);
            assertEquals(backend, Pd2ControllerBackendPreferences.get(preferences));
        }
    }

    @Test public void graphicsAndAudioChoicesPersistIndependentlyAndKeepControllerAndDisplay() throws Exception {
        try (ActivityController<Pd2Activity> controller = Robolectric.buildActivity(Pd2Activity.class)) {
            Pd2Activity activity = controller.setup().get();
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
            int backend = Pd2ControllerBackendPreferences.get(preferences);
            preferences.edit().putInt(Pd2FramePacingPolicy.PREFERENCE, 30).commit();
            AlertDialog dialog = openDialog(activity, "showPerformanceSettings");
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getListView().performItemClick(null, 0, 0);
            shadowOf(Looper.getMainLooper()).idle();
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(0, dialog.getListView().getCheckedItemPosition());
            dialog.getListView().performItemClick(null, 1, 1);
            assertFalse(Pd2FramePacingPolicy.lightGraphics(preferences));

            dialog = openDialog(activity, "showAudioBufferSettings");
            assertEquals(0, dialog.getListView().getCheckedItemPosition());
            dialog.getListView().performItemClick(null, 1, 1);
            assertEquals(64, Pd2AudioPolicy.latencyMillis(preferences));
            dialog = openDialog(activity, "showAudioQualitySettings");
            assertEquals(0, dialog.getListView().getCheckedItemPosition());
            dialog.getListView().performItemClick(null, 1, 1);
            assertTrue(Pd2AudioPolicy.ecoMode(preferences));
            assertEquals(64, Pd2AudioPolicy.latencyMillis(preferences));
            assertEquals(30, Pd2FramePacingPolicy.requestedFps(preferences));
            assertEquals(60f, Pd2DisplayPolicy.requestedFrameRate(preferences), 0f);
            assertEquals(backend, Pd2ControllerBackendPreferences.get(preferences));

            dialog = openDialog(activity, "showGameCursorSettings");
            assertEquals(0, dialog.getListView().getCheckedItemPosition());
            dialog.getListView().performItemClick(null, 1, 1);
            assertTrue(Pd2FramePacingPolicy.classicCursor(preferences));
            dialog = openDialog(activity, "showImageScalingSettings");
            assertEquals(0, dialog.getListView().getCheckedItemPosition());
            dialog.getListView().performItemClick(null, 1, 1);
            assertFalse(Pd2FramePacingPolicy.simpleScaling(preferences));
            dialog = openDialog(activity, "showImageScalingSettings");
            assertEquals(1, dialog.getListView().getCheckedItemPosition());
            dialog.getListView().performItemClick(null, 0, 0);
            assertTrue(Pd2FramePacingPolicy.simpleScaling(preferences));
            assertTrue(Pd2FramePacingPolicy.classicCursor(preferences));
            assertFalse(Pd2FramePacingPolicy.lightGraphics(preferences));
            assertTrue(Pd2AudioPolicy.ecoMode(preferences));
            assertEquals(30, Pd2FramePacingPolicy.requestedFps(preferences));
            assertEquals(backend, Pd2ControllerBackendPreferences.get(preferences));
            dialog = openDialog(activity, "showGameCursorSettings");
            assertEquals(1, dialog.getListView().getCheckedItemPosition());
            dialog.getListView().performItemClick(null, 0, 0);
            assertFalse(Pd2FramePacingPolicy.classicCursor(preferences));

            dialog = openDialog(activity, "showAudioQualitySettings");
            assertEquals(1, dialog.getListView().getCheckedItemPosition());
            dialog.getListView().performItemClick(null, 0, 0);
            assertFalse(Pd2AudioPolicy.ecoMode(preferences));
            assertEquals(64, Pd2AudioPolicy.latencyMillis(preferences));
        }
    }

    private static AlertDialog openDialog(Pd2Activity activity, String name) throws Exception {
        java.lang.reflect.Method open = Pd2Activity.class.getDeclaredMethod(name);
        open.setAccessible(true);
        open.invoke(activity);
        shadowOf(Looper.getMainLooper()).idle();
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findText(group.getChildAt(i), text);
                if (match != null) return match;
            }
        }
        return null;
    }
}
