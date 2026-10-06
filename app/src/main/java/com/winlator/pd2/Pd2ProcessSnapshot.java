package com.winlator.pd2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Bounded, read-only visible same-UID process diagnostics; never reads cmdline or sends signals. */
public final class Pd2ProcessSnapshot {
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_STATUS_READS = 512;
    public static final int MAX_FILE_BYTES = 8192;
    private static final int MAX_NAME_CHARS = 48;

    private Pd2ProcessSnapshot() {}

    /** RSS is a sequential per-process snapshot, not a count of unique physical memory. */
    public static JSONObject capture(File procRoot, int uid, int appPid) {
        try { return captureJson(procRoot, uid, appPid); }
        catch (JSONException error) { throw new IllegalStateException("Cannot build process snapshot", error); }
    }

    private static JSONObject captureJson(File procRoot, int uid, int appPid) throws JSONException {
        String[] names = null;
        try { if (procRoot != null) names = procRoot.list(); }
        catch (SecurityException ignored) { }
        int visiblePidCount = 0;
        boolean appListed = false;
        TreeSet<Integer> visible = new TreeSet<>();
        int otherBudget = MAX_STATUS_READS - (appPid > 0 ? 1 : 0);
        if (names != null) for (String name : names) {
            Integer pid = positivePid(name);
            if (pid == null) continue;
            visiblePidCount++;
            if (pid == appPid) { appListed = true; continue; }
            visible.add(pid);
            if (visible.size() > otherBudget) visible.pollLast();
        }
        List<Integer> candidates = new ArrayList<>();
        if (appPid > 0) candidates.add(appPid);
        candidates.addAll(visible);
        JSONArray processes = new JSONArray();
        int statusReads = 0, statusUnavailable = 0, statusAtLimit = 0;
        int otherUid = 0, unverifiedUid = 0, sameUidObserved = 0, identityMismatch = 0;
        int rssKnown = 0, rssUnknown = 0, auxiliaryUnavailable = 0, auxiliaryAtLimit = 0;
        long rssSum = 0;
        boolean rssOverflow = false;
        byte[] readBuffer = new byte[MAX_FILE_BYTES];
        for (int pid : candidates) {
            statusReads++;
            File directory = procRoot == null ? null : new File(procRoot, Integer.toString(pid));
            Read status = readStatus(directory, readBuffer);
            if (!status.available) { statusUnavailable++; continue; }
            if (status.atLimit) statusAtLimit++;
            Integer realUid = integer(firstToken(field(status.text, "Uid")));
            if (realUid == null || realUid < 0) { unverifiedUid++; continue; }
            if (realUid != uid) { otherUid++; continue; }
            Integer statusPid = integer(field(status.text, "Pid"));
            if (statusPid != null && statusPid != pid) { identityMismatch++; continue; }
            sameUidObserved++;
            if (processes.length() == MAX_ENTRIES) continue;
            Read stat = read(new File(directory, "stat"), readBuffer);
            Read oom = read(new File(directory, "oom_score_adj"), readBuffer);
            if (!stat.available) auxiliaryUnavailable++;
            if (!oom.available) auxiliaryUnavailable++;
            if (stat.atLimit) auxiliaryAtLimit++;
            if (oom.atLimit) auxiliaryAtLimit++;
            Long startTime = startTime(stat.text, pid);
            Long rss = kilobytes(field(status.text, "VmRSS"));
            Long hwm = kilobytes(field(status.text, "VmHWM"));
            Integer ppid = nonnegativeInteger(field(status.text, "PPid"));
            Integer threads = nonnegativeInteger(field(status.text, "Threads"));
            Integer oomScoreAdj = integer(oom.text == null ? null : oom.text.trim());
            if (oomScoreAdj != null && (oomScoreAdj < -1000 || oomScoreAdj > 1000)) oomScoreAdj = null;
            if (rss == null) rssUnknown++;
            else {
                rssKnown++;
                if (Long.MAX_VALUE - rssSum < rss) rssOverflow = true;
                else rssSum += rss;
            }
            processes.put(new JSONObject().put("pid", pid).put("ppid", unknown(ppid)).put("uid", realUid)
                    .put("name", unknown(commName(field(status.text, "Name"))))
                    .put("state", unknown(state(field(status.text, "State"))))
                    .put("rssKB", unknown(rss)).put("hwmKB", unknown(hwm)).put("threads", unknown(threads))
                    .put("oomScoreAdj", unknown(oomScoreAdj)).put("startTimeTicks", unknown(startTime))
                    .put("identity", startTime == null ? JSONObject.NULL : pid + ":" + startTime)
                    .put("statusRead", status.kind()).put("statRead", stat.kind()).put("oomScoreAdjRead", oom.kind())
                    .put("statParsed", startTime != null));
        }
        int scannedListed = statusReads - (appPid > 0 && !appListed ? 1 : 0);
        boolean scanLimited = names != null && visiblePidCount > scannedListed;
        boolean entryLimited = sameUidObserved > MAX_ENTRIES;
        boolean partial = names == null || scanLimited || entryLimited || statusUnavailable > 0
                || unverifiedUid > 0 || identityMismatch > 0 || statusAtLimit > 0;
        return new JSONObject().put("uid", uid).put("appPid", appPid).put("appPidPrioritized", appPid > 0)
                .put("directoryListingAvailable", names != null)
                .put("visiblePidCount", names == null ? JSONObject.NULL : visiblePidCount)
                .put("statusReads", statusReads).put("statusUnavailableCount", statusUnavailable)
                .put("statusReadLimitCount", statusAtLimit).put("otherUidCount", otherUid)
                .put("unverifiedUidCount", unverifiedUid).put("identityMismatchCount", identityMismatch)
                .put("sameUidObserved", sameUidObserved).put("entriesCaptured", processes.length())
                .put("entryLimit", MAX_ENTRIES).put("statusReadLimit", MAX_STATUS_READS).put("fileByteLimit", MAX_FILE_BYTES)
                .put("entryLimitReached", entryLimited).put("scanLimitReached", scanLimited).put("partial", partial)
                .put("auxiliaryUnavailableCount", auxiliaryUnavailable).put("auxiliaryReadLimitCount", auxiliaryAtLimit)
                .put("processes", processes).put("aggregate", new JSONObject()
                        .put("rssSumKB", rssKnown == 0 || rssOverflow ? JSONObject.NULL : rssSum)
                        .put("rssKnownEntries", rssKnown).put("rssUnknownEntries", rssUnknown).put("overflow", rssOverflow)
                        .put("complete", !partial && rssUnknown == 0 && !rssOverflow)
                        .put("scope", "Sum of captured readable VmRSS values; shared pages may be counted multiple times, not unique physical memory"))
                .put("scope", "Visible numeric /proc entries with verified matching real UID; sequential samples, Android visibility restrictions may hide processes; no cmdline or arguments read")
                .put("readLimitScope", "At most 8192 bytes per file; read-limit means possibly truncated, and any final partial line is ignored");
    }

