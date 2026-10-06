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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/** Removes only verified Box64 clients of the captured private prefix after its server has stopped. */
final class Pd2WineProcesses {
    static final int MAX_SCANS = 512, MAX_CLIENTS = 64, MAX_FILE_BYTES = 32768;
    private static final long SCAN_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(2);

    interface Access {
        String[] list();
        Integer ownerUid(int pid) throws IOException;
        byte[] read(int pid, String file) throws IOException;
        String executable(int pid) throws IOException;
        void kill(int pid) throws IOException;
    }

    static final class Identity {
        final int pid;
        final long start;
        final String name;
        Identity(int pid, long start, String name) { this.pid = pid; this.start = start; this.name = name; }
        String key() { return pid + ":" + start; }
        JSONObject json() throws JSONException {
            return new JSONObject().put("pid", pid).put("startTimeTicks", start).put("identity", key()).put("name", name);
        }
    }

    static final class Capture {
        final Map<String, Identity> clients = new LinkedHashMap<>();
        int scanned, unknown, unclassified, outside;
        boolean complete = true;
        void unknown() { unknown++; complete = false; }
        JSONObject json() throws JSONException {
            JSONArray identities = new JSONArray();
            for (Identity client : clients.values()) identities.put(client.json());
            return new JSONObject().put("complete", complete).put("scanned", scanned).put("unknown", unknown)
                    .put("unclassifiedDirectoryOwner", unclassified).put("outsideScope", outside).put("clients", identities);
        }
    }

    static final class Result {
        final Capture before, after;
        Capture finalCheck;
        final List<Identity> signalled = new ArrayList<>();
        final List<Identity> changed = new ArrayList<>();
        final List<Identity> remaining = new ArrayList<>();
        int unknown, failures;
        boolean passed;
        Result(Capture before, Capture after) { this.before = before; this.after = after; }
        JSONObject json() throws JSONException {
            return new JSONObject().put("beforeShutdown", before.json()).put("afterServerWait", after.json())
                    .put("afterClientWait", finalCheck == null ? JSONObject.NULL : finalCheck.json())
                    .put("signalled", identities(signalled)).put("identityChangedOrExited", identities(changed))
                    .put("remaining", identities(remaining)).put("unknown", unknown).put("signalFailures", failures)
                    .put("passed", passed)
                    .put("scope", "Only matching real UID, captured canonical WINEPREFIX, exact private Box64 executable and PID/start-time identity; values and arguments are not exported")
                    .put("limits", new JSONObject().put("scanEntries", MAX_SCANS).put("clients", MAX_CLIENTS)
                            .put("bytesPerFile", MAX_FILE_BYTES).put("scanMillis", 1000).put("exitWaitMillis", 2000));
        }
        private static JSONArray identities(List<Identity> clients) throws JSONException {
            JSONArray result = new JSONArray();
            for (Identity client : clients) result.put(client.json());
            return result;
        }
    }

    private final Access access;
    private final int uid, appPid;
    private final String box64, prefix;

    Pd2WineProcesses(Access access, int uid, int appPid, String box64, String prefix) {
        this.access = access; this.uid = uid; this.appPid = appPid; this.box64 = box64; this.prefix = prefix;
    }

    Capture capture() {
        Capture capture = new Capture();
        String[] names = access.list();
        if (names == null) { capture.unknown(); return capture; }
        TreeSet<Integer> candidates = new TreeSet<>();
        boolean limited = false;
        for (String name : names) {
            try {
                int pid = Integer.parseInt(name);
                if (pid <= 0 || pid == appPid || !name.equals(Integer.toString(pid))) continue;
                candidates.add(pid);
                if (candidates.size() > MAX_SCANS) { candidates.pollLast(); limited = true; }
            } catch (NumberFormatException ignored) { }
        }
        long deadline = System.nanoTime() + SCAN_NANOS;
        for (int pid : candidates) {
            if (System.nanoTime() >= deadline) { limited = true; break; }
            capture.scanned++;
            try {
                Integer owner = access.ownerUid(pid);
                if (owner == null) { capture.unclassified++; continue; }
                if (owner != uid) { capture.outside++; continue; }
            } catch (java.nio.file.NoSuchFileException exited) { continue; }
            catch (IOException | SecurityException unrelatedOrRestricted) { capture.unclassified++; continue; }
            try {
                Identity identity = verify(pid);
                if (identity == null) { capture.outside++; continue; }
                if (capture.clients.size() == MAX_CLIENTS) { limited = true; break; }
                capture.clients.put(identity.key(), identity);
            } catch (java.nio.file.NoSuchFileException exited) { }
            catch (IOException | SecurityException restricted) { capture.unknown(); }
        }
        if (limited) capture.unknown();
        return capture;
    }

