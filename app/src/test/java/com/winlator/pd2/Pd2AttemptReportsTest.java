package com.winlator.pd2;

import android.app.Application;

import com.winlator.container.Container;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public final class Pd2AttemptReportsTest {
    private File files;
    private File logs;
    private Thread.UncaughtExceptionHandler previousHandler;

    @Before public void createFixture() throws Exception {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        files = Files.createTempDirectory("pd2-attempt-report-test-").toFile();
        logs = new File(files, "pd2/logs");
        assertTrue(logs.mkdirs());
    }

    @After public void restoreHandler() { Thread.setDefaultUncaughtExceptionHandler(previousHandler); }

    @Test public void nextLaunchPreservesGenerationMatchedReportsBeforeTheyAreReplacedOrCleared() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File appLogs = new File(app.getFilesDir(), "pd2/logs");
        assertTrue(appLogs.mkdirs() || appLogs.isDirectory());
        String old = UUID.randomUUID().toString();
        write(new File(appLogs, "runtime.log"), "Gameplay: Save and Exit");
        write(new File(appLogs, "launch.json"), report(old, "old launch"));
        write(new File(appLogs, "controller.json"), report(old, "native failure counters"));
        write(new File(appLogs, "memory.json"), report(old, "old process identities"));
        String replacement = Pd2LaunchDiagnostics.begin(app, new Container(1), "-3dfx -w");
        Pd2ControllerDiagnostics.clearPreviousReport(app);
        assertNotEquals(old, replacement);
        assertFalse(new File(appLogs, "controller.json").exists());
        Map<String, byte[]> entries = exported(app.getFilesDir());
        assertEquals(4, entries.size());
        assertTrue(text(entries, "launch-attempt-").contains(old));
        assertTrue(text(entries, "controller-attempt-").contains("native failure counters"));
        assertTrue(text(entries, "memory-attempt-").contains("old process identities"));
        assertTrue(text(entries, "runtime-attempt-").contains("Gameplay: Save and Exit"));
        assertEquals(replacement, new JSONObject(read(new File(appLogs, "launch.json"))).getString("launchId"));
    }

    @Test public void staleMalformedAndOversizeReportsAreSkippedWithoutChangingTheirSource() throws Exception {
        String id = prepareReports();
        write(new File(logs, "controller.json"), report(UUID.randomUUID().toString(), "stale session"));
        File memory = new File(logs, "memory.json");
        byte[] oversized = new byte[Pd2AttemptReports.MAX_MEMORY_BYTES + 1];
        Arrays.fill(oversized, (byte)'x');
        Files.write(memory.toPath(), oversized);
        archive();
        Map<String, byte[]> entries = exported(files);
        assertEquals(2, entries.size());
        assertTrue(text(entries, "launch-attempt-").contains(id));
        assertEquals(oversized.length, memory.length());
        assertTrue(read(new File(logs, "controller.json")).contains("stale session"));
        write(new File(logs, "launch.json"), "{malformed");
        archive();
        assertEquals(3, exported(files).size()); // Existing complete generation plus the new runtime log.
        write(new File(logs, "launch.json"), report("../private-file", "invalid generation"));
        archive();
        assertEquals(4, exported(files).size());
    }

    @Test public void exactReportSizeLimitsAreAcceptedAndTheNextByteIsRejected() throws Exception {
        String id = UUID.randomUUID().toString();
        int[] limits = {Pd2AttemptReports.MAX_LAUNCH_BYTES, Pd2AttemptReports.MAX_CONTROLLER_BYTES,
                Pd2AttemptReports.MAX_MEMORY_BYTES};
        String[] names = {"launch", "controller", "memory"};
        write(new File(logs, "runtime.log"), "runtime fixture");
        for (int index = 0; index < names.length; index++) {
            byte[] bytes = report(id, "boundary").getBytes(StandardCharsets.UTF_8);
            byte[] padded = Arrays.copyOf(bytes, limits[index]);
            Arrays.fill(padded, bytes.length, padded.length, (byte)' ');
            Files.write(new File(logs, names[index] + ".json").toPath(), padded);
        }
        archive();
        Map<String, byte[]> entries = exported(files);
        assertEquals(4, entries.size());
        for (int index = 0; index < names.length; index++) {
            assertEquals(limits[index], bytes(entries, names[index] + "-attempt-").length);
            File source = new File(logs, names[index] + ".json");
            byte[] oversized = Arrays.copyOf(Files.readAllBytes(source.toPath()), limits[index] + 1);
            oversized[oversized.length - 1] = ' ';
            Files.write(source.toPath(), oversized);
        }
        archive();
        assertEquals(5, exported(files).size());
    }

    @Test public void retentionRemovesOnlyRecognizedCompanionsForPrunedRuntimeAttempts() throws Exception {
        File directory = Pd2SessionLog.getAttemptsDirectory(files);
        assertTrue(directory.mkdirs());
        File note = new File(directory, "private-note.json");
        write(note, "keep note");
        File unrelatedTemporary = new File(directory, "personal.json.tmp");
        write(unrelatedTemporary, "keep temporary");
        File unrelatedDirectory = new File(directory, "controller-attempt-0000000000000.json");
        assertTrue(unrelatedDirectory.mkdir());
        File interrupted = new File(directory, "memory-attempt-0000000000001.json.tmp");
        write(interrupted, "interrupted");
        File reservation = new File(directory, "memory-attempt-0000000000001.json");
        assertTrue(reservation.createNewFile());
        for (int index = 0; index < 7; index++) { prepareReports(); archive(); }
        Map<String, byte[]> entries = exported(files);
        assertEquals(Pd2SessionLog.MAX_ATTEMPTS * 4, entries.size());
        File[] reports = directory.listFiles(file -> file.isFile()
                && file.getName().matches("(?:launch|controller|memory)-attempt-[0-9]{13,19}\\.json"));
        assertNotNull(reports);
        assertEquals(12, reports.length);
        assertTrue(note.isFile()); assertEquals("keep note", read(note));
        assertTrue(unrelatedTemporary.isFile()); assertTrue(unrelatedDirectory.isDirectory());
        assertFalse(interrupted.exists()); assertFalse(reservation.exists());
    }

    @Test public void exportRechecksMatchingGenerationAndAllowlistAndKeepsZipOpen() throws Exception {
        prepareReports();
        File runtime = archive();
        File directory = runtime.getParentFile();
        String stamp = runtime.getName().substring("runtime-attempt-".length(), runtime.getName().length() - 4);
        write(new File(directory, "controller-attempt-" + stamp + ".json"),
                report(UUID.randomUUID().toString(), "stale archived controller"));
        write(new File(directory, "arbitrary.json"), "excluded private file");
        write(new File(directory, "launch-attempt-bad.json"), "excluded name");
        Map<String, byte[]> entries = exported(files);
        assertEquals(3, entries.size());
        assertFalse(entries.keySet().stream().anyMatch(name -> name.contains("controller")));
        assertTrue(entries.keySet().stream().allMatch(name -> name.startsWith("attempts/")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            Pd2AttemptReports.exportArchived(files, zip);
            zip.putNextEntry(new ZipEntry("still-open.txt"));
            zip.write(7); zip.closeEntry();
        }
        assertTrue(zipEntries(output.toByteArray()).containsKey("still-open.txt"));
    }

    @Test public void sourceArchiveAndDirectorySymlinksCannotExposeOutsideFiles() throws Exception {
        prepareReports();
        File privateFile = new File(files, "private.json");
        write(privateFile, report(UUID.randomUUID().toString(), "private data"));
        File controller = new File(logs, "controller.json");
        assertTrue(controller.delete());
        Files.createSymbolicLink(controller.toPath(), privateFile.toPath());
        File runtime = archive();
        assertEquals(3, exported(files).size());
        File directory = runtime.getParentFile();
        File linkedRuntime = new File(directory, "runtime-attempt-9999999999999.log");
        Files.createSymbolicLink(linkedRuntime.toPath(), privateFile.toPath());
        assertEquals(3, exported(files).size());
        String stamp = runtime.getName().substring("runtime-attempt-".length(), runtime.getName().length() - 4);
        File memory = new File(directory, "memory-attempt-" + stamp + ".json");
        assertTrue(memory.delete());
        Files.createSymbolicLink(memory.toPath(), privateFile.toPath());
        assertEquals(2, exported(files).size());
        File outside = Files.createTempDirectory("pd2-outside-attempts-").toFile();
        assertTrue(directory.renameTo(new File(logs, "previous-attempts")));
        Files.createSymbolicLink(directory.toPath(), outside.toPath());
        assertTrue(exported(files).isEmpty());
        assertTrue(read(privateFile).contains("private data"));
        assertEquals(0, outside.listFiles().length);
    }

    @Test public void absenceOfPreviousRuntimeDoesNotCreateAnUnrelatedReportArchive() throws Exception {
        prepareReports();
        assertTrue(new File(logs, "runtime.log").delete());
        Pd2AttemptReports.archivePrevious(files, null);
        assertTrue(exported(files).isEmpty());
        assertTrue(new File(logs, "controller.json").isFile());
    }

    private String prepareReports() throws Exception {
        String id = UUID.randomUUID().toString();
        write(new File(logs, "runtime.log"), "runtime fixture " + id);
        for (String name : new String[]{"launch", "controller", "memory"})
            write(new File(logs, name + ".json"), report(id, name + " fixture"));
        return id;
    }

    private File archive() throws Exception {
        File result = Pd2SessionLog.archivePrevious(files);
        Pd2AttemptReports.archivePrevious(files, result);
        return result;
    }

    private static String report(String id, String marker) throws Exception {
        return new JSONObject().put("launchId", id).put("fixture", marker).toString();
    }

    private static void write(File file, String content) throws Exception {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> exported(File files) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) { Pd2AttemptReports.exportArchived(files, zip); }
        return zipEntries(output.toByteArray());
    }

    private static Map<String, byte[]> zipEntries(byte[] bytes) throws Exception {
        Map<String, byte[]> result = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int read;
                while ((read = zip.read(buffer)) != -1) data.write(buffer, 0, read);
                result.put(entry.getName(), data.toByteArray());
            }
        }
        return result;
    }

    private static byte[] bytes(Map<String, byte[]> entries, String prefix) {
        return entries.entrySet().stream().filter(entry -> entry.getKey().startsWith("attempts/" + prefix))
                .findFirst().orElseThrow(AssertionError::new).getValue();
    }

    private static String text(Map<String, byte[]> entries, String prefix) {
        return new String(bytes(entries, prefix), StandardCharsets.UTF_8);
    }
}