    private static final class Read {
        final String text;
        final boolean available, atLimit;
        Read(String text, boolean available, boolean atLimit) { this.text = text; this.available = available; this.atLimit = atLimit; }
        static Read unavailable() { return new Read(null, false, false); }
        String kind() { return !available ? "unavailable" : atLimit ? "read-limit" : "readable"; }
    }

    private static Read read(File file, byte[] bytes) {
        int size = 0;
        try {
          if (Files.isSymbolicLink(file.toPath())) return Read.unavailable();
          try (FileInputStream input = new FileInputStream(file)) {
            while (size < bytes.length) {
                int count = input.read(bytes, size, bytes.length - size);
                if (count < 0) break;
                if (count == 0) break;
                size += count;
            }
            boolean atLimit = size == bytes.length;
            if (atLimit) while (size > 0 && bytes[size - 1] != '\n') size--;
            return new Read(new String(bytes, 0, size, StandardCharsets.UTF_8), true, atLimit);
          }
        }
        catch (IOException | SecurityException error) { return Read.unavailable(); }
    }

    private static Read readStatus(File directory, byte[] bytes) {
        try {
            return directory == null || Files.isSymbolicLink(directory.toPath())
                    ? Read.unavailable() : read(new File(directory, "status"), bytes);
        }
        catch (SecurityException error) { return Read.unavailable(); }
    }

    private static String field(String status, String key) {
        if (status == null) return null;
        for (String line : status.split("\n")) {
            if (line.startsWith(key + ":")) return line.substring(key.length() + 1).trim();
        }
        return null;
    }

    private static String firstToken(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim().split("\\s+", 2)[0];
    }

    private static String commName(String value) {
        String token = firstToken(value);
        if (token == null) return null;
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        token = token.substring(slash + 1);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < token.length() && name.length() < MAX_NAME_CHARS; i++) {
            char c = token.charAt(i);
            name.append(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '.' || c == '_' || c == '-' ? c : '_');
        }
        return name.length() == 0 ? null : name.toString();
    }

    private static String state(String value) {
        String token = firstToken(value);
        return token != null && token.length() == 1 && Character.isLetter(token.charAt(0)) ? token : null;
    }

    private static Long startTime(String stat, int expectedPid) {
        if (stat == null) return null;
        int open = stat.indexOf('('), close = stat.lastIndexOf(')');
        if (open <= 0 || close <= open) return null;
        Integer actualPid = integer(stat.substring(0, open).trim());
        if (actualPid == null || actualPid != expectedPid) return null;
        String[] fields = stat.substring(close + 1).trim().split("\\s+");
        // The suffix begins with field 3 (state); starttime is field 22.
        return fields.length > 19 ? nonnegativeLong(fields[19]) : null;
    }

    private static Long kilobytes(String value) {
        if (value == null) return null;
        String[] fields = value.split("\\s+");
        return fields.length == 2 && "kB".equals(fields[1]) ? nonnegativeLong(fields[0]) : null;
    }

    private static Long nonnegativeLong(String value) {
        if (value == null) return null;
        try { long number = Long.parseLong(value.trim()); return number >= 0 ? number : null; }
        catch (NumberFormatException error) { return null; }
    }

    private static Integer integer(String value) {
        if (value == null) return null;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException error) { return null; }
    }

    private static Integer nonnegativeInteger(String value) {
        Integer number = integer(value);
        return number != null && number >= 0 ? number : null;
    }

    private static Integer positivePid(String name) {
        Integer pid = integer(name);
        return pid != null && pid > 0 && name.equals(Integer.toString(pid)) ? pid : null;
    }

    private static Object unknown(Object value) { return value == null ? JSONObject.NULL : value; }
}