    /** Call only after the captured prefix's wineserver wait has succeeded, under the environment lock. */
    Result finish(Capture before) throws InterruptedException {
        Capture after = capture();
        Result result = new Result(before, after);
        Map<String, Identity> clients = new LinkedHashMap<>(before.clients);
        for (Identity current : after.clients.values()) {
            boolean reused = false;
            for (Identity original : before.clients.values())
                if (original.pid == current.pid && original.start != current.start) reused = true;
            // A reused PID does not acquire the old identity's authorization, even for the same prefix.
            if (!reused) clients.put(current.key(), current);
        }
        for (Identity original : clients.values()) {
            try {
                Identity current = revalidate(original);
                if (current == null || current.start != original.start) { result.changed.add(original); continue; }
                access.kill(original.pid);
                result.signalled.add(original);
            } catch (java.nio.file.NoSuchFileException exited) { result.changed.add(original); }
            catch (IOException | SecurityException restricted) { result.unknown++; result.failures++; }
        }
        long deadline = System.nanoTime() + WAIT_NANOS;
        do {
            result.remaining.clear();
            int unavailable = 0;
            for (Identity original : clients.values()) {
                try {
                    Identity current = revalidate(original);
                    if (current != null && current.start == original.start) result.remaining.add(original);
                } catch (java.nio.file.NoSuchFileException exited) { }
                catch (IOException | SecurityException restricted) { unavailable++; }
            }
            if (unavailable != 0) { result.unknown += unavailable; break; }
            if (result.remaining.isEmpty() || System.nanoTime() >= deadline) break;
            Thread.sleep(25);
        } while (true);
        Capture finalCapture = capture();
        result.finalCheck = finalCapture;
        // A service which spawned another client during shutdown must also block replacement startup.
        for (Identity current : finalCapture.clients.values()) {
            boolean listed = false;
            for (Identity remaining : result.remaining) if (remaining.key().equals(current.key())) listed = true;
            if (!listed) result.remaining.add(current);
        }
        result.passed = before.complete && after.complete && finalCapture.complete
                && result.unknown == 0 && result.failures == 0 && result.remaining.isEmpty();
        return result;
    }

    private Identity revalidate(Identity original) throws IOException {
        if (!originalAlive(original)) return null;
        Integer owner = access.ownerUid(original.pid);
        if (owner == null) throw new IOException("Captured client directory ownership is unavailable");
        if (owner != uid) {
            // /proc directory ownership can change to root when dumpability changes, even
            // without a real-UID change. A known live identity cannot be declared gone then.
            if (originalAlive(original))
                throw new IOException("Captured client directory ownership changed while still alive");
            return null;
        }
        Identity current = verify(original.pid);
        if (current == null && originalAlive(original))
            throw new IOException("Captured live client scope is no longer verifiable");
        return current;
    }

    private boolean originalAlive(Identity original) throws IOException {
        String stat = text(access.read(original.pid, "stat"));
        char state = state(stat);
        return startTime(stat, original.pid) == original.start && state != 'Z' && state != 'X' && state != 'x';
    }

    private Identity verify(int pid) throws IOException {
        if (pid <= 0 || pid == appPid) return null;
        Integer owner = access.ownerUid(pid);
        if (owner == null) throw new IOException("Unverified process directory ownership");
        if (owner != uid) return null;
        String status = text(access.read(pid, "status"));
        String realUid = first(field(status, "Uid"));
        try { if (realUid == null || Integer.parseInt(realUid) < 0) throw new IOException("Unverified UID"); }
        catch (NumberFormatException invalid) { throw new IOException("Unverified UID", invalid); }
        if (!realUid.equals(Integer.toString(uid))) return null;
        if (!Integer.toString(pid).equals(field(status, "Pid"))) throw new IOException("Unverified PID");
        String firstStat = text(access.read(pid, "stat"));
        long start = startTime(firstStat, pid);
        char state = state(firstStat);
        if (state == 'Z' || state == 'X' || state == 'x') return null;
        String executable = access.executable(pid);
        if (!box64.equals(executable)) return null;
        // This is the exec-time environment; do not persist it or read unrelated values.
        if (!prefix.equals(environmentPrefix(access.read(pid, "environ")))) return null;
        String lastStat = text(access.read(pid, "stat"));
        if (startTime(lastStat, pid) != start) throw new IOException("Process identity changed during verification");
        state = state(lastStat);
        if (state == 'Z' || state == 'X' || state == 'x') return null;
        String lastStatus = text(access.read(pid, "status"));
        if (!Integer.valueOf(uid).equals(access.ownerUid(pid))
                || !Integer.toString(uid).equals(first(field(lastStatus, "Uid")))
                || !Integer.toString(pid).equals(field(lastStatus, "Pid"))
                || !box64.equals(access.executable(pid)))
            throw new IOException("Process ownership changed during verification");
        return new Identity(pid, start, safeName(field(status, "Name")));
    }

