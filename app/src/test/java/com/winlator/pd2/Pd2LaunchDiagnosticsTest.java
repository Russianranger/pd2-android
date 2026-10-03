package com.winlator.pd2;

import android.app.Application;
import androidx.preference.PreferenceManager;
import com.winlator.box64.Box64PresetManager;
import com.winlator.container.Container;
import com.winlator.core.EnvVars;
import org.json.JSONObject;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class Pd2LaunchDiagnosticsTest {
    private static Thread.UncaughtExceptionHandler hostHandler;
    @BeforeClass public static void preserveExceptionHandler() { hostHandler = Thread.getDefaultUncaughtExceptionHandler(); }
    @After public void restoreExceptionHandler() { Thread.setDefaultUncaughtExceptionHandler(hostHandler); }

    @Test public void directDrawBypassesNativeWrapperWhileGlidePreservesIt() {
        EnvVars directDraw = new EnvVars(Pd2LaunchPolicy.environment("-ddraw -w", false));
        assertEquals("ddraw=b;glide3x=n,b;mscoree,mshtml=d", directDraw.get("WINEDLLOVERRIDES"));
        assertEquals("ddraw,glide3x=n,b;mscoree,mshtml=d",
                new EnvVars(Pd2LaunchPolicy.environment("-3dfx -w", false)).get("WINEDLLOVERRIDES"));
        assertEquals("ddraw,glide3x=n,b;mscoree,mshtml=d",
                new EnvVars(Pd2LaunchPolicy.environment("-ddraw-other -w", false)).get("WINEDLLOVERRIDES"));
        assertEquals(directDraw.get("WINEDLLOVERRIDES"),
                new EnvVars(Pd2LaunchPolicy.environment("  -DDRAW\t-w ", false)).get("WINEDLLOVERRIDES"));
    }

    @Test public void interpreterOverridesDefaultAndRetainsExceptionDiagnostics() {
        EnvVars merged = Box64PresetManager.getEnvVars(RuntimeEnvironment.getApplication(), Pd2LaunchPolicy.cpuPreset());
        merged.put("BOX64_DYNAREC", "1");
        merged.putAll(Pd2LaunchPolicy.environment("-ddraw -w", true));
        assertEquals("0", merged.get("BOX64_DYNAREC"));
        assertEquals("0", merged.get("BOX64_DYNAREC_BIGBLOCK"));
        assertEquals("2", merged.get("BOX64_DYNAREC_STRONGMEM"));
        assertEquals("0", merged.get("BOX64_DYNAREC_WEAKBARRIER"));
        assertTrue(merged.get("WINEDEBUG").contains("+seh"));
        assertTrue(merged.get("WINEDEBUG").contains("+loaddll"));
        assertEquals("1", merged.get("BOX64_SHOWSEGV"));
        assertEquals("1", new EnvVars(Pd2LaunchPolicy.environment("-ddraw -w", false)).get("BOX64_DYNAREC"));
    }

    @Test public void liveLogCapPreservesFirstExceptionAndMarksOmittedOutput() throws Exception {
        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        try (Pd2LogOutputStream log = new Pd2LogOutputStream(destination)) {
            log.write("First exception: fixture\n".getBytes(StandardCharsets.UTF_8));
            byte[] chunk = new byte[64 * 1024]; Arrays.fill(chunk, (byte)'x');
            for (int i = 0; i < 200; i++) log.write(chunk);
            int cappedBytes = destination.size();
            log.write("Must not grow".getBytes(StandardCharsets.UTF_8));
            assertEquals(cappedBytes, destination.size());
        }
        assertTrue(destination.size() <= Pd2LogOutputStream.MAX_BYTES);
        String output = destination.toString(StandardCharsets.UTF_8.name());
        assertTrue(output.startsWith("First exception: fixture\n"));
        assertTrue(output.endsWith("[PD2 runtime log reached 8 MiB; further output omitted]\n"));
    }

    @Test public void pe32HeaderReportsPreferredModuleAddressWithoutReadingContents() throws Exception {
        File file = pe(false, 0x6ff50000L);
        JSONObject info = Pd2LaunchDiagnostics.peHeader(file);
        assertEquals("PE32", info.getString("format"));
        assertEquals("0x6ff50000", info.getString("preferredImageBase"));
        assertEquals("0x14c", info.getString("machine"));
        assertEquals(0x5c000L, info.getLong("sizeOfImage"));
    }

    @Test public void pe64HeaderPreservesAddressesAboveFourGiB() throws Exception {
        JSONObject info = Pd2LaunchDiagnostics.peHeader(pe(true, 0x180000000L));
        assertEquals("PE32+", info.getString("format"));
        assertEquals("0x180000000", info.getString("preferredImageBase"));
        assertEquals("0x8664", info.getString("machine"));
    }

    @Test public void corruptPeOffsetDoesNotAllocateOrSeekAnUnboundedHeader() throws Exception {
        File file = pe(false, 0x400000);
        byte[] bytes = Files.readAllBytes(file.toPath());
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(60, Integer.MAX_VALUE);
        Files.write(file.toPath(), bytes);
        try { Pd2LaunchDiagnostics.peHeader(file); fail("Corrupt header must be rejected"); }
        catch (IOException expected) { assertEquals("Invalid PE header offset", expected.getMessage()); }
    }

    @Test public void nextLaunchArchivesPreviousAttemptAndIgnoresStaleExitCallback() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File logDirectory = new File(app.getFilesDir(), "pd2/logs"); logDirectory.mkdirs();
        Files.write(new File(logDirectory, "runtime.log").toPath(), "previous first exception".getBytes(StandardCharsets.UTF_8));
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(Pd2LaunchPolicy.CPU_PREFERENCE, true).commit();
        Container container = new Container(1);
        container.setGraphicsDriver("turnip,zink"); container.setBox64Preset(Pd2LaunchPolicy.cpuPreset());
        container.setEnvVars(Pd2LaunchPolicy.environment("-ddraw -w", true));
        String id = Pd2LaunchDiagnostics.begin(app, container, "-ddraw -w");
        File[] attempts = Pd2SessionLog.getAttemptsDirectory(app).listFiles();
        assertNotNull(attempts); assertEquals(1, attempts.length);
        assertTrue(read(attempts[0]).contains("previous first exception"));
        File manifest = new File(logDirectory, "launch.json");
        Pd2LaunchDiagnostics.exited(app, "old launch", 42);
        assertFalse(new JSONObject(read(manifest)).has("runtimeExitStatus"));
        Pd2LaunchDiagnostics.exited(app, id, 139);
        JSONObject report = new JSONObject(read(manifest));
        assertEquals(id, report.getString("launchId")); assertEquals(139, report.getInt("runtimeExitStatus"));
        assertEquals("-ddraw -w", report.getString("arguments")); assertTrue(report.getBoolean("interpreter"));
    }

    private static File pe(boolean x64, long imageBase) throws Exception {
        byte[] bytes = new byte[512];
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        data.putShort(0, (short)0x5a4d); data.putInt(60, 128); data.putInt(128, 0x4550);
        data.putShort(132, (short)(x64 ? 0x8664 : 0x14c)); data.putShort(148, (short)224);
        data.putShort(152, (short)(x64 ? 0x20b : 0x10b));
        if (x64) data.putLong(176, imageBase); else data.putInt(180, (int)imageBase);
        data.putInt(208, 0x5c000);
        File file = File.createTempFile("pd2-pe-fixture", ".dll"); file.deleteOnExit();
        Files.write(file.toPath(), bytes); return file;
    }
    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
