package com.winlator.xenvironment.components;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import androidx.preference.PreferenceManager;

import com.winlator.box64.Box64Preset;
import com.winlator.box64.Box64PresetManager;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.LocaleHelper;
import com.winlator.core.ProcessHelper;
import com.winlator.widget.LogView;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.RootFS;
import com.winlator.pd2.Pd2WineSession;
import com.winlator.pd2.Pd2LaunchDiagnostics;
import com.winlator.pd2.Pd2Activity;
import com.winlator.pd2.Pd2HidReadRuntime;
import com.winlator.pd2.Pd2LaunchPolicy;

import java.io.File;
import java.util.List;

public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private String guestExecutable;
    private int pid = -1;
    private long processGeneration;
    private String pd2LaunchId;
    private Pd2WineSession pd2WineSession;
    private EnvVars launchEnvironment;
    private boolean pd2CleanupRecorded;
    private String pd2LaunchFailure;
    private EnvVars envVars;
    private String box64Preset = Box64Preset.CONSERVATIVE;
    private Callback<Integer> terminationCallback;
    private final Object lock = new Object();

    public void setPd2LaunchId(String id) { pd2LaunchId = id; }
    public String getPd2LaunchFailure() { return pd2LaunchFailure; }

    private void recordLaunchFailure(String message) {
        pd2LaunchFailure = message;
        Pd2LaunchDiagnostics.failed(environment.getContext(), pd2LaunchId, message);
        Pd2Activity.recordLaunchFailure(environment.getContext(), message);
    }

    /** Worker-thread barrier, before root/tmp is cleared or runtime sockets start. */
    public boolean preparePd2Session() {
        synchronized (lock) {
            try {
                extractBox64File();
                copyDefaultBox64RCFile();
                launchEnvironment = createLaunchEnvironment();
                launchEnvironment.put("WINEPREFIX", new File(launchEnvironment.get("WINEPREFIX")).getCanonicalPath());
                RootFS rootFS = environment.getRootFS();
                pd2WineSession = new Pd2WineSession(rootFS.getRootDir(), rootFS.getWinePath(), launchEnvironment);
                Pd2WineSession.Result result = pd2WineSession.beforeLaunch();
                recordCleanup(result);
                if (!result.passed) { recordLaunchFailure(result.error); return false; }
                try {
                    // The environment lock excludes replacement startup; cleanup proves the old
                    // guest is gone before changing a driver. Never patch during Save/Quit.
                    boolean hidReadApplied = Pd2HidReadRuntime.prepareAfterCleanup(environment.getContext(), result);
                    launchEnvironment.put("WINEDLLOVERRIDES", Pd2LaunchPolicy.hidReadOverrides(
                            launchEnvironment.get("WINEDLLOVERRIDES"), hidReadApplied));
                    org.json.JSONObject hidReadStatus = Pd2HidReadRuntime.status(environment.getContext());
                    try { hidReadStatus.put("appliedForLaunch", hidReadApplied); }
                    catch (org.json.JSONException ignored) { }
                    Pd2LaunchDiagnostics.hidReadRuntime(environment.getContext(), pd2LaunchId, hidReadStatus);
                    return true;
                } catch (java.io.IOException | RuntimeException error) {
                    recordLaunchFailure("Cannot apply the HID read experiment: " + error.getMessage());
                    // A prepared session owns the global lease even before guest startup.
                    // Release it on installation failure so the next clean Play is possible.
                    recordCleanup(pd2WineSession.stop());
                    pd2CleanupRecorded = true;
                    return false;
                }
            } catch (java.io.IOException error) {
                recordLaunchFailure("Cannot verify PD2 Wine shutdown: " + error.getMessage());
                return false;
            }
        }
    }

    private void recordCleanup(Pd2WineSession.Result result) {
        Context context = environment.getContext();
        try { Pd2LaunchDiagnostics.wineCleanup(context, pd2LaunchId, result.json()); }
        catch (org.json.JSONException ignored) { }
        Pd2Activity.appendLauncherLog(context, "PD2 Wine cleanup " + result.phase + ": passed=" + result.passed
                + "; kill=" + result.killStatus + "; wait=" + result.waitStatus
                + "; portsFree=" + result.portsFree + "; elapsedMs=" + result.elapsedMillis);
    }

    @Override
    public void start() {
        synchronized (lock) {
            killRootProcess();
            extractBox64File();
            copyDefaultBox64RCFile();
            if (pd2LaunchId != null) {
                try { Pd2WineSession.prepareLaunchDirectories(environment.getRootFS().getRootDir()); }
                catch (java.io.IOException failure) {
                    recordLaunchFailure(failure.getMessage());
                    if (terminationCallback != null) terminationCallback.call(-1);
                    return;
                }
            }
            final long generation = ++processGeneration;
            pid = execGuestProgram(generation);
            if (pid == -1 && terminationCallback != null) terminationCallback.call(-1);
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            processGeneration++;
            if (pd2WineSession != null) {
                try {
                    Pd2WineSession.Result result = pd2WineSession.stop();
                    if (!pd2CleanupRecorded) { recordCleanup(result); pd2CleanupRecorded = true; }
                } finally {
                    // Scoped cleanup verifies client identity. A saved numeric root PID may already
                    // have exited/reused while its callback waited for this lock; never kill it here.
                    pid = -1;
                }
            }
            else killRootProcess();
        }
    }

    private void killRootProcess() {
        if (pid != -1) { Process.killProcess(pid); pid = -1; }
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    private EnvVars createLaunchEnvironment() {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();

        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars);
        LocaleHelper.setEnvVars(envVars);

        envVars.put("HOME", rootDir+RootFS.HOME_PATH);
        envVars.put("USER", RootFS.USER);
        envVars.put("TMPDIR", rootDir+"/tmp");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", rootDir+rootFS.getWinePath()+"/bin:"+rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");
        envVars.put("LD_LIBRARY_PATH", rootFS.getLibDir().getPath());
        envVars.put("BOX64_LD_LIBRARY_PATH", rootDir+"/lib/x86_64-linux-gnu");
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);

        if (this.envVars != null) envVars.putAll(this.envVars);

        File shmDir = new File(rootDir, "/tmp/shm");
        if (!shmDir.isDirectory()) shmDir.mkdirs();

        return envVars;
    }

    private int execGuestProgram(long generation) {
        RootFS rootFS = environment.getRootFS();
        File rootDir = rootFS.getRootDir();
        EnvVars effective = launchEnvironment != null ? launchEnvironment : createLaunchEnvironment();
        String command = rootDir+"/usr/local/bin/box64 "+guestExecutable;

        return ProcessHelper.exec(command, effective, rootDir, (status) -> {
            synchronized (lock) {
                if (generation != processGeneration) return;
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        });
    }

    private void extractBox64File() {
        Context context = environment.getContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String box64Version = preferences.getString("box64_version", DefaultVersion.BOX64);
        String currentBox64Version = preferences.getString("current_box64_version", "");

        if (!box64Version.equals(currentBox64Version)) {
            GeneralComponents.extractFile(GeneralComponents.Type.BOX64, context, box64Version, DefaultVersion.BOX64);
            preferences.edit().putString("current_box64_version", box64Version).apply();
        }
    }

    private void copyDefaultBox64RCFile() {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        FileUtils.copy(context, "box64/default.box64rc", new File(rootFS.getRootDir(), "/etc/config.box64rc"));
    }

    private void addBox64EnvVars(EnvVars envVars) {
        Context context = environment.getContext();
        RootFS rootFS = environment.getRootFS();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        int box64Logs = preferences.getInt("box64_logs", 0);
        boolean saveToFile = preferences.getBoolean("save_logs_to_file", false);

        envVars.put("BOX64_NOBANNER", box64Logs >= 1 ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");
        envVars.put("BOX64_UNITYPLAYER", "0");
        envVars.put("BOX64_DYNACACHE", "0");

        if (box64Logs >= 1) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");

            if (box64Logs == 2) {
                envVars.put("BOX64_SHOWSEGV", "1");
                envVars.put("BOX64_DLSYM_ERROR", "1");
                envVars.put("BOX64_TRACE_FILE", "stderr");

                if (saveToFile) {
                    File parent = (new File(preferences.getString("log_file", LogView.getLogFile().getPath()))).getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        File traceDir = new File(parent, "trace");
                        if (!traceDir.isDirectory()) traceDir.mkdirs();
                        FileUtils.clear(traceDir);

                        envVars.put("BOX64_TRACE_FILE", traceDir+"/box64-%pid.txt");
                    }
                }
            }
        }

        envVars.putAll(Box64PresetManager.getEnvVars(context, box64Preset));

        File box64RCFile = new File(rootFS.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    @Override
    public void onPause() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = processes.size()-1; i >= 0; i--) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state != ProcessHelper.PState.STOPPED) {
                        ProcessHelper.suspendProcess(process.pid);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        synchronized (lock) {
            if (pid != -1) {
                List<ProcessHelper.PStat> processes = ProcessHelper.getChildProcesses();
                for (int i = 0; i < processes.size(); i++) {
                    ProcessHelper.PStat process = processes.get(i);
                    if (process.guestProcess && process.state == ProcessHelper.PState.STOPPED) {
                        ProcessHelper.resumeProcess(process.pid);
                    }
                }
            }
        }
    }
}
