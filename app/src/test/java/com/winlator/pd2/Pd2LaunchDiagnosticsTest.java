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
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
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
        assertTrue(merged.get("WINEDEBUG").contains("+xinput"));
        assertTrue(merged.get("WINEDEBUG").contains("+rawinput"));
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
        assertEquals(0x100000L, info.getLong("stackReserveBytes"));
        assertEquals(0x1000L, info.getLong("stackCommitBytes"));
    }

    @Test public void pe64HeaderPreservesAddressesAboveFourGiB() throws Exception {
        JSONObject info = Pd2LaunchDiagnostics.peHeader(pe(true, 0x180000000L));
        assertEquals("PE32+", info.getString("format"));
        assertEquals("0x180000000", info.getString("preferredImageBase"));
        assertEquals("0x8664", info.getString("machine"));
        assertEquals(0x200000000L, info.getLong("stackReserveBytes"));
        assertEquals(0x100002000L, info.getLong("stackCommitBytes"));
    }

    @Test public void truncatedDeclaredStackFieldsAreRejectedEvenIfBytesExistAfterHeader() throws Exception {
        for (boolean x64 : new boolean[]{false, true}) {
            File file = pe(x64, 0x400000L);
            byte[] bytes = Files.readAllBytes(file.toPath());
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(148, (short)(x64 ? 87 : 79));
            Files.write(file.toPath(), bytes);
            try { Pd2LaunchDiagnostics.peHeader(file); fail("Declared stack header must cover both fields"); }
            catch (IOException expected) { assertEquals("Truncated stack fields in optional header", expected.getMessage()); }
        }
    }

    @Test public void hashesOnlySelectedCoreFilesAndReportsOversizeWithoutContents() throws Exception {
        File installed = installation();
        File game = new File(installed, "ProjectD2");
        Files.write(new File(installed, "Fog.dll").toPath(), "abc".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(game, "Storm.dll").toPath(), "abc".getBytes(StandardCharsets.UTF_8));
        try (RandomAccessFile oversized = new RandomAccessFile(new File(game, "PD2_EXT.dll"), "rw")) {
            oversized.setLength(Pd2LaunchDiagnostics.MAX_HASH_BYTES + 1L);
        }
        JSONObject report = Pd2LaunchDiagnostics.installationFiles(installed, Pd2InstallValidator.validate(installed));
        Map<String, JSONObject> entries = new HashMap<>();
        for (int index = 0; index < report.getJSONArray("files").length(); index++) {
            JSONObject entry = report.getJSONArray("files").getJSONObject(index);
            entries.put(entry.getString("path"), entry);
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                entries.get("Fog.dll").getString("sha256"));
        assertEquals(64, entries.get("ProjectD2/Game.exe").getString("sha256").length());
        assertFalse(entries.get("ProjectD2/Storm.dll").has("sha256"));
        assertFalse(entries.get("ProjectD2/PD2_EXT.dll").has("sha256"));
        assertEquals("File exceeds 8 MiB hash limit", entries.get("ProjectD2/PD2_EXT.dll").getString("hashError"));
        assertEquals(Pd2LaunchDiagnostics.MAX_HASH_BYTES, report.getInt("maxHashBytes"));
    }

    @Test public void hashLimitIncludesExactlyEightMiBAndRejectsTheNextByte() throws Exception {
        File file = File.createTempFile("pd2-hash-boundary", ".dll"); file.deleteOnExit();
        try (RandomAccessFile input = new RandomAccessFile(file, "rw")) { input.setLength(Pd2LaunchDiagnostics.MAX_HASH_BYTES); }
        // Known SHA-256 of 8 MiB of zero bytes, including all chunks at the inclusive limit.
        assertEquals("2daeb1f36095b44b318410b3f4e8b5d989dcc7bb023d1426c492dab0a3053e74", Pd2LaunchDiagnostics.coreFileSha256(file));
        try (RandomAccessFile input = new RandomAccessFile(file, "rw")) { input.setLength(Pd2LaunchDiagnostics.MAX_HASH_BYTES + 1L); }
        try { Pd2LaunchDiagnostics.coreFileSha256(file); fail("Oversized core file must not be hashed"); }
        catch (IOException expected) { assertEquals("File exceeds 8 MiB hash limit", expected.getMessage()); }
    }

    @Test public void gameLogsExportOnlyAllowlistAndTwoMostRecentlyModifiedDateLogs() throws Exception {
        File installed = installation();
        File game = new File(installed, "ProjectD2");
        for (int index = 1; index <= 3; index++) {
            File log = new File(game, "D210030" + index + ".txt");
            Files.write(log.toPath(), ("dated " + index).getBytes(StandardCharsets.UTF_8));
            assertTrue(log.setLastModified(1000L * index));
        }
        Files.write(new File(game, "d2dx_log.txt").toPath(), "wrapper dx".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(game, "d2gl.log").toPath(), "wrapper gl".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(game, "passwords.txt").toPath(), "must remain private".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(game, "D2save.d2s").toPath(), "save".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(installed, "D2100304.txt").toPath(), "base log excluded".getBytes(StandardCharsets.UTF_8));
        File nested = new File(game, "nested"); assertTrue(nested.mkdir());
        Files.write(new File(nested, "d2gl.log").toPath(), "nested excluded".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> entries = exportedLogs(installed, Pd2InstallValidator.validate(installed));
        assertEquals(4, entries.size());
        assertFalse(entries.containsKey("game-logs/D2100301.txt"));
        assertEquals("dated 2", new String(entries.get("game-logs/D2100302.txt"), StandardCharsets.UTF_8));
        assertEquals("dated 3", new String(entries.get("game-logs/D2100303.txt"), StandardCharsets.UTF_8));
        assertEquals("wrapper dx", new String(entries.get("game-logs/d2dx_log.txt"), StandardCharsets.UTF_8));
        assertEquals("wrapper gl", new String(entries.get("game-logs/d2gl.log"), StandardCharsets.UTF_8));
    }

    @Test public void gameLogCapPreservesFirstFaultAndFinalSection() throws Exception {
        File installed = installation();
        byte[] bytes = new byte[1024 * 1024]; Arrays.fill(bytes, (byte)'x');
        byte[] first = "First fault: Fog.dll+1879A\n".getBytes(StandardCharsets.UTF_8);
        byte[] last = "\nFinal exception details".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(first, 0, bytes, 0, first.length);
        System.arraycopy(last, 0, bytes, bytes.length - last.length, last.length);
        Files.write(new File(installed, "ProjectD2/d2gl.log").toPath(), bytes);
        byte[] snapshot = exportedLogs(installed, Pd2InstallValidator.validate(installed)).get("game-logs/d2gl.log");
        assertEquals(Pd2LaunchDiagnostics.MAX_GAME_LOG_BYTES, snapshot.length);
        String text = new String(snapshot, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("First fault: Fog.dll+1879A\n"));
        assertTrue(text.endsWith("\nFinal exception details"));
        assertTrue(text.contains("middle omitted; first and last sections preserved"));
        assertEquals(bytes.length, new File(installed, "ProjectD2/d2gl.log").length());
    }

    @Test public void postValidationSymlinkCannotExportOutsideInstallationOrHashPrivateFile() throws Exception {
        File installed = installation();
        Pd2InstallValidator.Result accepted = Pd2InstallValidator.validate(installed);
        assertTrue(accepted.valid);
        File outside = File.createTempFile("pd2-private-file", ".txt"); outside.deleteOnExit();
        Files.write(outside.toPath(), "private contents".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(installed, "ProjectD2/d2gl.log").toPath(), outside.toPath());
        Files.createSymbolicLink(new File(installed, "Fog.dll").toPath(), outside.toPath());
        assertTrue(exportedLogs(installed, accepted).isEmpty());
        assertFalse(Pd2LaunchDiagnostics.installationFiles(installed, accepted).toString().contains("Fog.dll"));
        // The accepted directory itself can also have changed into an outside link after validation.
        File directory = new File(installed, "ProjectD2");
        File outsideDirectory = Files.createTempDirectory("pd2-outside-game").toFile();
        assertTrue(directory.renameTo(new File(installed, "old-game")));
        Files.write(new File(outsideDirectory, "d2gl.log").toPath(), "private log".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(directory.toPath(), outsideDirectory.toPath());
        assertTrue(exportedLogs(installed, accepted).isEmpty());
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
        if (x64) { data.putLong(224, 0x200000000L); data.putLong(232, 0x100002000L); }
        else { data.putInt(224, 0x100000); data.putInt(228, 0x1000); }
        File file = File.createTempFile("pd2-pe-fixture", ".dll"); file.deleteOnExit();
        Files.write(file.toPath(), bytes); return file;
    }
    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File installation() throws Exception {
        File installed = Files.createTempDirectory("pd2-diagnostics-install").toFile();
        File game = new File(installed, "ProjectD2"); assertTrue(game.mkdir());
        Files.copy(pe(false, 0x400000L).toPath(), new File(game, "Game.exe").toPath());
        Files.copy(pe(false, 0x10000000L).toPath(), new File(game, "ProjectDiablo.dll").toPath());
        byte[] mpq = new byte[32]; mpq[0] = 'M'; mpq[1] = 'P'; mpq[2] = 'Q'; mpq[3] = 0x1a;
        Files.write(new File(game, "pd2data.mpq").toPath(), mpq);
        for (String name : new String[]{"d2data.mpq", "d2char.mpq", "d2sfx.mpq", "d2exp.mpq"})
            Files.write(new File(installed, name).toPath(), mpq);
        assertTrue(Pd2InstallValidator.validate(installed).valid);
        return installed;
    }

    private static Map<String, byte[]> exportedLogs(File installed, Pd2InstallValidator.Result accepted) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { Pd2LaunchDiagnostics.exportGameLogs(installed, accepted, zip); }
        Map<String, byte[]> result = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream contents = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) != -1) contents.write(buffer, 0, read);
                result.put(entry.getName(), contents.toByteArray());
            }
        }
        return result;
    }
}
