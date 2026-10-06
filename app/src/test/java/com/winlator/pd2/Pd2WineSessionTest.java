package com.winlator.pd2;

import android.app.Application;
import com.winlator.core.EnvVars;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Exercise real cleanup ownership, prefix capture, process bounds and exclusive UDP probes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2WineSessionTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test public void absentServerIsAcceptedAndUsesTheCapturedRuntimeEnvironment() throws Exception {
        Fixture f = fixture();
        RecordingRunner runner = new RecordingRunner(1, 0);
        Pd2WineSession session = f.session(runner);
        try {
            Pd2WineSession.Result result = session.beforeLaunch();
            assertTrue(result.error, result.passed);
            assertEquals("beforeLaunch", result.phase);
            assertEquals(1, result.killStatus);
            assertEquals(0, result.waitStatus);
            assertTrue(result.portsFree);
            assertEquals(Arrays.asList("-k2", "-w"), runner.options);
            assertEquals(Arrays.asList("capture", "-k2", "-w", "clients", "ports"), runner.steps);
            assertFalse(result.serverEscalated);
            assertEquals(f.root.getCanonicalFile(), runner.directories.get(0));
            assertEquals(Arrays.asList(new File(f.root, "usr/local/bin/box64").getCanonicalPath(),
                    new File(f.root, "opt/wine/bin/wineserver").getCanonicalPath(), "-k2"), runner.commands.get(0));
            Map<String, String> variables = runner.environments.get(0);
            assertEquals(f.prefix.getCanonicalPath(), variables.get("WINEPREFIX"));
            assertEquals(new File(f.root, "usr/lib").getPath(), variables.get("LD_LIBRARY_PATH"));
            assertEquals(new File(f.root, "lib/x86_64-linux-gnu").getPath(), variables.get("BOX64_LD_LIBRARY_PATH"));
            assertEquals(new File(f.root, "tmp").getPath(), variables.get("TMPDIR"));
            assertEquals("1", variables.get("WINEESYNC"));
            assertEquals("-all", variables.get("WINEDEBUG"));
            assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(f.save.toPath()));
        } finally { session.stop(); }
    }

    @Test public void killFailureAndTimeoutCannotAcquireTheSessionOrRunTheWaitCommand() throws Exception {
        for (int killStatus : new int[]{2, -2}) {
            Fixture f = fixture();
            RecordingRunner runner = new RecordingRunner(killStatus, 0);
            Pd2WineSession session = f.session(runner);
            try {
                Pd2WineSession.Result result = session.beforeLaunch();
                assertFalse(result.passed);
                assertEquals(killStatus, result.killStatus);
                assertEquals(-1, result.waitStatus);
                assertEquals(Arrays.asList("-k2"), runner.options);
                assertEquals(0, runner.portProbes);
                assertEquals("stopSkipped", session.stop().phase);
                assertEquals(1, runner.commands.size());
                assertFreshSessionCanAcquire(f);
            } finally { session.stop(); }
        }
    }

    @Test public void waitFailureAndTimeoutCannotAcquireTheSessionOrProbeControllerPorts() throws Exception {
        for (int waitStatus : new int[]{1, -2}) {
            Fixture f = fixture();
            RecordingRunner runner = new RecordingRunner(0, waitStatus);
            Pd2WineSession session = f.session(runner);
            try {
                Pd2WineSession.Result result = session.beforeLaunch();
                assertFalse(result.passed);
                assertEquals(waitStatus, result.waitStatus);
                assertEquals(Arrays.asList("-k2", "-w", "-k9", "-w"), runner.options);
                assertTrue(result.serverEscalated);
                assertEquals(0, runner.portProbes);
                assertEquals("stopSkipped", session.stop().phase);
                assertFreshSessionCanAcquire(f);
            } finally { session.stop(); }
        }
    }

    @Test public void unavailableCleanupExecutableCannotAcquireTheSession() throws Exception {
        Fixture f = fixture();
        RecordingRunner runner = new RecordingRunner(0, 0);
        runner.failure = new IOException("missing executable");
        Pd2WineSession session = f.session(runner);
        try {
            Pd2WineSession.Result result = session.beforeLaunch();
            assertFalse(result.passed);
            assertTrue(result.error.contains("IOException"));
            assertEquals(-1, result.killStatus);
            assertEquals(0, runner.portProbes);
            assertEquals("stopSkipped", session.stop().phase);
            assertFreshSessionCanAcquire(f);
        } finally { session.stop(); }
    }

    @Test public void failedGracefulWaitEscalatesOnlyTheCapturedServerThenVerifiesItsClients() throws Exception {
        Fixture f = fixture();
        RecordingRunner runner = new RecordingRunner(0, -2);
        runner.waitRecovers = true;
        Pd2WineSession session = f.session(runner);
        try {
            Pd2WineSession.Result result = session.beforeLaunch();
            assertTrue(result.error, result.passed);
            assertTrue(result.serverEscalated);
            assertEquals(Arrays.asList("-k2", "-w", "-k9", "-w"), runner.options);
            assertEquals(Arrays.asList("capture", "-k2", "-w", "-k9", "-w", "clients", "ports"), runner.steps);
            for (Map<String, String> variables : runner.environments)
                assertEquals(f.prefix.getCanonicalPath(), variables.get("WINEPREFIX"));
        } finally { session.stop(); }
    }

    @Test public void unverifiedOrSurvivingClientsBlockRelaunchEvenWhenControllerPortsAreFree() throws Exception {
        Fixture f = fixture();
        RecordingRunner runner = new RecordingRunner(0, 0);
        runner.residualPassed = false;
        Pd2WineSession session = f.session(runner);
        try {
            Pd2WineSession.Result result = session.beforeLaunch();
            assertFalse(result.passed);
            assertTrue(result.portsFree);
            assertTrue(result.error.contains("Wine client processes"));
            assertFalse(result.processCleanup.getBoolean("passed"));
            assertEquals("stopSkipped", session.stop().phase);
            assertFreshSessionCanAcquire(f);
        } finally { session.stop(); }
    }

    @Test public void symlinkRetargetDoesNotChangeThePrefixUsedByEitherCleanupBoundary() throws Exception {
        Fixture f = fixture();
        File firstHome = new File(f.root, "home/xuser-1");
        File secondHome = new File(f.root, "home/xuser-2");
        assertTrue(new File(firstHome, ".wine").mkdirs());
        assertTrue(new File(secondHome, ".wine").mkdirs());
        File homeLink = new File(f.root, "home/xuser");
        Files.createSymbolicLink(homeLink.toPath(), firstHome.toPath());
        f.variables.put("WINEPREFIX", new File(homeLink, ".wine").getPath());
        RecordingRunner runner = new RecordingRunner(0, 0);
        Pd2WineSession session = f.session(runner);
        // Container activation changes this symlink after the session captures its target.
        Files.delete(homeLink.toPath());
        Files.createSymbolicLink(homeLink.toPath(), secondHome.toPath());
        try {
            assertTrue(session.beforeLaunch().passed);
            assertTrue(session.stop().passed);
            assertEquals(4, runner.environments.size());
            for (Map<String, String> variables : runner.environments)
                assertEquals(new File(firstHome, ".wine").getCanonicalPath(), variables.get("WINEPREFIX"));
        } finally { session.stop(); }
    }

    @Test public void oldStopIsIdempotentAndCannotStopTheReplacementOwner() throws Exception {
        Fixture f = fixture();
        RecordingRunner firstRunner = new RecordingRunner(0, 0);
        RecordingRunner nextRunner = new RecordingRunner(1, 0);
        Pd2WineSession first = f.session(firstRunner);
        Pd2WineSession next = f.session(nextRunner);
        try {
            assertTrue(first.beforeLaunch().passed);
            assertFalse("An acquired session must not rerun launch cleanup", first.beforeLaunch().passed);
            assertEquals(2, firstRunner.commands.size());
            assertFalse(next.beforeLaunch().passed);
            assertTrue("An owner rejection must not execute cleanup", nextRunner.commands.isEmpty());
            Pd2WineSession.Result firstStop = first.stop();
            assertTrue(firstStop.passed);
            assertEquals(4, firstRunner.commands.size());
            assertTrue(next.beforeLaunch().passed);
            assertSame(firstStop, first.stop());
            assertFalse("A stopped session must never reacquire the replacement prefix", first.beforeLaunch().passed);
            assertEquals(4, firstRunner.commands.size());
            assertEquals(2, nextRunner.commands.size());
            assertTrue(next.stop().passed);
            assertEquals(4, nextRunner.commands.size());
        } finally { first.stop(); next.stop(); }
    }

    @Test public void anUnpreparedOldSessionCannotStopAnActiveReplacement() throws Exception {
        Fixture f = fixture();
        RecordingRunner rejectedRunner = new RecordingRunner(-2, 0);
        RecordingRunner nextRunner = new RecordingRunner(0, 0);
        Pd2WineSession rejected = f.session(rejectedRunner);
        Pd2WineSession next = f.session(nextRunner);
        try {
            assertFalse(rejected.beforeLaunch().passed);
            assertTrue(next.beforeLaunch().passed);
            assertEquals("stopSkipped", rejected.stop().phase);
            assertEquals(1, rejectedRunner.commands.size());
            assertEquals(2, nextRunner.commands.size());
            assertTrue(next.stop().passed);
        } finally { rejected.stop(); next.stop(); }
    }

    @Test public void realPortProbeRejectsEitherOccupiedEndpointAndReleasesItsOwnSocket() throws Exception {
        Pd2WineSession.Runner real = processRunner();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        for (int port : new int[]{7949, 7950}) {
            try (DatagramSocket occupied = new DatagramSocket(null)) {
                occupied.bind(new InetSocketAddress(loopback, port));
                assertFalse("Occupied native endpoint " + port, real.portsFree());
                assertEquals(port != 7949, real.portStatus().getBoolean("7949Free"));
                assertEquals(port != 7950, real.portStatus().getBoolean("7950Free"));
            }
            // A failed HID bind must close the probe's earlier successful XInput bind.
            assertTrue("Probe must release its temporary native sockets", real.portsFree());
        }
    }

    @Test public void launchRecreatesSharedMemoryAfterThePostPreflightTemporaryClear() throws Exception {
        Fixture f = fixture();
        Pd2WineSession session = f.session(new RecordingRunner(1, 0));
        File tmp = new File(f.root, "tmp");
        File shm = new File(tmp, "shm");
        try {
            Pd2WineSession.prepareLaunchDirectories(f.root);
            assertTrue(session.beforeLaunch().passed);
            // The display startup clears runtime tmp after cleanup releases old Wine clients.
            Files.delete(shm.toPath());
            Files.delete(tmp.toPath());
            assertFalse(shm.exists());
            Pd2WineSession.prepareLaunchDirectories(f.root);
            Map<String, String> variables = new LinkedHashMap<>();
            variables.put("PD2_EXPECTED_SHM", shm.getPath());
            int status = processRunner().run(Arrays.asList("/bin/sh", "-c", "test -d \"$PD2_EXPECTED_SHM\""), variables, f.root);
            assertEquals("Shared-memory directory must exist when the guest is executed", 0, status);
            assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(f.save.toPath()));
        } finally { session.stop(); }
    }

    @Test public void launchDirectoryCreationRejectsASymlinkOutsideTheRuntime() throws Exception {
        Fixture f = fixture();
        File outside = directory.newFolder();
        File marker = new File(outside, "retained.txt");
        Files.write(marker.toPath(), new byte[]{4});
        Files.createSymbolicLink(new File(f.root, "tmp").toPath(), outside.toPath());
        try {
            Pd2WineSession.prepareLaunchDirectories(f.root);
            fail("A linked temporary directory must not escape the runtime");
        } catch (IOException expected) {
            assertFalse(new File(outside, "shm").exists());
            assertArrayEquals(new byte[]{4}, Files.readAllBytes(marker.toPath()));
        }
    }

    @Test(timeout = 10000) public void cleanupOutputIsDrainedAndBoundedWithoutLosingTheFailureTail() throws Exception {
        Pd2WineSession.Runner runner = processRunner();
        int status = runner.run(Arrays.asList("/bin/sh", "-c",
                "i=0; while [ $i -lt 12000 ]; do echo cleanup-noise; i=$((i+1)); done; echo final-loader-error >&2; exit 3"),
                new LinkedHashMap<>(), directory.getRoot());
        assertEquals(3, status);
        assertTrue(runner.output().endsWith("final-loader-error\n"));
        assertTrue("Helper diagnostics must remain bounded", runner.output().getBytes(StandardCharsets.UTF_8).length <= 4096);
        assertEquals(0, runner.run(Arrays.asList("/bin/sh", "-c", "exit 0"), new LinkedHashMap<>(), directory.getRoot()));
        assertEquals("A later command cannot retain an old failure", "", runner.output());
    }

    @Test(timeout = 10000) public void busyNativePortPreventsAcquisitionUntilTheOldReceiverCloses() throws Exception {
        Fixture f = fixture();
        RecordingRunner runner = new RecordingRunner(0, 0);
        runner.portRunner = processRunner();
        Pd2WineSession session = f.session(runner);
        DatagramSocket occupied = new DatagramSocket(null);
        occupied.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 7950));
        try {
            Pd2WineSession.Result blocked = session.beforeLaunch();
            assertFalse(blocked.passed);
            assertFalse(blocked.portsFree);
            assertTrue("Cleanup must wait briefly for an exiting Wine receiver", runner.portProbes > 1);
            assertTrue(blocked.elapsedMillis >= 1500);
            assertEquals("stopSkipped", session.stop().phase);
            occupied.close();
            assertTrue(session.beforeLaunch().passed);
            assertTrue(session.stop().passed);
        } finally { occupied.close(); session.stop(); }
    }

    @Test(timeout = 12000) public void realRunnerBoundsAndTerminatesAnUnresponsiveCommand() throws Exception {
        File pidFile = directory.newFile("cleanup-pid.txt");
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("PD2_TIMEOUT_PID_FILE", pidFile.getPath());
        List<String> command = Arrays.asList("/bin/sh", "-c", "echo $$ > \"$PD2_TIMEOUT_PID_FILE\"; exec sleep 30");
        long started = System.nanoTime();
        int status = processRunner().run(command, variables, directory.getRoot());
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(-2, status);
        assertTrue("Command must time out after its configured wait", elapsed >= 3000);
        assertTrue("An unresponsive helper must not block relaunch indefinitely", elapsed < 8000);
        long pid = Long.parseLong(new String(Files.readAllBytes(pidFile.toPath()), StandardCharsets.UTF_8).trim());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (processAlive(pid) && System.nanoTime() < deadline)
            Thread.sleep(10);
        assertFalse("Timeout must terminate the cleanup process", processAlive(pid));
    }

    private boolean processAlive(long pid) throws IOException {
        File stat = new File("/proc/" + pid + "/stat");
        if (!stat.exists()) return false;
        try {
            String value = new String(Files.readAllBytes(stat.toPath()), StandardCharsets.UTF_8);
            char state = value.charAt(value.lastIndexOf(')') + 2);
            return state != 'Z' && state != 'X';
        } catch (java.nio.file.NoSuchFileException exited) { return false; }
    }

    private void assertFreshSessionCanAcquire(Fixture f) throws Exception {
        Pd2WineSession fresh = f.session(new RecordingRunner(1, 0));
        try { assertTrue("Failed preparation must not claim ownership", fresh.beforeLaunch().passed); }
        finally { fresh.stop(); }
    }

    private Fixture fixture() throws Exception {
        File root = directory.newFolder();
        File prefix = new File(root, "prefix/.wine");
        assertTrue(prefix.mkdirs());
        File save = new File(prefix, "character.d2s");
        Files.write(save.toPath(), new byte[]{9, 8, 7});
        EnvVars variables = new EnvVars().put("WINEPREFIX", prefix.getPath())
                .put("LD_LIBRARY_PATH", new File(root, "usr/lib").getPath())
                .put("BOX64_LD_LIBRARY_PATH", new File(root, "lib/x86_64-linux-gnu").getPath())
                .put("TMPDIR", new File(root, "tmp").getPath())
                .put("WINEDEBUG", "+xinput").put("WINEESYNC", "1");
        return new Fixture(root, prefix, save, variables);
    }

    private static Pd2WineSession.Runner processRunner() throws Exception {
        Class<?> type = Class.forName("com.winlator.pd2.Pd2WineSession$ProcessRunner");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (Pd2WineSession.Runner) constructor.newInstance();
    }

    private static final class Fixture {
        final File root, prefix, save;
        final EnvVars variables;

        Fixture(File root, File prefix, File save, EnvVars variables) {
            this.root = root; this.prefix = prefix; this.save = save; this.variables = variables;
        }

        Pd2WineSession session(Pd2WineSession.Runner runner) throws IOException {
            return new Pd2WineSession(root, "/opt/wine", variables, runner);
        }
    }

    private static final class RecordingRunner implements Pd2WineSession.Runner {
        final int killStatus, waitStatus;
        final List<List<String>> commands = new ArrayList<>();
        final List<String> options = new ArrayList<>();
        final List<String> steps = new ArrayList<>();
        final List<Map<String, String>> environments = new ArrayList<>();
        final List<File> directories = new ArrayList<>();
        int portProbes;
        IOException failure;
        Pd2WineSession.Runner portRunner;
        boolean waitRecovers, residualPassed = true;
        int waitRuns;

        RecordingRunner(int killStatus, int waitStatus) {
            this.killStatus = killStatus; this.waitStatus = waitStatus;
        }

        @Override public int run(List<String> command, Map<String, String> environment, File directory) throws IOException {
            commands.add(new ArrayList<>(command));
            environments.add(new LinkedHashMap<>(environment));
            directories.add(directory);
            String option = command.get(2);
            options.add(option);
            steps.add(option);
            if (failure != null) throw failure;
            if (option.startsWith("-k")) return killStatus;
            return waitRecovers && ++waitRuns > 1 ? 0 : waitStatus;
        }

        @Override public Pd2WineProcesses.Capture captureProcesses(File box64, String prefix) {
            steps.add("capture");
            return new Pd2WineProcesses.Capture();
        }

        @Override public Pd2WineProcesses.Result finishProcesses(Pd2WineProcesses.Capture before) {
            steps.add("clients");
            Pd2WineProcesses.Result result = new Pd2WineProcesses.Result(before, new Pd2WineProcesses.Capture());
            result.passed = residualPassed;
            return result;
        }

        @Override public boolean portsFree() throws IOException {
            portProbes++;
            steps.add("ports");
            return portRunner == null || portRunner.portsFree();
        }
    }
}
