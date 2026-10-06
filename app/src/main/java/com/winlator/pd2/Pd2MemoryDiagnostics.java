package com.winlator.pd2;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Process;
import androidx.preference.PreferenceManager;
import com.winlator.services.ForegroundService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Persist a small pre-exit memory history; Android kills cannot run a Java crash handler. */
public final class Pd2MemoryDiagnostics implements AutoCloseable {
    static final int MAX_SAMPLES = 24;
    static final int MAX_REPORT_BYTES = 512 * 1024;
    static final long INTERVAL_MS = 5000;
    private final File launchFile, destination;
    private final String launchId;
    private final Supplier<JSONObject> collector;
    private final Runnable controllerCheckpoint;
    private final long intervalMs;
    private final ArrayDeque<JSONObject> samples = new ArrayDeque<>();
    private final ScheduledThreadPoolExecutor worker;
    private final ScheduledFuture<?> periodic;
    private boolean closed;
    private volatile boolean resumed;
    private volatile int trimLevel = -1;
    private volatile long trimAt;

    public static Pd2MemoryDiagnostics start(Context context, String id, Runnable controllerCheckpoint) {
        Context app = context.getApplicationContext();
        return new Pd2MemoryDiagnostics(app.getFilesDir(), id, () -> collect(app), controllerCheckpoint, INTERVAL_MS);
    }

    Pd2MemoryDiagnostics(File filesDirectory, String id, Supplier<JSONObject> collector,
                         Runnable checkpoint, long intervalMs) {
        this.launchId = id;
        this.launchFile = new File(filesDirectory, "pd2/logs/launch.json");
        this.destination = getFile(filesDirectory);
        this.collector = collector;
        this.controllerCheckpoint = checkpoint;
        this.intervalMs = intervalMs;
        worker = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "PD2-memory-diagnostics");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        worker.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        periodic = worker.scheduleWithFixedDelay(this::checkpoint, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void setResumed(boolean value) { resumed = value; }
    public void trimmed(int level) { trimLevel = level; trimAt = System.currentTimeMillis(); }

    void checkpoint() {
        try {
            JSONObject sample = collector.get();
            sample.put("capturedAt", System.currentTimeMillis()).put("activityResumed", resumed)
                    .put("lastTrimLevel", trimLevel < 0 ? JSONObject.NULL : trimLevel)
                    .put("lastTrimAt", trimAt == 0 ? JSONObject.NULL : trimAt);
            synchronized (samples) {
                samples.addLast(sample);
                while (samples.size() > MAX_SAMPLES) samples.removeFirst();
                boolean byteTrimmed = false;
                JSONObject report = report(false);
                while (samples.size() > 1 && report.toString().getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) {
                    samples.removeFirst();
                    byteTrimmed = true;
                    report = report(true);
                }
                if (byteTrimmed) report.put("byteLimitTrimmed", true);
                persist(destination, launchFile, report);
            }
            controllerCheckpoint.run();
        } catch (IOException | JSONException | RuntimeException ignored) {
            // Diagnostics must neither interrupt gameplay nor grow an error log during memory pressure.
        }
    }

    private JSONObject report(boolean byteTrimmed) throws JSONException {
        return new JSONObject().put("launchId", launchId).put("sampleIntervalMs", intervalMs)
                .put("maxSamples", MAX_SAMPLES).put("maxReportBytes", MAX_REPORT_BYTES)
                .put("retainedSamples", samples.size()).put("byteLimitTrimmed", byteTrimmed)
                .put("scope", "Device memory, app heaps and visible same-UID processes; sampled, not final memory at death. RSS sums double-count shared pages; GPU/hidden processes may be absent.")
                .put("samples", new JSONArray(samples));
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        periodic.cancel(false);
        worker.execute(this::checkpoint);
        worker.shutdown();
    }

    boolean isTerminated() { return worker.isTerminated(); }
    boolean awaitTermination(long milliseconds) throws InterruptedException {
        return worker.awaitTermination(milliseconds, TimeUnit.MILLISECONDS);
    }

    public static File getFile(Context context) { return getFile(context.getFilesDir()); }
    static File getFile(File directory) { return new File(directory, "pd2/logs/memory.json"); }

    static void persist(File file, File launch, JSONObject report) throws IOException, JSONException {
        // Use the launch writer's monitor so a new launch cannot race this generation check/rename.
        synchronized (Pd2LaunchDiagnostics.class) {
            if (!launch.isFile() || launch.length() > 128 * 1024) return;
            JSONObject active = new JSONObject(new String(Files.readAllBytes(launch.toPath()), StandardCharsets.UTF_8));
            if (!report.getString("launchId").equals(active.optString("launchId"))) return;
            byte[] bytes = report.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_REPORT_BYTES) return;
            File temporary = new File(file.getParentFile(), "memory.json.tmp");
            try {
                try (FileOutputStream out = new FileOutputStream(temporary)) {
                    out.write(bytes);
                    out.getFD().sync();
                }
                if (!temporary.renameTo(file)) throw new IOException("Cannot publish memory snapshot");
            } finally { temporary.delete(); }
        }
    }

    private static JSONObject collect(Context app) {
        JSONObject sample = new JSONObject();
        try {
            Runtime java = Runtime.getRuntime();
            sample.put("appPid", Process.myPid()).put("appUid", Process.myUid())
                    .put("javaUsedBytes", java.totalMemory() - java.freeMemory())
                    .put("javaCommittedBytes", java.totalMemory()).put("javaMaxBytes", java.maxMemory())
                    .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
                    .put("backgroundProtectionEnabled", PreferenceManager.getDefaultSharedPreferences(app)
                            .getBoolean("enable_background_protection", false))
                    .put("foregroundSessionRequested", ForegroundService.isSessionActive());
            ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(memory);
                sample.put("deviceTotalBytes", memory.totalMem).put("deviceAvailableBytes", memory.availMem)
                        .put("deviceLowMemoryThresholdBytes", memory.threshold).put("deviceLowMemory", memory.lowMemory);
                ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
                ActivityManager.getMyMemoryState(process);
                sample.put("appImportance", process.importance).put("androidLastTrimLevel", process.lastTrimLevel);
            } else sample.put("deviceMemoryStatus", "unavailable");
            sample.put("processes", Pd2ProcessSnapshot.capture(new File("/proc"), Process.myUid(), Process.myPid()));
        } catch (JSONException | RuntimeException failure) {
            try { sample.put("sampleError", failure.getClass().getSimpleName()); } catch (JSONException ignored) { }
        }
        return sample;
    }
}
