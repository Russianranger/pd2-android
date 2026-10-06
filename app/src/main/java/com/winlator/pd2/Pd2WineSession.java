package com.winlator.pd2;

import com.winlator.core.EnvVars;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

/** Stop the exact PD2 prefix, including Wine services which outlive explorer.exe. */
public final class Pd2WineSession {
    private static final Object LOCK = new Object();
    private static Pd2WineSession active;
    private static final long COMMAND_TIMEOUT_MS = 4000;
    private final File root;
    private final File box64;
    private final File wineserver;
    private final Map<String, String> environment = new LinkedHashMap<>();
    private final Runner runner;
    private boolean prepared;
    private Result stopped;

    interface Runner {
        int run(List<String> command, Map<String, String> environment, File directory) throws IOException, InterruptedException;
        boolean portsFree() throws IOException;
        default String output() { return ""; }
        default Pd2WineProcesses.Capture captureProcesses(File box64, String prefix) { return new Pd2WineProcesses.Capture(); }
        default Pd2WineProcesses.Result finishProcesses(Pd2WineProcesses.Capture before) throws InterruptedException {
            Pd2WineProcesses.Result result = new Pd2WineProcesses.Result(before, new Pd2WineProcesses.Capture());
            result.passed = true;
            return result;
        }
        default JSONObject portStatus() throws JSONException { return new JSONObject(); }
    }

    public static final class Result {
        public final String phase;
        public final int killStatus, waitStatus;
        public final boolean portsFree, passed;
        public final long elapsedMillis;
        public final String error;
        public final String killOutput, waitOutput;
        public final JSONObject processCleanup, controllerPortStatus;
        public final boolean serverEscalated;

        Result(String phase, int killStatus, int waitStatus, boolean portsFree, long elapsedMillis, String error) {
            this(phase, killStatus, waitStatus, portsFree, elapsedMillis, error, "", "");
        }

        Result(String phase, int killStatus, int waitStatus, boolean portsFree, long elapsedMillis, String error,
               String killOutput, String waitOutput) {
            this(phase, killStatus, waitStatus, portsFree, elapsedMillis, error, killOutput, waitOutput,
                    null, null, false);
        }

        Result(String phase, int killStatus, int waitStatus, boolean portsFree, long elapsedMillis, String error,
               String killOutput, String waitOutput, JSONObject processCleanup, JSONObject controllerPortStatus,
               boolean serverEscalated) {
            this.phase = phase;
            this.killStatus = killStatus;
            this.waitStatus = waitStatus;
            this.portsFree = portsFree;
            this.elapsedMillis = elapsedMillis;
            this.error = error;
            this.killOutput = killOutput;
            this.waitOutput = waitOutput;
            this.processCleanup = processCleanup;
            this.controllerPortStatus = controllerPortStatus;
            this.serverEscalated = serverEscalated;
            // Wine returns 1 when -k finds no running server, including a clean first launch.
            passed = (killStatus == 0 || killStatus == 1) && waitStatus == 0 && portsFree && error.isEmpty();
        }

        public JSONObject json() throws JSONException {
            return new JSONObject().put("phase", phase).put("killStatus", killStatus)
                    .put("waitStatus", waitStatus).put("controllerPortsFree", portsFree)
                    .put("passed", passed).put("elapsedMillis", elapsedMillis).put("error", error)
                    .put("killOutput", killOutput).put("waitOutput", waitOutput)
                    .put("shutdownSignal", "SIGINT (-k2), which enters Wine's client termination path")
                    .put("serverEscalated", serverEscalated)
                    .put("processCleanup", processCleanup == null ? JSONObject.NULL : processCleanup)
                    .put("controllerPortStatus", controllerPortStatus == null ? JSONObject.NULL : controllerPortStatus)
                    .put("scope", "Exact captured PD2 Wine prefix shutdown, verified same-prefix Box64 client identities and exclusive loopback ports 7949/7950; no process-name or PID-range kill");
        }
    }

    /** Temporary files are cleared after preflight; recreate these immediately before guest startup. */
    public static void prepareLaunchDirectories(File root) throws IOException {
        File runtime = root.getCanonicalFile();
        File sharedMemory = new File(runtime, "tmp/shm");
        if (!sharedMemory.getCanonicalFile().getPath().startsWith(runtime.getPath() + File.separator))
            throw new IOException("Runtime shared-memory directory is outside the private runtime");
        if (!sharedMemory.isDirectory() && !sharedMemory.mkdirs())
            throw new IOException("Cannot create the runtime shared-memory directory");
    }

