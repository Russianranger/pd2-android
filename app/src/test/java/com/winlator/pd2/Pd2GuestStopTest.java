package com.winlator.pd2;

import android.app.Application;

import com.winlator.core.EnvVars;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowProcess;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2GuestStopTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void failedPd2CleanupDoesNotFallBackToAnUnverifiedNumericRootPid() throws Exception {
        verifyPd2Stop(3);
    }

    @Test public void successfulPd2CleanupClearsTheRootAndRetainsCallbackGenerationProtection() throws Exception {
        verifyPd2Stop(0);
    }

    @Test public void genericRuntimeRetainsItsExistingRootProcessStopBehavior() throws Exception {
        ShadowProcess.clearKilledProcesses();
        GuestProgramLauncherComponent guest = new GuestProgramLauncherComponent();
        set(guest, "pid", 42424);
        guest.stop();
        assertTrue(ShadowProcess.wasKilled(42424));
        assertEquals(-1, field(guest, "pid").getInt(guest));
    }

    private void verifyPd2Stop(int afterStopKill) throws Exception {
        ShadowProcess.clearKilledProcesses();
        File root = temporary.newFolder();
        File prefix = new File(root, "home/xuser-2/.wine"); assertTrue(prefix.mkdirs());
        Runner runner = new Runner();
        Pd2WineSession session = new Pd2WineSession(root, "/opt/wine", new EnvVars().put("WINEPREFIX", prefix.getPath()), runner);
        assertTrue(session.beforeLaunch().passed);
        GuestProgramLauncherComponent guest = new GuestProgramLauncherComponent();
        set(guest, "pd2WineSession", session);
        set(guest, "pd2CleanupRecorded", true); // The recording path is separately covered by launch diagnostics.
        set(guest, "pid", 42424);
        set(guest, "processGeneration", 7L);
        runner.kill = afterStopKill;
        try {
            guest.stop();
            assertFalse("Failed scoped cleanup must never authorize a numeric-PID fallback", ShadowProcess.wasKilled(42424));
            assertEquals(-1, field(guest, "pid").getInt(guest));
            assertEquals(8L, field(guest, "processGeneration").getLong(guest));
            assertEquals(afterStopKill == 0, session.stop().passed);
            guest.stop();
            assertFalse(ShadowProcess.wasKilled(42424));
            assertEquals(-1, field(guest, "pid").getInt(guest));
        } finally { session.stop(); }
    }

    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void set(Object object, String name, Object value) throws Exception { field(object, name).set(object, value); }

    private static final class Runner implements Pd2WineSession.Runner {
        int kill;
        public int run(List<String> command, Map<String, String> environment, File directory) {
            return command.get(2).startsWith("-k") ? kill : 0;
        }
        public boolean portsFree() { return true; }
    }
}