    private static String text(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length >= MAX_FILE_BYTES) throw new IOException("Process file unavailable or exceeds bound");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static String environmentPrefix(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length >= MAX_FILE_BYTES) throw new IOException("Environment unavailable or exceeds bound");
        int start = 0;
        String prefix = null;
        for (int i = 0; i < bytes.length; i++) if (bytes[i] == 0) {
            String value = new String(bytes, start, i - start, StandardCharsets.UTF_8);
            if (value.startsWith("WINEPREFIX=")) {
                if (prefix != null) throw new IOException("Ambiguous prefix");
                prefix = value.substring("WINEPREFIX=".length());
            }
            start = i + 1;
        }
        if (start != bytes.length) throw new IOException("Incomplete process environment");
        return prefix;
    }

    private static String field(String text, String key) {
        for (String line : text.split("\n")) if (line.startsWith(key + ":")) return line.substring(key.length() + 1).trim();
        return null;
    }
    private static String first(String value) { return value == null ? null : value.split("\\s+", 2)[0]; }
    private static String safeName(String value) {
        if (value == null) return "unknown";
        return value.substring(0, Math.min(48, value.length())).replaceAll("[^a-zA-Z0-9._-]", "_");
    }
    private static char state(String stat) throws IOException {
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 2 >= stat.length()) throw new IOException("Invalid process stat");
        return stat.charAt(close + 2);
    }
    private static long startTime(String stat, int pid) throws IOException {
        int open = stat.indexOf('('), close = stat.lastIndexOf(')');
        if (open <= 0 || close <= open) throw new IOException("Invalid process stat");
        try {
            if (Integer.parseInt(stat.substring(0, open).trim()) != pid) throw new IOException("Process stat PID mismatch");
            String[] fields = stat.substring(close + 1).trim().split("\\s+");
            long value = fields.length > 19 ? Long.parseLong(fields[19]) : -1;
            if (value < 0) throw new IOException("Invalid process start time");
            return value;
        } catch (NumberFormatException invalid) { throw new IOException("Invalid process identity", invalid); }
    }

    interface Signal { void kill(int pid) throws IOException; }
    interface Owner { Integer uid(File directory) throws IOException; }

    static Access proc(File root, Signal signal) {
        return proc(root, signal, directory -> ((Number)Files.getAttribute(directory.toPath(), "unix:uid",
                java.nio.file.LinkOption.NOFOLLOW_LINKS)).intValue());
    }

    static Access proc(File root, Signal signal, Owner owner) {
        return new Access() {
            public String[] list() { try { return root.list(); } catch (SecurityException denied) { return null; } }
            public Integer ownerUid(int pid) throws IOException {
                File directory = new File(root, Integer.toString(pid));
                if (Files.isSymbolicLink(directory.toPath())) throw new IOException("Linked process directory");
                return owner.uid(directory);
            }
            public byte[] read(int pid, String file) throws IOException {
                File directory = new File(root, Integer.toString(pid));
                File path = new File(directory, file);
                if (Files.isSymbolicLink(directory.toPath()) || Files.isSymbolicLink(path.toPath()))
                    throw new IOException("Linked process identity file");
                byte[] bytes = new byte[MAX_FILE_BYTES];
                int size = 0;
                try (FileInputStream input = new FileInputStream(path)) {
                    while (size < bytes.length) {
                        int count = input.read(bytes, size, bytes.length - size);
                        if (count <= 0) break;
                        size += count;
                    }
                } catch (java.io.FileNotFoundException unavailable) {
                    if (!directory.exists()) throw new java.nio.file.NoSuchFileException(directory.getPath());
                    throw unavailable;
                }
                byte[] result = new byte[size];
                System.arraycopy(bytes, 0, result, 0, size);
                return result;
            }
            public String executable(int pid) throws IOException {
                return Files.readSymbolicLink(new File(root, pid + "/exe").toPath()).toString();
            }
            public void kill(int pid) throws IOException { signal.kill(pid); }
        };
    }
}