    public Pd2WineSession(File root, String winePath, EnvVars variables) throws IOException {
        this(root, winePath, variables, new ProcessRunner());
    }

    Pd2WineSession(File root, String winePath, EnvVars variables, Runner runner) throws IOException {
        this.root = root.getCanonicalFile();
        this.box64 = new File(this.root, "usr/local/bin/box64").getCanonicalFile();
        this.wineserver = new File(this.root.getPath() + winePath + "/bin/wineserver").getCanonicalFile();
        if (!box64.getPath().startsWith(this.root.getPath() + File.separator)
                || !wineserver.getPath().startsWith(this.root.getPath() + File.separator))
            throw new IOException("Wine shutdown executable is outside the runtime");
        for (String name : variables) environment.put(name, variables.get(name));
        // Capture the target inode before a later container activation can retarget .wine.
        File prefix = new File(variables.get("WINEPREFIX")).getCanonicalFile();
        if (!variables.has("WINEPREFIX") || !prefix.isDirectory()
                || !prefix.getPath().startsWith(this.root.getPath() + File.separator))
            throw new IOException("Cannot verify the private PD2 Wine prefix");
        environment.put("WINEPREFIX", prefix.getPath());
        environment.put("WINEDEBUG", "-all");
        this.runner = runner;
    }

    /** Called on the environment worker before any new Windows process is started. */
    public Result beforeLaunch() {
        synchronized (LOCK) {
            if (stopped != null || prepared)
                return new Result("beforeLaunch", -1, -1, false, 0, "This Wine session already launched or stopped");
            if (active != null && active != this)
                return new Result("beforeLaunch", -1, -1, false, 0, "Previous PD2 Wine session is still stopping");
            Result result = cleanup("beforeLaunch");
            if (result.passed) { prepared = true; active = this; }
            return result;
        }
    }

    /** Idempotent: a delayed old activity cannot shut down the replacement session. */
    public Result stop() {
        synchronized (LOCK) {
            if (stopped != null) return stopped;
            if (!prepared || active != this)
                return new Result("stopSkipped", -1, -1, false, 0, "This session does not own the running prefix");
            stopped = cleanup("afterStop");
            active = null;
            return stopped;
        }
    }

