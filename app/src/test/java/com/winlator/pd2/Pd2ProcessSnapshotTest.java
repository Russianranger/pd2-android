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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/** Real bounded file reads against a fake proc tree; no device proc or native runtime is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2ProcessSnapshotTest {
    private static final int UID = 10452;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void capturesOnlyVerifiedSameUidAndReportsNonUniqueRssSum() throws Exception {
        File proc = temporary.newFolder("proc");
        process(proc, 20, UID, "Game.exe", 300, 400, 8, 500);
        process(proc, 10, UID, "com.pd2.thor", 120, 150, 12, 200);
        process(proc, 30, UID + 1, "other-app-private-name", 9000, 10000, 30, 100);
        JSONObject report = Pd2ProcessSnapshot.capture(proc, UID, 10);
        assertEquals(3, report.getInt("visiblePidCount"));
        assertEquals(2, report.getInt("sameUidObserved"));
        assertEquals(1, report.getInt("otherUidCount"));
        JSONArray entries = report.getJSONArray("processes");
        assertEquals(2, entries.length());
        assertEquals(10, entries.getJSONObject(0).getInt("pid"));
        assertEquals(UID, entries.getJSONObject(1).getInt("uid"));
        assertEquals(300, entries.getJSONObject(1).getLong("rssKB"));
        assertEquals(400, entries.getJSONObject(1).getLong("hwmKB"));
        assertEquals(8, entries.getJSONObject(1).getInt("threads"));
        assertEquals("S", entries.getJSONObject(1).getString("state"));
        assertEquals(420, report.getJSONObject("aggregate").getLong("rssSumKB"));
        assertTrue(report.getJSONObject("aggregate").getString("scope").contains("shared pages"));
        assertFalse(report.toString().contains("other-app-private-name"));
    }

    @Test public void noCmdlineOrArgumentsAreReadAndCommNameIsSanitizedBasename() throws Exception {
        File proc = temporary.newFolder("proc");
        File directory = process(proc, 20, UID, "/private/folder/Game.exe --SECRET_ARGUMENT", 10, 10, 1, 123);
        write(new File(directory, "cmdline"), "PRIVATE_CREDENTIAL\u0000--password=SECRET_PASSWORD");
        JSONObject report = Pd2ProcessSnapshot.capture(proc, UID, 20);
        JSONObject process = report.getJSONArray("processes").getJSONObject(0);
        assertEquals("Game.exe", process.getString("name"));
        String json = report.toString();
        for (String privateValue : new String[]{"PRIVATE_CREDENTIAL", "SECRET_ARGUMENT", "SECRET_PASSWORD", "/private/folder"})
            assertFalse(privateValue, json.contains(privateValue));
    }

    @Test public void starttimeDistinguishesReusedPidAndParsesParenthesesInStatComm() throws Exception {
        File proc = temporary.newFolder("proc");
        File directory = process(proc, 40, UID, "Game.exe", 10, 10, 1, 100);
        write(new File(directory, "stat"), stat(40, "odd ) comm (name)", 100));
        JSONObject first = Pd2ProcessSnapshot.capture(proc, UID, 40).getJSONArray("processes").getJSONObject(0);
        assertEquals(100, first.getLong("startTimeTicks"));
        assertEquals("40:100", first.getString("identity"));
        write(new File(directory, "stat"), stat(40, "replacement", 200));
        JSONObject second = Pd2ProcessSnapshot.capture(proc, UID, 40).getJSONArray("processes").getJSONObject(0);
        assertEquals(200, second.getLong("startTimeTicks"));
        assertNotEquals(first.getString("identity"), second.getString("identity"));
    }

    @Test public void missingAndMalformedMemoryFieldsStayUnknownRatherThanZero() throws Exception {
        File proc = temporary.newFolder("proc");
        File directory = process(proc, 50, UID, "Wine", 10, 10, 1, 100);
        write(new File(directory, "status"), "Name:\tWine\nPid:\t50\nUid:\t" + UID
                + "\nVmRSS:\tbroken kB\nVmHWM:\t123 bytes\nThreads:\t-1\n");
        write(new File(directory, "stat"), "50 (truncated) S 1");
        write(new File(directory, "oom_score_adj"), "2000\n");
        JSONObject report = Pd2ProcessSnapshot.capture(proc, UID, 50);
        JSONObject process = report.getJSONArray("processes").getJSONObject(0);
        for (String key : new String[]{"rssKB", "hwmKB", "threads", "ppid", "state", "oomScoreAdj", "startTimeTicks", "identity"})
            assertTrue(key, process.isNull(key));
        assertTrue(report.getJSONObject("aggregate").isNull("rssSumKB"));
        assertEquals(0, report.getJSONObject("aggregate").getInt("rssKnownEntries"));
        assertEquals(1, report.getJSONObject("aggregate").getInt("rssUnknownEntries"));
        assertFalse(report.getJSONObject("aggregate").getBoolean("complete"));
    }

    @Test public void missingUidCannotBeAssumedToBelongToTheApp() throws Exception {
        File proc = temporary.newFolder("proc");
        File directory = new File(proc, "60");
        assertTrue(directory.mkdir());
        write(new File(directory, "status"), "Name:\tunknown-owner\nVmRSS:\t9000 kB\n");
        JSONObject report = Pd2ProcessSnapshot.capture(proc, UID, 60);
        assertEquals(0, report.getJSONArray("processes").length());
        assertEquals(1, report.getInt("unverifiedUidCount"));
        assertTrue(report.getBoolean("partial"));
        assertTrue(report.getJSONObject("aggregate").isNull("rssSumKB"));
    }

    @Test public void unavailableRootAndUnreadableStatusAreReportedExplicitly() throws Exception {
        JSONObject absent = Pd2ProcessSnapshot.capture(new File(temporary.getRoot(), "no-proc"), UID, 70);
        assertFalse(absent.getBoolean("directoryListingAvailable"));
        assertTrue(absent.isNull("visiblePidCount"));
        assertTrue(absent.getJSONObject("aggregate").isNull("rssSumKB"));
        assertEquals(1, absent.getInt("statusUnavailableCount"));
        File proc = temporary.newFolder("proc");
        File directory = new File(proc, "70");
        assertTrue(directory.mkdir());
        // Directory reads fail reliably even when tests run as root and chmod would not deny access.
        assertTrue(new File(directory, "status").mkdir());
        JSONObject unreadable = Pd2ProcessSnapshot.capture(proc, UID, 70);
        assertEquals(1, unreadable.getInt("statusUnavailableCount"));
        assertEquals(0, unreadable.getJSONArray("processes").length());
        assertTrue(unreadable.getBoolean("partial"));
    }

    @Test public void statusAndProcessDirectoryLinksAreNotFollowed() throws Exception {
        File proc = temporary.newFolder("proc");
        File outside = temporary.newFolder("outside");
        write(new File(outside, "status"), status(80, UID, "do-not-read-linked-source", 10, 10, 1));
        Files.createSymbolicLink(new File(proc, "80").toPath(), outside.toPath());
        File second = new File(proc, "81");
        assertTrue(second.mkdir());
        Files.createSymbolicLink(new File(second, "status").toPath(), new File(outside, "status").toPath());
        JSONObject report = Pd2ProcessSnapshot.capture(proc, UID, 80);
        assertEquals(2, report.getInt("statusUnavailableCount"));
        assertFalse(report.toString().contains("do-not-read-linked-source"));
    }

    @Test public void capturedEntriesAreBoundedAndAppPidIsAlwaysPrioritized() throws Exception {
        File proc = temporary.newFolder("proc");
        for (int pid = 1; pid <= 70; pid++) process(proc, pid, UID, "Wine", 10, 10, 1, pid * 100L);
        process(proc, 1000, UID, "com.pd2.thor", 20, 20, 1, 100000);
        JSONObject report = Pd2ProcessSnapshot.capture(proc, UID, 1000);
        assertEquals(71, report.getInt("visiblePidCount"));
        assertEquals(71, report.getInt("sameUidObserved"));
        assertEquals(64, report.getJSONArray("processes").length());
        assertEquals(1000, report.getJSONArray("processes").getJSONObject(0).getInt("pid"));
        assertEquals(650, report.getJSONObject("aggregate").getLong("rssSumKB"));
        assertTrue(report.getBoolean("entryLimitReached"));
        assertFalse(report.getBoolean("scanLimitReached"));
        assertFalse(report.getJSONObject("aggregate").getBoolean("complete"));
    }

    @Test public void statusScanLimitBoundsWorkEvenWhenOtherUidsDominateTheListing() throws Exception {
        File proc = temporary.newFolder("proc");
        for (int pid = 1; pid <= Pd2ProcessSnapshot.MAX_STATUS_READS + 20; pid++) {
            File directory = new File(proc, Integer.toString(pid));
            assertTrue(directory.mkdir());
            write(new File(directory, "status"), status(pid, UID + 1, "other-owner", 10, 10, 1));
        }
        process(proc, 1000, UID, "app", 20, 20, 1, 100000);
        JSONObject report = Pd2ProcessSnapshot.capture(proc, UID, 1000);
        assertEquals(Pd2ProcessSnapshot.MAX_STATUS_READS + 21, report.getInt("visiblePidCount"));
        assertEquals(Pd2ProcessSnapshot.MAX_STATUS_READS, report.getInt("statusReads"));
        assertEquals(1, report.getJSONArray("processes").length());
        assertEquals(1000, report.getJSONArray("processes").getJSONObject(0).getInt("pid"));
        assertTrue(report.getBoolean("scanLimitReached"));
        assertTrue(report.getBoolean("partial"));
    }

    @Test public void eachSourceIsByteBoundedAndPartialTrailingFieldsAreIgnored() throws Exception {
        File proc = temporary.newFolder("proc");
        File directory = process(proc, 90, UID, "Wine", 10, 10, 1, 100);
        write(new File(directory, "status"), "Name:\tWine\nPid:\t90\nUid:\t" + UID
                + "\nVmRSS:\t10 kB\nVmHWM:\t" + "1".repeat(Pd2ProcessSnapshot.MAX_FILE_BYTES));
        write(new File(directory, "stat"), "90 (" + "x".repeat(Pd2ProcessSnapshot.MAX_FILE_BYTES) + ") S 1");
        write(new File(directory, "oom_score_adj"), "1".repeat(Pd2ProcessSnapshot.MAX_FILE_BYTES));
        JSONObject report = Pd2ProcessSnapshot.capture(proc, UID, 90);
        JSONObject process = report.getJSONArray("processes").getJSONObject(0);
        assertEquals("read-limit", process.getString("statusRead"));
        assertEquals("read-limit", process.getString("statRead"));
        assertEquals("read-limit", process.getString("oomScoreAdjRead"));
        assertEquals(10, process.getLong("rssKB"));
        assertTrue(process.isNull("hwmKB"));
        assertTrue(process.isNull("startTimeTicks"));
        assertTrue(process.isNull("oomScoreAdj"));
        assertEquals(1, report.getInt("statusReadLimitCount"));
        assertEquals(2, report.getInt("auxiliaryReadLimitCount"));
        assertTrue(report.getBoolean("partial"));
        assertTrue(report.toString().length() < 4000);
    }

    @Test public void mismatchedStatusPidIsExcludedAndWrongStatPidCannotSupplyIdentity() throws Exception {
        File proc = temporary.newFolder("proc");
        File directory = process(proc, 100, UID, "Wine", 10, 10, 1, 100);
        write(new File(directory, "status"), status(101, UID, "changed-process", 10, 10, 1));
        JSONObject mismatch = Pd2ProcessSnapshot.capture(proc, UID, 100);
        assertEquals(0, mismatch.getJSONArray("processes").length());
        assertEquals(1, mismatch.getInt("identityMismatchCount"));
        write(new File(directory, "status"), status(100, UID, "Wine", 10, 10, 1));
        write(new File(directory, "stat"), stat(101, "another-pid", 100));
        JSONObject process = Pd2ProcessSnapshot.capture(proc, UID, 100).getJSONArray("processes").getJSONObject(0);
        assertTrue(process.isNull("identity"));
        assertFalse(process.getBoolean("statParsed"));
    }

    @Test public void validZeroRssRemainsKnownAndAggregateOverflowDoesNotBecomeZero() throws Exception {
        File proc = temporary.newFolder("proc");
        process(proc, 110, UID, "zero-rss", 0, 0, 1, 100);
        JSONObject zero = Pd2ProcessSnapshot.capture(proc, UID, 110);
        assertEquals(0, zero.getJSONArray("processes").getJSONObject(0).getLong("rssKB"));
        assertEquals(1, zero.getJSONObject("aggregate").getInt("rssKnownEntries"));
        assertFalse(zero.getJSONObject("aggregate").isNull("rssSumKB"));
        process(proc, 111, UID, "big-rss", Long.MAX_VALUE, Long.MAX_VALUE, 1, 200);
        process(proc, 112, UID, "more-rss", 1, 1, 1, 300);
        JSONObject overflow = Pd2ProcessSnapshot.capture(proc, UID, 110).getJSONObject("aggregate");
        assertTrue(overflow.getBoolean("overflow"));
        assertTrue(overflow.isNull("rssSumKB"));
        assertFalse(overflow.getBoolean("complete"));
    }

    @Test public void cpuFieldsUseOwnUserAndSystemTicksWithTrickyCommAndPerIdentityIntervals() throws Exception {
        File proc = temporary.newFolder("proc");
        File directory = process(proc, 120, UID, "Game.exe", 10, 10, 3, 100);
        write(new File(directory, "stat"), cpuStat(120, "odd ) name ( with spaces)", 100, "11", "13"));
        Pd2ProcessSnapshot.CpuTracker cpu = new Pd2ProcessSnapshot.CpuTracker();
        JSONObject firstReport = cpuCapture(proc, 120, cpu, 100, 1_000_000_000L);
        JSONObject first = firstReport.getJSONArray("processes").getJSONObject(0);
        assertEquals(11, first.getLong("cpuUserTicks"));
        assertEquals(13, first.getLong("cpuSystemTicks"));
        assertEquals(24, first.getLong("cpuTotalTicks"));
        assertEquals(100, firstReport.getLong("clockTicksPerSecond"));
        assertTrue(first.isNull("cpuDeltaTotalTicks"));
        assertTrue(first.isNull("cpuPercentOfOneCore"));

        write(new File(directory, "stat"), cpuStat(120, "new comm text", 100, "611", "413"));
        JSONObject next = cpuCapture(proc, 120, cpu, 100, 6_000_000_000L)
                .getJSONArray("processes").getJSONObject(0);
        assertEquals(600, next.getLong("cpuDeltaUserTicks"));
        assertEquals(400, next.getLong("cpuDeltaSystemTicks"));
        assertEquals(1000, next.getLong("cpuDeltaTotalTicks"));
        assertEquals(5_000_000_000L, next.getLong("cpuDeltaElapsedNanos"));
        // Multithreaded process CPU is expressed per core, never clamped to 100%.
        assertEquals(200d, next.getDouble("cpuPercentOfOneCore"), 0.00001d);
        assertTrue(next.toString().length() < 1800);
    }

    @Test public void cpuPidReuseAndMissingIdentityNeverJoinDifferentLifetimes() throws Exception {
        File proc = temporary.newFolder("proc");
        File directory = process(proc, 121, UID, "Game.exe", 10, 10, 1, 100);
        Pd2ProcessSnapshot.CpuTracker cpu = new Pd2ProcessSnapshot.CpuTracker();
        cpuCapture(proc, 121, cpu, 100, 1_000_000_000L);
        write(new File(directory, "stat"), cpuStat(121, "replacement", 200, "1000", "1000"));
        assertNoCpuDelta(cpuCapture(proc, 121, cpu, 100, 2_000_000_000L));
        write(new File(directory, "stat"), cpuStat(121, "replacement", 200, "1010", "1010"));
        assertEquals(20, cpuCapture(proc, 121, cpu, 100, 3_000_000_000L)
                .getJSONArray("processes").getJSONObject(0).getLong("cpuDeltaTotalTicks"));
        write(new File(directory, "stat"), cpuStat(999, "wrong pid", 200, "1020", "1020"));
        JSONObject wrong = cpuCapture(proc, 121, cpu, 100, 4_000_000_000L);
        assertNoCpuDelta(wrong);
        assertTrue(wrong.getJSONArray("processes").getJSONObject(0).isNull("cpuUserTicks"));
        write(new File(directory, "stat"), cpuStat(121, "replacement", 200, "1030", "1030"));
        assertNoCpuDelta(cpuCapture(proc, 121, cpu, 100, 5_000_000_000L));
        assertTrue(new File(directory, "stat").delete());
        assertNoCpuDelta(cpuCapture(proc, 121, cpu, 100, 6_000_000_000L));
        write(new File(directory, "stat"), cpuStat(121, "replacement", 200, "1040", "1040"));
        assertNoCpuDelta(cpuCapture(proc, 121, cpu, 100, 7_000_000_000L));
    }

    @Test public void cpuCounterResetNegativeMalformedAndOverflowAreUnknownNotLoadSpikes() throws Exception {
        File proc = temporary.newFolder("proc");
        File directory = process(proc, 122, UID, "Wine", 10, 10, 1, 100);
        Pd2ProcessSnapshot.CpuTracker cpu = new Pd2ProcessSnapshot.CpuTracker();
        write(new File(directory, "stat"), cpuStat(122, "Wine", 100, "100", "100"));
        cpuCapture(proc, 122, cpu, 100, 1_000_000_000L);
        // The total grew but user time decreased, so this is not a valid delta.
        write(new File(directory, "stat"), cpuStat(122, "Wine", 100, "90", "120"));
        assertNoCpuDelta(cpuCapture(proc, 122, cpu, 100, 2_000_000_000L));
        for (String user : new String[]{"-1", "broken", "9223372036854775808", Long.toString(Long.MAX_VALUE)}) {
            write(new File(directory, "stat"), cpuStat(122, "Wine", 100, user, "120"));
            JSONObject report = cpuCapture(proc, 122, cpu, 100, 3_000_000_000L);
            assertNoCpuDelta(report);
            assertTrue(report.getJSONArray("processes").getJSONObject(0).isNull("cpuTotalTicks"));
        }
        write(new File(directory, "stat"), cpuStat(122, "Wine", 100, "100", "130"));
        assertNoCpuDelta(cpuCapture(proc, 122, cpu, 100, 4_000_000_000L));
        write(new File(directory, "stat"), cpuStat(122, "Wine", 100, "101", "131"));
        assertEquals(2, cpuCapture(proc, 122, cpu, 100, 5_000_000_000L)
                .getJSONArray("processes").getJSONObject(0).getLong("cpuDeltaTotalTicks"));
    }

    @Test public void cpuUnknownOrChangedTickRateKeepsRawTicksWithoutInventingPercent() throws Exception {
        File proc = temporary.newFolder("proc");
        File directory = process(proc, 123, UID, "Wine", 10, 10, 1, 100);
        Pd2ProcessSnapshot.CpuTracker cpu = new Pd2ProcessSnapshot.CpuTracker();
        cpuCapture(proc, 123, cpu, -1, 1_000_000_000L);
        write(new File(directory, "stat"), cpuStat(123, "Wine", 100, "50", "50"));
        JSONObject unknown = cpuCapture(proc, 123, cpu, 0, 2_000_000_000L);
        assertTrue(unknown.isNull("clockTicksPerSecond"));
        JSONObject process = unknown.getJSONArray("processes").getJSONObject(0);
        assertEquals(100, process.getLong("cpuDeltaTotalTicks"));
        assertTrue(process.isNull("cpuPercentOfOneCore"));
        write(new File(directory, "stat"), cpuStat(123, "Wine", 100, "100", "100"));
        JSONObject firstKnown = cpuCapture(proc, 123, cpu, 100, 3_000_000_000L);
        assertTrue(firstKnown.getJSONArray("processes").getJSONObject(0).isNull("cpuPercentOfOneCore"));
        write(new File(directory, "stat"), cpuStat(123, "Wine", 100, "150", "150"));
        assertEquals(100d, cpuCapture(proc, 123, cpu, 100, 4_000_000_000L)
                .getJSONArray("processes").getJSONObject(0).getDouble("cpuPercentOfOneCore"), 0.00001d);
        write(new File(directory, "stat"), cpuStat(123, "Wine", 100, "200", "200"));
        assertTrue(cpuCapture(proc, 123, cpu, 1000, 5_000_000_000L)
                .getJSONArray("processes").getJSONObject(0).isNull("cpuPercentOfOneCore"));
    }

    @Test public void cpuNonMonotonicTimeAndMissingProcessesResetTheBaseline() throws Exception {
        File proc = temporary.newFolder("proc");
        File directory = process(proc, 124, UID, "Wine", 10, 10, 1, 100);
        Pd2ProcessSnapshot.CpuTracker cpu = new Pd2ProcessSnapshot.CpuTracker();
        cpuCapture(proc, 124, cpu, 100, 5_000_000_000L);
        write(new File(directory, "stat"), cpuStat(124, "Wine", 100, "10", "10"));
        assertNoCpuDelta(cpuCapture(proc, 124, cpu, 100, 5_000_000_000L));
        assertNoCpuDelta(cpuCapture(proc, 124, cpu, 100, 4_000_000_000L));
        assertNoCpuDelta(cpuCapture(proc, 124, cpu, 100, 0));
        assertNoCpuDelta(cpuCapture(proc, 124, cpu, 100, 6_000_000_000L));
        write(new File(directory, "status"), status(124, UID + 1, "other-owner", 10, 10, 1));
        assertEquals(0, cpuCapture(proc, 124, cpu, 100, 7_000_000_000L).getJSONArray("processes").length());
        write(new File(directory, "status"), status(124, UID, "Wine", 10, 10, 1));
        assertNoCpuDelta(cpuCapture(proc, 124, cpu, 100, 8_000_000_000L));
    }

    @Test public void cpuTrackerNeverSharesBaselinesAcrossUidOrAppSessionIdentity() throws Exception {
        File proc = temporary.newFolder("proc");
        File directory = process(proc, 125, UID, "Wine", 10, 10, 1, 100);
        Pd2ProcessSnapshot.CpuTracker cpu = new Pd2ProcessSnapshot.CpuTracker();
        cpuCapture(proc, 125, cpu, 100, 1_000_000_000L);
        write(new File(directory, "status"), status(125, UID + 1, "other-owner", 10, 10, 1));
        write(new File(directory, "stat"), cpuStat(125, "other-owner", 100, "100", "100"));
        JSONObject otherUid = Pd2ProcessSnapshot.capture(proc, UID + 1, 125, cpu, 100, () -> 2_000_000_000L);
        assertNoCpuDelta(otherUid);
        assertNoCpuDelta(Pd2ProcessSnapshot.capture(proc, UID + 1, 999, cpu, 100, () -> 3_000_000_000L));
    }

    private static JSONObject cpuCapture(File proc, int pid, Pd2ProcessSnapshot.CpuTracker cpu, long clock, long nanos) {
        return Pd2ProcessSnapshot.capture(proc, UID, pid, cpu, clock, () -> nanos);
    }

    private static void assertNoCpuDelta(JSONObject report) throws Exception {
        JSONObject process = report.getJSONArray("processes").getJSONObject(0);
        assertTrue(process.isNull("cpuDeltaTotalTicks"));
        assertTrue(process.isNull("cpuPercentOfOneCore"));
    }

    private static File process(File root, int pid, int uid, String name, long rss, long hwm, int threads, long start) throws Exception {
        File directory = new File(root, Integer.toString(pid));
        assertTrue(directory.mkdir());
        write(new File(directory, "status"), status(pid, uid, name, rss, hwm, threads));
        write(new File(directory, "stat"), stat(pid, name, start));
        write(new File(directory, "oom_score_adj"), "-100\n");
        return directory;
    }

    private static String status(int pid, int uid, String name, long rss, long hwm, int threads) {
        return "Name:\t" + name + "\nState:\tS (sleeping)\nPid:\t" + pid + "\nPPid:\t1\nUid:\t"
                + uid + "\t" + uid + "\t" + uid + "\t" + uid + "\nVmRSS:\t" + rss
                + " kB\nVmHWM:\t" + hwm + " kB\nThreads:\t" + threads + "\n";
    }

    private static String stat(int pid, String name, long start) {
        return cpuStat(pid, name, start, "0", "0");
    }

    private static String cpuStat(int pid, String name, long start, String user, String system) {
        StringBuilder fields = new StringBuilder(pid + " (" + name + ") S");
        for (int field = 4; field < 22; field++) {
            // Large child CPU values must never be added to the process's own time.
            fields.append(' ').append(field == 14 ? user : field == 15 ? system
                    : field == 16 || field == 17 ? "500000" : "0");
        }
        return fields.append(' ').append(start).append(" 0 0\n").toString();
    }

    private static void write(File file, String content) throws Exception {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
