package com.winlator.pd2;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.winlator.core.EnvVars;

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
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Launcher dialogs and real preference-to-launch environment glue; no Wine prefix is needed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public final class Pd2ControllerBackendSettingsTest {
    private static Thread.UncaughtExceptionHandler hostHandler;

    @BeforeClass public static void preserveHostExceptionHandler() {
        hostHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @After public void restoreExceptionHandler() { Thread.setDefaultUncaughtExceptionHandler(hostHandler); }
    @AfterClass public static void restoreHostExceptionHandler() { Thread.setDefaultUncaughtExceptionHandler(hostHandler); }

    @Test public void compatibilitySelectionPersistsAndArmsTraceWithoutChangingOtherSettings() {
        try (ActivityController<Pd2Activity> controller = Robolectric.buildActivity(Pd2Activity.class)) {
            Pd2Activity activity = controller.setup().get();
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
            preferences.edit()
                    .remove(Pd2ControllerBackendPolicy.PREFERENCE)
                    .putBoolean(Pd2InputTraceRuntime.PREFERENCE, false)
                    .putBoolean(Pd2ControllerRuntime.ENABLED_PREFERENCE, false)
                    .putBoolean(Pd2HidReadRuntime.ENABLED_PREFERENCE, true)
                    .putString("pd2_renderer", "turnip,virgl")
                    .putString("pd2_arguments", "-ddraw -w")
                    .putBoolean(Pd2LaunchPolicy.CPU_PREFERENCE, true).commit();
            Map<String, ?> before = new HashMap<>(preferences.getAll());

            AlertDialog nativePath = openNativeInputPath(activity);
            ListView choices = nativePath.getListView();
            assertEquals(Pd2ControllerBackendPolicy.STANDARD, choices.getCheckedItemPosition());
            choices.performItemClick(null, Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY,
                    Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY);
            shadowOf(Looper.getMainLooper()).idle();

            assertEquals(Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY,
                    preferences.getInt(Pd2ControllerBackendPolicy.PREFERENCE, -1));
            assertTrue(Pd2InputTraceRuntime.requested(activity));
            Map<String, ?> after = new HashMap<>(preferences.getAll());
            before.remove(Pd2ControllerBackendPolicy.PREFERENCE);
            before.remove(Pd2InputTraceRuntime.PREFERENCE);
            after.remove(Pd2ControllerBackendPolicy.PREFERENCE);
            after.remove(Pd2InputTraceRuntime.PREFERENCE);
            assertEquals("The Native path choice may change only its preference and the one-shot trace", before, after);
        }
    }

    @Test public void standardSelectionPreservesAnAlreadyArmedManualTrace() {
        try (ActivityController<Pd2Activity> controller = Robolectric.buildActivity(Pd2Activity.class)) {
            Pd2Activity activity = controller.setup().get();
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
            preferences.edit().putInt(Pd2ControllerBackendPolicy.PREFERENCE,
                    Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY)
                    .putBoolean(Pd2InputTraceRuntime.PREFERENCE, true).commit();
            Map<String, ?> before = new HashMap<>(preferences.getAll());

            AlertDialog nativePath = openNativeInputPath(activity);
            assertEquals(Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY,
                    nativePath.getListView().getCheckedItemPosition());
            nativePath.getListView().performItemClick(null, Pd2ControllerBackendPolicy.STANDARD,
                    Pd2ControllerBackendPolicy.STANDARD);
            shadowOf(Looper.getMainLooper()).idle();

            assertEquals(Pd2ControllerBackendPolicy.STANDARD,
                    preferences.getInt(Pd2ControllerBackendPolicy.PREFERENCE, -1));
            assertTrue("Returning to Standard must preserve a separately requested trace",
                    Pd2InputTraceRuntime.requested(activity));
            Map<String, ?> after = new HashMap<>(preferences.getAll());
            before.remove(Pd2ControllerBackendPolicy.PREFERENCE);
            after.remove(Pd2ControllerBackendPolicy.PREFERENCE);
            assertEquals(before, after);
        }
    }

    @Test public void manualTraceDialogShowsItsChoicesAndCanArmTheNextPlay() {
        try (ActivityController<Pd2Activity> controller = Robolectric.buildActivity(Pd2Activity.class)) {
            Pd2Activity activity = controller.setup().get();
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
            preferences.edit().putBoolean(Pd2InputTraceRuntime.PREFERENCE, false).commit();
            AlertDialog settings = openControllerSettings(activity);
            settings.getListView().performItemClick(null, 3, 3);
            shadowOf(Looper.getMainLooper()).idle();

            AlertDialog trace = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotSame(settings, trace);
            assertTrue(trace.isShowing());
            assertNotNull(trace.getListView());
            assertTrue("The manual one-shot trace choices must be visible", trace.getListView().isShown());
            assertEquals(0, trace.getListView().getCheckedItemPosition());
            trace.getListView().performItemClick(null, 1, 1);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(Pd2InputTraceRuntime.requested(activity));
        }
    }

    @Test public void runtimeReadsBackendPreferenceForEachFreshEnvironmentAndRestoresDefaultBytes() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().commit();
        String baseline = Pd2LaunchPolicy.environment("-3dfx -w", false);
        assertEquals(baseline, runtimeEnvironment(context));

        preferences.edit().putInt(Pd2ControllerBackendPolicy.PREFERENCE,
                Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY).commit();
        Map<String, ?> selectedPreferences = new HashMap<>(preferences.getAll());
        assertCompatibilityOverlay(baseline, runtimeEnvironment(context));
        assertEquals("Reading launch policy must not mutate preferences", selectedPreferences, preferences.getAll());

        preferences.edit().putInt(Pd2ControllerBackendPolicy.PREFERENCE,
                Pd2ControllerBackendPolicy.STANDARD).commit();
        String restored = runtimeEnvironment(context);
        assertEquals(baseline, restored);
        EnvVars variables = new EnvVars(restored);
        assertFalse(variables.has("SDL_JOYSTICK_RAWINPUT"));
        assertFalse(variables.has("SDL_XINPUT_ENABLED"));
        assertFalse(variables.has("SDL_JOYSTICK_HIDAPI_XBOX"));
        assertEquals(Pd2LaunchPolicy.WINE_DEBUG, variables.get("WINEDEBUG"));
        assertEquals("1", variables.get("BOX64_DYNAREC"));
        assertEquals("false", variables.get("MESA_SHADER_CACHE_DISABLE"));
        assertEquals("1", variables.get("WINEESYNC"));
    }

    @Test public void runtimeCompatibilityPreservesExistingDirectDrawAndCpuChoices() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear().putInt(Pd2ControllerBackendPolicy.PREFERENCE,
                Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY)
                .putString("pd2_renderer", "turnip,virgl")
                .putString("pd2_arguments", "-ddraw -w")
                .putBoolean(Pd2LaunchPolicy.CPU_PREFERENCE, true).commit();
        Map<String, ?> before = new HashMap<>(preferences.getAll());

        String environment = runtimeEnvironment(context);
        assertCompatibilityOverlay(Pd2LaunchPolicy.environment("-ddraw -w", true), environment);
        EnvVars variables = new EnvVars(environment);
        assertEquals("0", variables.get("BOX64_DYNAREC"));
        assertEquals("ddraw=b;glide3x=n,b;mscoree,mshtml=d", variables.get("WINEDLLOVERRIDES"));
        assertEquals(Pd2LaunchPolicy.WINE_DEBUG, variables.get("WINEDEBUG"));
        assertEquals(before, preferences.getAll());
    }

    private static AlertDialog openNativeInputPath(Pd2Activity activity) {
        AlertDialog controller = openControllerSettings(activity);
        controller.getListView().performItemClick(null, 0, 0);
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog nativePath = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotSame(controller, nativePath);
        assertTrue(nativePath.isShowing());
        assertNotNull(nativePath.getListView());
        assertTrue("Native input choices must be visible, not a detached list behind a message",
                nativePath.getListView().isShown());
        return nativePath;
    }

    private static AlertDialog openControllerSettings(Pd2Activity activity) {
        View settings = findText(activity.findViewById(android.R.id.content), "Launch settings");
        assertNotNull(settings);
        assertTrue(settings.performClick());
        AlertDialog launch = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(launch.getButton(DialogInterface.BUTTON_POSITIVE).performClick());
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog controller = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotSame(launch, controller);
        assertNotNull(controller.getListView());
        assertTrue(controller.getListView().isShown());
        return controller;
    }

    private static String runtimeEnvironment(Context context) throws Exception {
        Method method = Pd2Runtime.class.getDeclaredMethod("environment", Context.class);
        method.setAccessible(true);
        return (String) method.invoke(null, context);
    }

    private static void assertCompatibilityOverlay(String baseline, String selected) {
        EnvVars variables = new EnvVars(selected);
        assertEquals("0", variables.get("SDL_JOYSTICK_RAWINPUT"));
        assertEquals("1", variables.get("SDL_XINPUT_ENABLED"));
        assertEquals("0", variables.get("SDL_JOYSTICK_HIDAPI_XBOX"));
        variables.remove("SDL_JOYSTICK_RAWINPUT");
        variables.remove("SDL_XINPUT_ENABLED");
        variables.remove("SDL_JOYSTICK_HIDAPI_XBOX");
        assertEquals("All pre-existing Wine, graphics and CPU flags must survive", baseline, variables.toString());
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