    private Result cleanup(String phase) {
        long started = System.nanoTime();
        int kill = -1, wait = -1;
        boolean free = false;
        String error = "";
        String killOutput = "", waitOutput = "";
        JSONObject processes = null, ports = null;
        boolean escalated = false, clientsStopped = false;
        try {
            Pd2WineProcesses.Capture before = runner.captureProcesses(box64, environment.get("WINEPREFIX"));
            // Unlike -k9, explicit SIGINT enters Wine's server-side client termination path.
            // -k2 returns immediately; it does not use the default helper's 10.5s escalation loop.
            kill = runner.run(command("-k2"), environment, root);
            killOutput = runner.output();
            if (kill == 0 || kill == 1) {
                wait = runner.run(command("-w"), environment, root);
                waitOutput = runner.output();
                if (wait != 0) {
                    escalated = true;
                    int forced = runner.run(command("-k9"), environment, root);
                    killOutput = boundedTail(killOutput + "\n[Bounded server escalation]\n" + runner.output());
                    if (forced == 0 || forced == 1) {
                        wait = runner.run(command("-w"), environment, root);
                        waitOutput = boundedTail(waitOutput + "\n[After server escalation]\n" + runner.output());
                    }
                }
            }
            if ((kill == 0 || kill == 1) && wait == 0) {
                Pd2WineProcesses.Result residual = runner.finishProcesses(before);
                clientsStopped = residual.passed;
                processes = residual.json();
                // The server lock can disappear just before Unix HID clients release their sockets.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                do {
                    free = runner.portsFree();
                    if (free || System.nanoTime() >= deadline) break;
                    Thread.sleep(25);
                } while (true);
                ports = runner.portStatus();
            }
            if (!free) error = kill != 0 && kill != 1 ? "Wine shutdown command failed (status " + kill + ")"
                    : wait != 0 ? "Wine shutdown wait failed (status " + wait + ")"
                    : "Controller ports 7949/7950 are still occupied after Wine shutdown";
            else if (!clientsStopped) error = "Cannot verify that the captured Wine client processes have stopped";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            error = "Wine shutdown was interrupted";
        } catch (IOException failure) {
            error = "Wine shutdown could not run: " + failure.getClass().getSimpleName();
        } catch (JSONException failure) {
            error = "Wine shutdown diagnostics could not be recorded";
        }
        return new Result(phase, kill, wait, free, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), error,
                killOutput, waitOutput, processes, ports, escalated);
    }

    private static String boundedTail(String output) {
        return output.substring(Math.max(0, output.length() - 4096));
    }

    private List<String> command(String option) {
        List<String> command = new ArrayList<>();
        command.add(box64.getPath()); command.add(wineserver.getPath()); command.add(option);
        return command;
    }

    private static final class ProcessRunner implements Runner {
        private String lastOutput = "";
        private Pd2WineProcesses processes;
        private Boolean xinputFree, hidFree;
        public String output() { return lastOutput; }

        public Pd2WineProcesses.Capture captureProcesses(File box64, String prefix) {
            processes = new Pd2WineProcesses(Pd2WineProcesses.proc(new File("/proc"), pid -> {
                try { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL); }
                catch (android.system.ErrnoException error) {
                    if (error.errno != android.system.OsConstants.ESRCH) throw new IOException("Verified client signal failed", error);
                }
            }, directory -> {
                try { return android.system.Os.stat(directory.getPath()).st_uid; }
                catch (android.system.ErrnoException error) {
                    if (error.errno == android.system.OsConstants.ENOENT)
                        throw new java.nio.file.NoSuchFileException(directory.getPath());
                    throw new IOException("Process directory ownership unavailable", error);
                }
            }), android.os.Process.myUid(), android.os.Process.myPid(), box64.getPath(), prefix);
            return processes.capture();
        }

        public Pd2WineProcesses.Result finishProcesses(Pd2WineProcesses.Capture before) throws InterruptedException {
            return processes.finish(before);
        }

        public int run(List<String> command, Map<String, String> environment, File directory)
                throws IOException, InterruptedException {
            lastOutput = "";
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory)
                    .redirectErrorStream(true);
            builder.environment().putAll(environment);
            Process process = builder.start();
            BoundedOutput output = new BoundedOutput();
            Thread drain = new Thread(() -> output.read(process.getInputStream()), "pd2-wine-cleanup-output");
            drain.setDaemon(true);
            drain.start();
            try {
                return process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS) ? process.exitValue() : -2;
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                try { drain.join(250); }
                finally {
                    lastOutput = output.text();
                    try { process.getInputStream().close(); } catch (IOException ignored) { }
                }
            }
        }

        public boolean portsFree() throws IOException {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            // No SO_REUSEADDR: another receiver must not coexist with the fresh game.
            xinputFree = null; hidFree = null;
            try (DatagramSocket xinput = new DatagramSocket(null); DatagramSocket hid = new DatagramSocket(null)) {
                xinputFree = portFree(xinput, loopback, 7949);
                hidFree = portFree(hid, loopback, 7950);
                return xinputFree && hidFree;
            }
        }

        private boolean portFree(DatagramSocket socket, InetAddress loopback, int port) throws IOException {
            try {
                socket.bind(new InetSocketAddress(loopback, port));
                return true;
            } catch (java.net.BindException busy) { return false; }
        }

        public JSONObject portStatus() throws JSONException {
            return new JSONObject().put("7949Free", xinputFree == null ? JSONObject.NULL : xinputFree)
                    .put("7950Free", hidFree == null ? JSONObject.NULL : hidFree)
                    .put("scope", "Independent exclusive UDP binds, no SO_REUSEADDR; binding does not establish Windows device enumeration");
        }
    }

    /** Drain all helper output to avoid pipe deadlocks while retaining only a bounded diagnostic tail. */
    private static final class BoundedOutput {
        private final byte[] tail = new byte[4096];
        private int next, size;

        void read(InputStream input) {
            byte[] buffer = new byte[1024];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) append(buffer, count);
            } catch (IOException ignored) { }
        }

        private synchronized void append(byte[] bytes, int count) {
            for (int i = 0; i < count; i++) {
                tail[next] = bytes[i]; next = (next + 1) % tail.length;
                if (size < tail.length) size++;
            }
        }

        synchronized String text() {
            byte[] bytes = new byte[size];
            int start = (next - size + tail.length) % tail.length;
            for (int i = 0; i < size; i++) bytes[i] = tail[(start + i) % tail.length];
            return new String(bytes, StandardCharsets.UTF_8).replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        }
    }
}
