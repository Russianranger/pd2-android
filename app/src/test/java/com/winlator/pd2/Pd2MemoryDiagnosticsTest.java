package com.winlator.pd2;

import android.app.Application;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2MemoryDiagnosticsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private File launch(File root, String id) throws Exception {
        File file = new File(root, "pd2/logs/launch.json");
        assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
        Files.write(file.toPath(), new JSONObject().put("launchId", id).toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }
    private JSONObject read(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    private JSONObject sample(int index) {
        try { return new JSONObject().put("sequence", index); } catch (Exception error) { throw new AssertionError(error); }
    }

    @Test public void historyKeepsRecentSamplesOnDiskAndAdvancesControllerCheckpoints() throws Exception {
        File root = temporary.newFolder(); launch(root, "current");
        CountDownLatch first = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger(), controllers = new AtomicInteger();
        Pd2MemoryDiagnostics monitor = new Pd2MemoryDiagnostics(root, "current", () -> sample(sequence.incrementAndGet()),
                () -> { controllers.incrementAndGet(); first.countDown(); }, 86400000);
        try {
            assertTrue(first.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 35; i++) monitor.checkpoint();
            JSONObject report = read(Pd2MemoryDiagnostics.getFile(root));
            JSONArray history = report.getJSONArray("samples");
            assertEquals(24, history.length());
            assertEquals(13, history.getJSONObject(0).getInt("sequence"));
            assertEquals(36, history.getJSONObject(23).getInt("sequence"));
            assertTrue(history.getJSONObject(23).getLong("capturedAt") > 0);
            assertEquals(36, controllers.get());
            assertEquals("current", report.getString("launchId"));
            assertTrue(Pd2MemoryDiagnostics.getFile(root).length() < Pd2MemoryDiagnostics.MAX_REPORT_BYTES);
        } finally { monitor.close(); assertTrue(monitor.awaitTermination(5000)); }
    }

    @Test public void blockedOldCollectorCannotOverwriteAReplacementLaunchAndWorkerTerminates() throws Exception {
        File root = temporary.newFolder(); File launch = launch(root, "old");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Pd2MemoryDiagnostics monitor = new Pd2MemoryDiagnostics(root, "old", () -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new AssertionError(error); }
            return sample(1);
        }, () -> {}, 86400000);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            launch(root, "new");
            Pd2MemoryDiagnostics.persist(Pd2MemoryDiagnostics.getFile(root), launch,
                    new JSONObject().put("launchId", "new").put("marker", "replacement"));
            monitor.close(); monitor.close(); release.countDown();
            assertTrue(monitor.awaitTermination(5000));
            assertTrue(monitor.isTerminated());
            assertEquals("replacement", read(Pd2MemoryDiagnostics.getFile(root)).getString("marker"));
            assertFalse(new File(launch.getParentFile(), "memory.json.tmp").exists());
        } finally { release.countDown(); monitor.close(); }
    }

    @Test public void oversizedReportLeavesLastCompleteSnapshotIntact() throws Exception {
        File root = temporary.newFolder(); File launch = launch(root, "current"), report = Pd2MemoryDiagnostics.getFile(root);
        Pd2MemoryDiagnostics.persist(report, launch, new JSONObject().put("launchId", "current").put("marker", "complete"));
        Pd2MemoryDiagnostics.persist(report, launch, new JSONObject().put("launchId", "current")
                .put("overflow", "x".repeat(Pd2MemoryDiagnostics.MAX_REPORT_BYTES)));
        assertEquals("complete", read(report).getString("marker"));
    }

    @Test public void byteLimitDropsOlderSamplesInsteadOfFreezingTheMostRecentEvidence() throws Exception {
        File root = temporary.newFolder(); launch(root, "current"); CountDownLatch first = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        Pd2MemoryDiagnostics monitor = new Pd2MemoryDiagnostics(root, "current", () -> {
            JSONObject value = sample(sequence.incrementAndGet());
            try { value.put("largeProcessFixture", "x".repeat(50000)); } catch (Exception error) { throw new AssertionError(error); }
            return value;
        }, first::countDown, 86400000);
        try {
            assertTrue(first.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 35; i++) monitor.checkpoint();
            File file = Pd2MemoryDiagnostics.getFile(root); JSONObject report = read(file);
            JSONArray history = report.getJSONArray("samples");
            assertTrue(history.length() < 24);
            assertEquals(history.length(), report.getInt("retainedSamples"));
            assertTrue(report.getBoolean("byteLimitTrimmed"));
            assertEquals(36, history.getJSONObject(history.length() - 1).getInt("sequence"));
            assertTrue(file.length() <= Pd2MemoryDiagnostics.MAX_REPORT_BYTES);
        } finally { monitor.close(); assertTrue(monitor.awaitTermination(5000)); }
    }

    @Test public void trimAndActivityStateAreTimestampedWithoutInventingAnInitialTrim() throws Exception {
        File root = temporary.newFolder(); launch(root, "current"); CountDownLatch first = new CountDownLatch(1);
        Pd2MemoryDiagnostics monitor = new Pd2MemoryDiagnostics(root, "current", () -> sample(1), first::countDown, 86400000);
        try {
            assertTrue(first.await(5, TimeUnit.SECONDS));
            assertTrue(read(Pd2MemoryDiagnostics.getFile(root)).getJSONArray("samples").getJSONObject(0).isNull("lastTrimLevel"));
            monitor.setResumed(true); monitor.trimmed(15); monitor.checkpoint();
            JSONObject sample = read(Pd2MemoryDiagnostics.getFile(root)).getJSONArray("samples").getJSONObject(1);
            assertTrue(sample.getBoolean("activityResumed"));
            assertEquals(15, sample.getInt("lastTrimLevel"));
            assertTrue(sample.getLong("lastTrimAt") > 0);
        } finally { monitor.close(); assertTrue(monitor.awaitTermination(5000)); }
    }

    @Test public void collectorFailureDoesNotStopFollowingSamplesOrRetainAnExecutor() throws Exception {
        File root = temporary.newFolder(); launch(root, "current"); AtomicInteger calls = new AtomicInteger();
        CountDownLatch success = new CountDownLatch(1);
        Pd2MemoryDiagnostics monitor = new Pd2MemoryDiagnostics(root, "current", () -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("fixture unavailable");
            return sample(calls.get());
        }, success::countDown, 10);
        try { assertTrue(success.await(5, TimeUnit.SECONDS)); assertTrue(Pd2MemoryDiagnostics.getFile(root).isFile()); }
        finally { monitor.close(); assertTrue(monitor.awaitTermination(5000)); }
    }

    @Test public void supportExportRetainsTheMatchingMemoryReportAndSkipsStaleOrOversizedData() throws Exception {
        File root = temporary.newFolder(); File launch = launch(root, "current"), report = Pd2MemoryDiagnostics.getFile(root);
        JSONObject valid = new JSONObject().put("launchId", "current").put("padding", "x".repeat(70000));
        Pd2MemoryDiagnostics.persist(report, launch, valid);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            Pd2Activity.zipSessionDiagnostics(out, "memory.json", report, launch, Pd2MemoryDiagnostics.MAX_REPORT_BYTES);
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals("memory.json", in.getNextEntry().getName());
            assertEquals("current", new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getString("launchId"));
            assertNull(in.getNextEntry());
        }
        launch(root, "replacement"); bytes.reset();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            Pd2Activity.zipSessionDiagnostics(out, "memory.json", report, launch, Pd2MemoryDiagnostics.MAX_REPORT_BYTES);
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { assertNull(in.getNextEntry()); }
        launch(root, "current"); Files.write(report.toPath(), new byte[Pd2MemoryDiagnostics.MAX_REPORT_BYTES + 1]); bytes.reset();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            Pd2Activity.zipSessionDiagnostics(out, "memory.json", report, launch, Pd2MemoryDiagnostics.MAX_REPORT_BYTES);
        }
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { assertNull(in.getNextEntry()); }
    }
}
