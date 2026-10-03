package com.winlator.pd2;

import android.content.SharedPreferences;
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
