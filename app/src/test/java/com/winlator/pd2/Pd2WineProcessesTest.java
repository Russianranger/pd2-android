package com.winlator.pd2;

import android.app.Application;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Real proc files plus controlled identity changes exercise the cleanup's signal boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2WineProcessesTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();
    private static final int UID = 10298, APP = 14689;
    private static final String BOX64 = "/private/runtime/usr/local/bin/box64";
    private static final String PREFIX = "/private/runtime/home/xuser-2/.wine";

    @Test public void orphanedClientsAreRemovedByIdentityWithoutAPidRangeOrNameSelection() throws Exception {
        Fake access = new Fake();
        access.add(50, 111, UID, BOX64, PREFIX, "arbitrary-name");
        access.add(14940, 91000720, UID, BOX64, PREFIX, "winedevice.exe");
        access.add(14964, 91000771, UID, BOX64, PREFIX, "winedevice.exe");
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture before = processes.capture();
        assertEquals(3, before.clients.size());
        Pd2WineProcesses.Result result = processes.finish(before);
        assertTrue(result.json().toString(), result.passed);
        assertEquals(Arrays.asList(50, 14940, 14964), access.signals);
        assertEquals(3, result.signalled.size());
        assertTrue(result.remaining.isEmpty());
    }

    @Test public void appForeignUidDifferentPrefixAndDifferentExecutableAreProtected() throws Exception {
        Fake access = new Fake();
        access.add(APP, 11, UID, BOX64, PREFIX, "com.pd2.thor");
        access.add(2, 22, UID + 1, BOX64, PREFIX, "winedevice.exe");
        access.add(3, 33, UID, BOX64, "/private/runtime/home/xuser-1/.wine", "winedevice.exe");
        access.add(4, 44, UID, "/private/other/box64", PREFIX, "winedevice.exe");
        access.add(5, 55, UID, BOX64 + " (deleted)", PREFIX, "winedevice.exe");
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Result result = processes.finish(processes.capture());
        assertTrue(result.passed);
        assertTrue(access.signals.isEmpty());
        assertFalse("Other-UID input/environment must not be read", access.reads.contains("2:environ"));
        assertFalse(access.reads.contains(APP + ":status"));
    }

    @Test public void reusedPidCannotReceiveAnOldSessionsSignal() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture before = processes.capture();
        access.add(14940, 999, UID, "/private/unrelated/tool", PREFIX, "winedevice.exe");
        Pd2WineProcesses.Result result = processes.finish(before);
        assertTrue(result.passed);
        assertTrue(access.signals.isEmpty());
        assertEquals(1, result.changed.size());
    }

    @Test public void clientThatChangedPrefixCannotReceiveAnOldSessionsSignal() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture before = processes.capture();
        access.entries.get(14940).put("environ", bytes("WINEPREFIX=/private/other/.wine\0"));
        assertFalse("The original live identity has changed scope, not proved exit", processes.finish(before).passed);
        assertTrue(access.signals.isEmpty());
    }

    @Test public void capturedLiveIdentityChangingExecutableCannotBeSignalledOrDeclaredExited() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture before = processes.capture();
        access.exes.put(14940, "/private/different-executable");
        assertFalse(processes.finish(before).passed);
        assertTrue(access.signals.isEmpty());
    }

    @Test public void ownershipLostDuringVerificationCannotAuthorizeASignal() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture before = processes.capture();
        access.beforeRead = (pid, file) -> {
            if (file.equals("environ")) access.owners.put(pid, 0);
        };
        assertFalse(processes.finish(before).passed);
        assertTrue(access.signals.isEmpty());
    }

    @Test public void reusedPidInTheSamePrefixBlocksReplacementWithoutInheritingSignalPermission() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture before = processes.capture();
        access.add(14940, 999, UID, BOX64, PREFIX, "winedevice.exe");
        Pd2WineProcesses.Result result = processes.finish(before);
        assertFalse(result.passed);
        assertTrue(access.signals.isEmpty());
        assertEquals(1, result.remaining.size());
        assertEquals(999, result.remaining.get(0).start);
    }

    @Test public void capturedLiveIdentityLosingItsUidCannotBeSignalledOrDeclaredStopped() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture before = processes.capture();
        access.add(14940, 111, UID + 1, BOX64, PREFIX, "winedevice.exe");
        assertFalse(processes.finish(before).passed);
        assertTrue(access.signals.isEmpty());
    }

    @Test public void rootOwnedProcDirectoryDoesNotProveThatAPreviouslyCapturedSameUidClientExited() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture before = processes.capture();
        // Linux may make proc files root-owned for a nondumpable process while its real UID remains ours.
        access.owners.put(14940, 0);
        assertFalse(processes.finish(before).passed);
        assertTrue(access.signals.isEmpty());
    }

    @Test public void lostDirectoryOwnershipRequiresPositiveExitedOrReusedIdentityProof() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture before = processes.capture();
        access.owners.put(14940, 0); access.restricted = "stat";
        assertFalse(processes.finish(before).passed);
        assertTrue(access.signals.isEmpty());
        access.restricted = null;
        access.add(14940, 999, UID + 1, "/foreign/tool", "/foreign/.wine", "other-process");
        assertTrue(processes.finish(before).passed);
        assertTrue(access.signals.isEmpty());
    }

    @Test public void identityChangeDuringVerificationFailsClosed() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        access.beforeRead = (pid, file) -> {
            if (file.equals("environ")) access.entries.get(pid).put("stat", bytes(stat(pid, 999, "winedevice.exe", 'S')));
        };
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture before = processes.capture();
        assertFalse(before.complete);
        assertTrue(before.clients.isEmpty());
        assertTrue(access.signals.isEmpty());
    }

    @Test public void missingOrRestrictedOwnershipCannotAuthorizeASignalOrCleanRelaunch() throws Exception {
        for (String restricted : Arrays.asList("status", "stat", "environ", "exe")) {
            Fake access = new Fake();
            access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
            access.restricted = restricted;
            Pd2WineProcesses processes = processes(access);
            Pd2WineProcesses.Capture before = processes.capture();
            assertFalse(restricted, before.complete);
            assertFalse(restricted, processes.finish(before).passed);
            assertTrue(restricted, access.signals.isEmpty());
        }
    }

    @Test public void unavailableProcListingBlocksCleanupVerification() throws Exception {
        Fake access = new Fake();
        access.listAvailable = false;
        Pd2WineProcesses processes = processes(access);
        assertFalse(processes.finish(processes.capture()).passed);
        assertTrue(access.signals.isEmpty());
    }

    @Test public void unreadableForeignPidAndUnclassifiedGlobalDirectoryDoNotBlockThePrivateSession() throws Exception {
        Fake access = new Fake();
        access.add(10, 111, UID + 1, BOX64, PREFIX, "foreign-private-process");
        access.add(20, 222, UID, BOX64, PREFIX, "unknown-directory");
        access.ownerUnknown = 20;
        access.restricted = "status";
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture capture = processes.capture();
        assertTrue(capture.complete);
        assertEquals(1, capture.unclassified);
        assertTrue(processes.finish(capture).passed);
        assertTrue(access.signals.isEmpty());
        assertFalse(access.reads.contains("10:status"));
        assertFalse(access.reads.contains("20:status"));
    }

    @Test public void capturedOwnedClientLosingDirectoryVerificationBlocksRelaunchWithoutASignal() throws Exception {
        Fake access = new Fake();
        access.add(20, 222, UID, BOX64, PREFIX, "owned-client");
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture capture = processes.capture();
        access.ownerUnknown = 20;
        assertFalse(processes.finish(capture).passed);
        assertTrue(access.signals.isEmpty());
    }

    @Test public void deadOrExitedClientsNeedNoSignal() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture before = processes.capture();
        access.entries.get(14940).put("stat", bytes(stat(14940, 111, "winedevice.exe", 'Z')));
        assertTrue(processes.finish(before).passed);
        assertTrue(access.signals.isEmpty());
        access.entries.clear();
        assertTrue(processes.finish(before).passed);
        assertTrue(access.signals.isEmpty());
    }

    @Test public void aLateSamePrefixServiceIsCapturedAfterTheServerWait() throws Exception {
        Fake access = new Fake();
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Capture before = processes.capture();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        assertTrue(processes.finish(before).passed);
        assertEquals(Arrays.asList(14940), access.signals);
    }

    @Test public void failedSignalsCannotReportCleanupSuccess() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        access.signalFailure = true;
        Pd2WineProcesses processes = processes(access);
        Pd2WineProcesses.Result result = processes.finish(processes.capture());
        assertFalse(result.passed);
        assertEquals(1, result.failures);
        assertEquals(1, result.remaining.size());
    }

    @Test(timeout = 5000) public void unresponsiveOwnedClientHasABoundedExitWait() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        access.removeOnSignal = false;
        Pd2WineProcesses processes = processes(access);
        long started = System.nanoTime();
        Pd2WineProcesses.Result result = processes.finish(processes.capture());
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertFalse(result.passed);
        assertTrue(millis >= 1500);
        assertTrue(millis < 4500);
        assertEquals(Arrays.asList(14940), access.signals);
    }

    @Test public void scanAndClientCapsCannotReportAnIncompleteScanAsClean() {
        Fake access = new Fake();
        for (int i = 1; i <= Pd2WineProcesses.MAX_CLIENTS + 1; i++) access.add(i, i, UID, BOX64, PREFIX, "client");
        Pd2WineProcesses.Capture capped = processes(access).capture();
        assertFalse(capped.complete);
        assertEquals(Pd2WineProcesses.MAX_CLIENTS, capped.clients.size());
        access.entries.clear(); access.exes.clear();
        for (int i = 1; i <= Pd2WineProcesses.MAX_SCANS + 1; i++) access.add(i, i, UID + 1, BOX64, PREFIX, "client");
        capped = processes(access).capture();
        assertFalse(capped.complete);
        assertEquals(Pd2WineProcesses.MAX_SCANS, capped.scanned);
    }

    @Test public void oversizedMalformedAndDuplicateEnvironmentFieldsFailClosed() throws Exception {
        for (byte[] malformed : Arrays.asList(new byte[Pd2WineProcesses.MAX_FILE_BYTES],
                bytes("WINEPREFIX=" + PREFIX), bytes("WINEPREFIX=" + PREFIX + "\0WINEPREFIX=" + PREFIX + "\0"))) {
            Fake access = new Fake();
            access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
            access.entries.get(14940).put("environ", malformed);
            Pd2WineProcesses processes = processes(access);
            assertFalse(processes.finish(processes.capture()).passed);
            assertTrue(access.signals.isEmpty());
        }
    }

    @Test public void malformedPidStartTimeAndUidCannotAuthorizeSignals() throws Exception {
        for (String field : Arrays.asList("Uid", "Pid", "stat")) {
            Fake access = new Fake();
            access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
            if (field.equals("stat")) access.entries.get(14940).put("stat", bytes("14940 (client) S 1"));
            else access.entries.get(14940).put("status", bytes("Name: client\nPid: " + (field.equals("Pid") ? "9" : "14940")
                    + "\nUid: " + (field.equals("Uid") ? "invalid" : UID) + "\n"));
            Pd2WineProcesses processes = processes(access);
            assertFalse(processes.finish(processes.capture()).passed);
            assertTrue(access.signals.isEmpty());
        }
    }

    @Test public void diagnosticsContainIdentitiesAndOutcomesButNoEnvironmentOrArguments() throws Exception {
        Fake access = new Fake();
        access.add(14940, 111, UID, BOX64, PREFIX, "winedevice.exe");
        access.entries.get(14940).put("environ", bytes("TOKEN=secret-value\0WINEPREFIX=" + PREFIX + "\0PASSWORD=secret\0"));
        Pd2WineProcesses processes = processes(access);
        String json = processes.finish(processes.capture()).json().toString();
        assertTrue(json.contains("14940:111"));
        assertFalse(json.contains("secret-value"));
        assertFalse(json.contains("PASSWORD="));
        assertFalse(json.contains(PREFIX));
        assertFalse(json.contains(BOX64));
    }

    @Test public void realFilesystemReaderRejectsLinkedOwnershipFilesAndBoundsBytes() throws Exception {
        File proc = directory.newFolder();
        File entry = new File(proc, "14940"); assertTrue(entry.mkdir());
        File marker = directory.newFile(); Files.write(marker.toPath(), bytes("sensitive"));
        Files.createSymbolicLink(new File(entry, "environ").toPath(), marker.toPath());
        Pd2WineProcesses.Access access = Pd2WineProcesses.proc(proc, pid -> fail("No signal expected"));
        try { access.read(14940, "environ"); fail("Linked identity files must be rejected"); }
        catch (IOException expected) { }
        File status = new File(entry, "status"); Files.write(status.toPath(), new byte[Pd2WineProcesses.MAX_FILE_BYTES + 50]);
        assertEquals(Pd2WineProcesses.MAX_FILE_BYTES, access.read(14940, "status").length);
        assertArrayEquals(bytes("sensitive"), Files.readAllBytes(marker.toPath()));
    }

    @Test(timeout = 6000) public void actualChildProcessWithMatchingScopeIsStoppedAndForeignPrefixIsPreserved() throws Exception {
        File sleep = new File("/usr/bin/sleep");
        org.junit.Assume.assumeTrue(sleep.isFile());
        String uniquePrefix = PREFIX + "-" + java.util.UUID.randomUUID();
        ProcessBuilder ownedBuilder = new ProcessBuilder(sleep.getPath(), "30");
        ownedBuilder.environment().put("WINEPREFIX", uniquePrefix);
        Process owned = ownedBuilder.start();
        ProcessBuilder foreignBuilder = new ProcessBuilder(sleep.getPath(), "30");
        foreignBuilder.environment().put("WINEPREFIX", "/private/foreign/.wine");
        Process foreign = foreignBuilder.start();
        try {
            int appPid = Integer.parseInt(Files.readSymbolicLink(new File("/proc/self").toPath()).toString());
            int uid = ((Number)Files.getAttribute(new File("/proc/self").toPath(), "unix:uid")).intValue();
            // Development proc is mounted outside this runner's PID namespace. The production
            // scanner obtains the real proc identity; the test signals its owned Process object.
            int[] procIdentity = {-1};
            Pd2WineProcesses.Access access = Pd2WineProcesses.proc(new File("/proc"), pid -> {
                assertEquals("Only the owned real child may be signalled", procIdentity[0], pid);
                owned.destroyForcibly();
            });
            Pd2WineProcesses processes = new Pd2WineProcesses(access, uid, appPid, sleep.getCanonicalPath(), uniquePrefix);
            Pd2WineProcesses.Capture captured = processes.capture();
            assertEquals("The unique prefix identifies only the owned live child", 1, captured.clients.size());
            procIdentity[0] = captured.clients.values().iterator().next().pid;
            Pd2WineProcesses.Result result = processes.finish(captured);
            assertEquals(1, result.signalled.size());
            assertTrue(result.remaining.isEmpty());
            if (!captured.complete || !result.after.complete)
                assertFalse("Unverifiable same-UID host neighbors cannot be declared clean", result.passed);
            assertFalse(owned.isAlive());
            assertTrue("A different prefix must remain running", foreign.isAlive());
        } finally { owned.destroyForcibly(); foreign.destroyForcibly(); owned.waitFor(); foreign.waitFor(); }
    }

    private Pd2WineProcesses processes(Fake access) { return new Pd2WineProcesses(access, UID, APP, BOX64, PREFIX); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String stat(int pid, long start, String name, char state) {
        StringBuilder fields = new StringBuilder(pid + " (" + name + ") " + state);
        for (int i = 4; i < 22; i++) fields.append(" 1");
        return fields.append(' ').append(start).append(" 0 0\n").toString();
    }

    interface BeforeRead { void run(int pid, String field); }
    private static final class Fake implements Pd2WineProcesses.Access {
        final Map<Integer, Map<String, byte[]>> entries = new LinkedHashMap<>();
        final Map<Integer, String> exes = new LinkedHashMap<>();
        final Map<Integer, Integer> owners = new LinkedHashMap<>();
        final List<Integer> signals = new ArrayList<>();
        final List<String> reads = new ArrayList<>();
        boolean listAvailable = true, removeOnSignal = true, signalFailure;
        String restricted;
        int ownerUnknown = -1;
        BeforeRead beforeRead;
        void add(int pid, long start, int uid, String exe, String prefix, String name) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("status", bytes("Name: " + name + "\nPid: " + pid + "\nPPid: 1\nUid: " + uid + " " + uid + " " + uid + " " + uid + "\n"));
            files.put("stat", bytes(stat(pid, start, name, 'S')));
            files.put("environ", bytes("WINEPREFIX=" + prefix + "\0IGNORED=not-exported\0"));
            entries.put(pid, files); exes.put(pid, exe);
            owners.put(pid, uid);
        }
        public String[] list() { return listAvailable ? entries.keySet().stream().map(String::valueOf).toArray(String[]::new) : null; }
        public Integer ownerUid(int pid) throws IOException {
            if (!entries.containsKey(pid)) throw new java.nio.file.NoSuchFileException(Integer.toString(pid));
            return pid == ownerUnknown ? null : owners.get(pid);
        }
        public byte[] read(int pid, String field) throws IOException {
            reads.add(pid + ":" + field);
            if (beforeRead != null) beforeRead.run(pid, field);
            if (field.equals(restricted)) throw new IOException("Restricted fixture");
            if (!entries.containsKey(pid)) throw new java.nio.file.NoSuchFileException(Integer.toString(pid));
            return entries.get(pid).get(field);
        }
        public String executable(int pid) throws IOException {
            if ("exe".equals(restricted)) throw new IOException("Restricted fixture");
            return exes.get(pid);
        }
        public void kill(int pid) throws IOException {
            if (signalFailure) throw new IOException("Signal refused");
            signals.add(pid);
            if (removeOnSignal) { entries.remove(pid); exes.remove(pid); }
        }
    }
}
