package com.winlator.pd2;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.os.Process;

import androidx.annotation.RequiresApi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded Android exit identity and sampled process memory; never reads exit traces. */
@RequiresApi(30)
public final class Pd2AndroidExitDiagnostics {
    static final int MAX_EXITS = 5;
    static final int MAX_PROCESS_NAME_CHARS = 128;
    static final int MAX_DESCRIPTION_CHARS = 512;
    static final String MEMORY_SCOPE = "PSS/RSS are the last sampled memory of this exited process, "
            + "not memory at death, whole-device memory or total Wine/guest memory.";

    private Pd2AndroidExitDiagnostics() {}

    /** Caller must retain its API 30 guard. Current identity belongs to the exporting process. */
    public static void appendTo(JSONObject report, ActivityManager manager, String packageName)
            throws JSONException {
        JSONArray exits = new JSONArray();
        for (ApplicationExitInfo info : manager.getHistoricalProcessExitReasons(packageName, 0, MAX_EXITS)) {
            if (exits.length() == MAX_EXITS) break;
            exits.put(describe(info));
        }
        report.put("androidExits", exits)
                .put("currentAppPid", Process.myPid())
                .put("currentAppProcessName", nullableText(Application.getProcessName(), MAX_PROCESS_NAME_CHARS))
                .put("lowMemoryKillReportSupported", ActivityManager.isLowMemoryKillReportSupported())
                .put("androidExitMemoryScope", MEMORY_SCOPE);
    }

    public static JSONObject describe(ApplicationExitInfo info) throws JSONException {
        return new JSONObject()
                .put("timestamp", info.getTimestamp())
                .put("pid", info.getPid())
                .put("processName", nullableText(info.getProcessName(), MAX_PROCESS_NAME_CHARS))
                .put("realUid", info.getRealUid())
                .put("packageUid", info.getPackageUid())
                .put("importance", info.getImportance())
                .put("reason", info.getReason())
                .put("reasonLabel", reasonLabel(info.getReason()))
                .put("status", info.getStatus())
                .put("description", nullableText(info.getDescription(), MAX_DESCRIPTION_CHARS))
                .put("pssKB", info.getPss())
                .put("rssKB", info.getRss())
                .put("memoryScope", MEMORY_SCOPE);
    }

    /** Unknown future values retain their numeric reason and are never guessed from status. */
    static String reasonLabel(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_UNKNOWN: return "UNKNOWN";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            case ApplicationExitInfo.REASON_FREEZER: return "FREEZER";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "PACKAGE_STATE_CHANGE";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "PACKAGE_UPDATED";
            default: return "UNRECOGNIZED";
        }
    }

    private static Object nullableText(String value, int limit) {
        if (value == null) return JSONObject.NULL;
        String normalized = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        int end = Math.min(normalized.length(), limit);
        if (end < normalized.length() && end > 0 && Character.isHighSurrogate(normalized.charAt(end - 1))) end--;
        return normalized.substring(0, end);
    }
}
