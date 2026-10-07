package com.winlator.pd2;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.winlator.xenvironment.RootFS;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** One-launch, opt-in API return diagnostic. Filters are restored after verified Wine shutdown. */
public final class Pd2InputTraceRuntime {
    public static final String PREFERENCE = "pd2_input_trace";
    private static final Object LOCK = new Object();
    private static long appliedAt, restoredAt;
    private static int restoredFields, conflictsPreserved;
    private static String lastError = "";
    private static final String JOURNAL = "pd2/runtime/input-trace-journal.json";

    private Pd2InputTraceRuntime() { }

    public static boolean requested(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREFERENCE, false);
    }

    /** Context and hive identity come from the captured session, never an editable journal path. */
    public static boolean prepareAfterCleanup(Context context, Pd2WineSession.Result cleanup, File hive, String launchId) throws IOException {
        requireCleanup(cleanup, true);
        synchronized (LOCK) {
            try {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
                boolean enabled = preferences.getBoolean(PREFERENCE, false);
                if (!enabled && !hasJournal(context)) return false;
                Pd2InputTraceRegistry.requireOwner(launchId);
                verifyHive(context, hive);
                restore(context, hive, null);
                if (!enabled) return false;
                Pd2InputTraceRegistry.apply(hive, journal(context), launchId);
                // Consumed only after the owned filter was durably installed and read back.
                if (!preferences.edit().putBoolean(PREFERENCE, false).commit())
                    throw new IOException("Cannot consume the next-launch controller trace setting");
                appliedAt = System.currentTimeMillis();
                lastError = "";
                return true;
            } catch (IOException error) { lastError = error.getMessage(); throw error; }
        }
    }

    public static void restoreAfterCleanup(Context context, Pd2WineSession.Result cleanup, File expectedHive, String launchId) throws IOException {
        requireCleanup(cleanup, false);
        synchronized (LOCK) {
            try {
                if (!hasJournal(context)) return;
                Pd2InputTraceRegistry.requireOwner(launchId);
                verifyHive(context, expectedHive);
                restore(context, expectedHive, "afterStop".equals(cleanup.phase) ? launchId : null);
            } catch (IOException error) { lastError = error.getMessage(); throw error; }
        }
    }

    private static void restore(Context context, File hive, String expectedOwner) throws IOException {
        Pd2InputTraceRegistry.RestoreResult result = Pd2InputTraceRegistry.restore(hive, journal(context), expectedOwner);
        if (result.hadJournal) {
            restoredAt = System.currentTimeMillis();
            restoredFields = result.restoredFields;
            conflictsPreserved = result.conflictsPreserved;
        }
        lastError = "";
    }

    static void requireCleanup(Pd2WineSession.Result cleanup, boolean preparing) throws IOException {
        if (cleanup == null || !cleanup.passed || !("beforeLaunch".equals(cleanup.phase)
                || !preparing && "afterStop".equals(cleanup.phase)))
            throw new IOException("Controller API tracing requires completed cleanup of the captured Wine session");
    }

    private static File journal(Context context) throws IOException {
        return new File(context.getFilesDir().getCanonicalFile(), JOURNAL);
    }

    private static boolean hasJournal(Context context) throws IOException {
        File file = journal(context);
        return file.exists() || Files.isSymbolicLink(file.toPath());
    }

    private static void verifyHive(Context context, File hive) throws IOException {
        File root = RootFS.find(context).getRootDir().getCanonicalFile();
        File files = context.getFilesDir().getCanonicalFile();
        Pd2InputTraceRegistry.requirePath(hive, true);
        if (!root.getPath().startsWith(files.getPath() + File.separator)
                || !hive.getPath().startsWith(new File(root, "home").getPath() + File.separator)
                || !"user.reg".equals(hive.getName()) || !".wine".equals(hive.getParentFile().getName()))
            throw new IOException("Controller trace hive is outside the captured private Wine prefix");
    }

    public static JSONObject status(Context context) {
        synchronized (LOCK) {
            JSONObject status = new JSONObject();
            try {
                File journal = journal(context);
                boolean pending = journal.exists() || Files.isSymbolicLink(journal.toPath());
                status.put("revision", Pd2InputTraceRegistry.REVISION).put("requestedForNextLaunch", requested(context))
                        .put("pendingRestoration", pending).put("appliedAt", appliedAt)
                        .put("restoredAt", restoredAt).put("restoredFields", restoredFields)
                        .put("conflictsPreserved", conflictsPreserved).put("error", lastError)
                        .put("scope", "Selected Wine API scalar arguments and return codes; no controller state values")
                        .put("freshnessMeasured", false).put("retention", "Rotating runtime log; tracing lasts until Stop");
            } catch (IOException | JSONException | RuntimeException error) {
                try { status.put("error", error.getClass().getSimpleName()); }
                catch (JSONException ignored) { }
            }
            return status;
        }
    }
}
