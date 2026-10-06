package com.winlator.pd2;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Process;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2AndroidExitDiagnosticsTest {
    @Test public void lowMemoryPreservesExitedIdentityAndSampledMemoryWithoutInventingTotals() throws Exception {
        ApplicationExitInfo info = ApplicationExitInfoBuilder.newBuilder()
                .setPid(1234).setProcessName("com.pd2.thor:runtime")
                .setRealUid(10123).setPackageUid(10124).setImportance(100)
                .setReason(3).setStatus(0).setTimestamp(1791255749540L)
                .setDescription("memory pressure").setPss(129803L).setRss(271901L).build();
        JSONObject result = Pd2AndroidExitDiagnostics.describe(info);
        assertEquals(1234, result.getInt("pid"));
        assertEquals("com.pd2.thor:runtime", result.getString("processName"));
        assertEquals(10123, result.getInt("realUid"));
        assertEquals(10124, result.getInt("packageUid"));
        assertEquals(100, result.getInt("importance"));
        assertEquals(3, result.getInt("reason"));
        assertEquals("LOW_MEMORY", result.getString("reasonLabel"));
        assertEquals(0, result.getInt("status"));
        assertEquals(1791255749540L, result.getLong("timestamp"));
        assertEquals("memory pressure", result.getString("description"));
        assertEquals(129803L, result.getLong("pssKB"));
        assertEquals(271901L, result.getLong("rssKB"));
        assertEquals(Pd2AndroidExitDiagnostics.MEMORY_SCOPE, result.getString("memoryScope"));
        assertEquals(new HashSet<>(Arrays.asList("timestamp", "pid", "processName", "realUid", "packageUid",
                "importance", "reason", "reasonLabel", "status", "description", "pssKB", "rssKB", "memoryScope")), keys(result));
    }

    @Test public void signalStatusDoesNotBecomeLowMemoryOrHideCrashKinds() throws Exception {
        for (int[] pair : new int[][]{{2, 9}, {3, 0}, {4, 0}, {5, 11}, {0, 0}, {127, 9}}) {
            JSONObject result = Pd2AndroidExitDiagnostics.describe(ApplicationExitInfoBuilder.newBuilder()
                    .setReason(pair[0]).setStatus(pair[1]).build());
            assertEquals(pair[0], result.getInt("reason"));
            assertEquals(pair[1], result.getInt("status"));
            String expected = pair[0] == 2 ? "SIGNALED" : pair[0] == 3 ? "LOW_MEMORY" : pair[0] == 4 ? "CRASH"
                    : pair[0] == 5 ? "CRASH_NATIVE" : pair[0] == 0 ? "UNKNOWN" : "UNRECOGNIZED";
            assertEquals(expected, result.getString("reasonLabel"));
        }
    }

    @Test public void allSdk35ReasonConstantsHaveDistinctLabels() {
        String[] labels = {"UNKNOWN", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE", "ANR",
                "INITIALIZATION_FAILURE", "PERMISSION_CHANGE", "EXCESSIVE_RESOURCE_USAGE", "USER_REQUESTED",
                "USER_STOPPED", "DEPENDENCY_DIED", "OTHER", "FREEZER", "PACKAGE_STATE_CHANGE", "PACKAGE_UPDATED"};
        for (int reason = 0; reason < labels.length; reason++)
            assertEquals(labels[reason], Pd2AndroidExitDiagnostics.reasonLabel(reason));
        assertEquals("UNRECOGNIZED", Pd2AndroidExitDiagnostics.reasonLabel(-1));
        assertEquals("UNRECOGNIZED", Pd2AndroidExitDiagnostics.reasonLabel(17));
    }

    @Test public void nullDescriptionRemainsExplicitAndTextIsBoundedWithoutSplitSurrogate() throws Exception {
        JSONObject empty = Pd2AndroidExitDiagnostics.describe(ApplicationExitInfoBuilder.newBuilder()
                .setDescription(null).setProcessName(null).build());
        assertTrue(empty.has("description"));
        assertTrue(empty.isNull("description"));
        assertTrue(empty.isNull("processName"));
        String name = "x".repeat(127) + "\ud83d\udd25" + "\n".repeat(1000);
        JSONObject bounded = Pd2AndroidExitDiagnostics.describe(ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(name).setDescription("\r\n\t" + "x".repeat(1000)).build());
        assertEquals(127, bounded.getString("processName").length());
        assertEquals(512, bounded.getString("description").length());
        assertTrue(bounded.getString("description").startsWith("   "));
        assertFalse(bounded.getString("description").contains("\n"));
        assertFalse(bounded.getString("description").contains("\r"));
        assertFalse(bounded.getString("description").contains("\t"));
    }

    @Test public void supportScopeIdentifiesExporterSeparatelyAndLimitsHistoricalRecords() throws Exception {
        Application application = RuntimeEnvironment.getApplication();
        ActivityManager manager = (ActivityManager) application.getSystemService(Context.ACTIVITY_SERVICE);
        ShadowActivityManager history = shadowOf(manager);
        for (int index = 0; index < 8; index++) history.addApplicationExitInfo(ApplicationExitInfoBuilder.newBuilder()
                .setPid(4000 + index).setProcessName("prior-runtime-" + index).setReason(3).setPss(index).build());
        JSONObject result = new JSONObject();
        Pd2AndroidExitDiagnostics.appendTo(result, manager, application.getPackageName());
        assertEquals(5, result.getJSONArray("androidExits").length());
        assertEquals(Process.myPid(), result.getInt("currentAppPid"));
        assertEquals(Application.getProcessName() == null ? JSONObject.NULL : Application.getProcessName(),
                result.get("currentAppProcessName"));
        assertEquals(ActivityManager.isLowMemoryKillReportSupported(), result.getBoolean("lowMemoryKillReportSupported"));
        assertEquals(Pd2AndroidExitDiagnostics.MEMORY_SCOPE, result.getString("androidExitMemoryScope"));
        assertNotEquals(result.getInt("currentAppPid"), result.getJSONArray("androidExits").getJSONObject(0).getInt("pid"));
        assertFalse(result.has("pssKB"));
        assertFalse(result.has("rssKB"));
    }

    private static Set<String> keys(JSONObject json) {
        Set<String> keys = new HashSet<>();
        Iterator<String> iterator = json.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        return keys;
    }
}
