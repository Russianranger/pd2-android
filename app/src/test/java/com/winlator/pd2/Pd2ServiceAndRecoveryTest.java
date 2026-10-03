package com.winlator.pd2;

import android.app.Application;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
public final class Pd2ServiceAndRecoveryTest {
    private static Thread.UncaughtExceptionHandler hostHandler;
    @BeforeClass public static void preserveHostExceptionHandler() { hostHandler = Thread.getDefaultUncaughtExceptionHandler(); }
    @After public void restoreExceptionHandler() { Thread.setDefaultUncaughtExceptionHandler(hostHandler); }
    @AfterClass public static void restoreHostExceptionHandler() { Thread.setDefaultUncaughtExceptionHandler(hostHandler); }

    @Test public void explicitJobWaitsUntilForegroundNotificationExists() {
        Application app = RuntimeEnvironment.getApplication();
        AtomicBoolean ran = new AtomicBoolean();
        Pd2WorkService.start(app, () -> ran.set(true));
        assertFalse("Queued job must wait for service creation", ran.get());
        assertEquals(Pd2WorkService.class.getName(), shadowOf(app).getNextStartedService().getComponent().getClassName());
        ServiceController<Pd2WorkService> controller = Robolectric.buildService(Pd2WorkService.class);
        try {
            Pd2WorkService service = controller.create().get();
            assertNotNull("Notification must precede jobs", shadowOf(service).getLastForegroundNotification());
            assertFalse("onCreate must not run queued jobs", ran.get());
            controller.startCommand(0, 1);
            assertTrue("Job starts after foreground notification", ran.get());
        } finally { controller.destroy(); }
    }

    @Test public void freshEntryRoutesToLauncherWithoutStartingJobs() {
        Application app = RuntimeEnvironment.getApplication();
        Pd2CrashLog.acknowledge(app);
        try (ActivityController<Pd2EntryActivity> controller = Robolectric.buildActivity(Pd2EntryActivity.class)) {
            Pd2EntryActivity activity = controller.create().get();
            assertTrue(activity.isFinishing());
            assertEquals(Pd2Activity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            assertNull(shadowOf(app).getNextStartedService());
        }
    }

    @Test public void pendingCrashEntryRoutesToRecoveryWithoutStartingJobs() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Pd2CrashLog.record(app, Thread.currentThread(), new IllegalStateException("entry fixture"), "Android 13 test");
        try (ActivityController<Pd2EntryActivity> controller = Robolectric.buildActivity(Pd2EntryActivity.class)) {
            Pd2EntryActivity activity = controller.create().get();
            assertTrue(activity.isFinishing());
            assertEquals(Pd2RecoveryActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            assertTrue(Pd2CrashLog.hasPending(app));
            assertNull(shadowOf(app).getNextStartedService());
        }
    }

    @Test public void pendingCrashOpensRecoveryBeforeLauncherWork() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Pd2CrashLog.record(app, Thread.currentThread(), new IllegalStateException("startup fixture"), "Android 13 test");
        try (ActivityController<Pd2Activity> controller = Robolectric.buildActivity(Pd2Activity.class)) {
            Pd2Activity activity = controller.create().get();
            assertTrue(activity.isFinishing());
            assertEquals(Pd2RecoveryActivity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            assertTrue("Recovery must retain the report until explicit retry", Pd2CrashLog.hasPending(app));
            assertNull("Recovery must not start installation jobs", shadowOf(app).getNextStartedService());
        }
    }

    @Test public void recoveryRetryAcknowledgesReportAndOpensLauncher() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Pd2CrashLog.record(app, Thread.currentThread(), new IllegalStateException("retry fixture"), "Android 13 test");
        try (ActivityController<Pd2RecoveryActivity> controller = Robolectric.buildActivity(Pd2RecoveryActivity.class)) {
            Pd2RecoveryActivity activity = controller.setup().get();
            Button retry = button(activity.findViewById(android.R.id.content), "Retry launcher");
            assertNotNull("Recovery must provide an explicit retry", retry);
            retry.performClick();
            assertFalse(Pd2CrashLog.hasPending(app));
            assertTrue("Crash report remains available for support", Pd2CrashLog.getCrashFile(app).isFile());
            assertEquals(Pd2Activity.class.getName(), shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        }
    }

    private static Button button(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button)view).getText())) return (Button)view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = button(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
