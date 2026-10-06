package com.winlator.pd2;

import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.core.FileUtils;
import com.winlator.core.WineInfo;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A focused PD2 launcher; the embedded runtime is app-private and independent of Winlator. */
public final class Pd2Activity extends AppCompatActivity {
    private static final int IMPORT_FOLDER = 101, IMPORT_ZIP = 102;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static volatile boolean busy;
    private static volatile String operation = "Choose a complete Diablo II + Lord of Destruction installation with ProjectD2.";
    private static volatile Pd2InstallValidator.Result installation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private TextView status;
    private Button play, prepare, folder, zip, settings, stop;
    private static volatile boolean runtimePreparing;
    private static volatile boolean containerPreparing;
    private long lastStickNavigation;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { updateUi(); handler.postDelayed(this, 500); }
    };

    public static boolean isOperationInProgress() {
        return busy || runtimePreparing || containerPreparing || Pd2ContainerMaintenance.isContainerWorkInProgress();
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Pd2CrashLog.hasPending(this)) {
            startActivity(new Intent(this, Pd2RecoveryActivity.class));
            finish();
            return;
        }
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!preferences.contains("pd2_defaults")) {
            File logs = new File(getFilesDir(), "pd2/logs");
            logs.mkdirs();
            preferences.edit().putBoolean("pd2_defaults", true)
                .putBoolean("enable_background_protection", true)
                .putBoolean("enable_background_wakelock", true)
                .putBoolean("save_logs_to_file", true).putInt("box64_logs", 1)
                .putString("log_file", new File(logs, "runtime.log").getPath())
                .putString("pd2_renderer", "turnip,zink").putString("pd2_arguments", "-3dfx -w").apply();
        }
        buildUi();
        if (!busy) runOperation("Checking installation", false, () -> {
            Pd2Installer.recover(getApplicationContext());
            installation = Pd2InstallValidator.validate(Pd2Installer.installedDirectory(getApplicationContext()));
            operation = getIntent().hasExtra("pd2_launch_failure")
                    ? launchFailureMessage(getIntent().getStringExtra("pd2_launch_failure"))
                    : getIntent().hasExtra("pd2_runtime_exit_status")
                    ? runtimeExitMessage(getIntent().getIntExtra("pd2_runtime_exit_status", 0))
                    : installation.valid ? "Installation ready. Prepare the runtime, then Play." : installation.message;
        });
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(23, 15, 15));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(26), dp(18), dp(26), dp(18));
        scroll.addView(layout);
        TextView title = new TextView(this);
        title.setText("PROJECT DIABLO II");
        title.setTextColor(Color.rgb(241, 202, 129));
        title.setTextSize(27); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        layout.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Android launcher · Preview " + appVersion());
        subtitle.setTextColor(Color.rgb(190, 164, 148)); subtitle.setTextSize(14);
        layout.addView(subtitle);
        status = new TextView(this);
        status.setTextColor(Color.rgb(234, 225, 207)); status.setTextSize(16);
        status.setPadding(0, dp(16), 0, dp(12));
        layout.addView(status);
        LinearLayout row = row(layout);
        play = button(row, "Play", this::play);
        stop = button(row, "Stop client", () -> Pd2ControllerDialogs.enable(new AlertDialog.Builder(this)
            .setTitle("Stop client?").setMessage("Save and exit your game first. This stops the running Windows client.")
            .setNegativeButton("Cancel", null).setPositiveButton("Stop", (d, w) -> XServerDisplayActivity.stopPd2Session(this)).show()));
        row = row(layout);
        prepare = button(row, "Prepare runtime", this::prepareRuntime);
        settings = button(row, "Launch settings", this::showSettings);
        row = row(layout);
        folder = button(row, "Import installation folder", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, IMPORT_FOLDER);
        });
        zip = button(row, "Import installation ZIP", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, IMPORT_ZIP);
        });
        row = row(layout);
        button(row, "Export support logs", this::exportSupport);
        button(row, "Advanced runtime settings", () -> startActivity(new Intent(this, MainActivity.class)));
        TextView help = new TextView(this);
        help.setText("Import the whole Diablo II folder, including the base MPQ files and ProjectD2. Your original files stay in place.\n\nIn game: use the gear button or press L3 + R3 together to switch between PD2 controller controls and mouse/keyboard controls.\n\nRuntime and game files are stored privately by this app. Winlator or GameNative does not need to be installed.");
        help.setTextColor(Color.rgb(190, 175, 156)); help.setTextSize(14);
        help.setPadding(0, dp(16), 0, 0); layout.addView(help);
        setContentView(scroll);
        play.requestFocus();
    }
    private LinearLayout row(LinearLayout layout) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }
    private Button button(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false);
        button.setTextSize(15); button.setTextColor(Color.rgb(246, 224, 181));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(58, 29, 24)); background.setCornerRadius(dp(8));
        background.setStroke(dp(1), Color.rgb(139, 92, 51));
        button.setBackground(background);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setFocusable(true);
        button.setOnFocusChangeListener((v, focused) -> {
            background.setStroke(dp(focused ? 3 : 1), Color.rgb(focused ? 241 : 139, focused ? 202 : 92, focused ? 129 : 51));
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(58), 1);
        params.setMargins(dp(4), dp(5), dp(4), dp(5)); row.addView(button, params);
        button.setOnClickListener(v -> action.run()); return button;
    }
    @Override protected void onResume() { super.onResume(); if (status != null) handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }

    private void updateUi() {
        boolean runtime = Pd2Runtime.hasRuntime(this);
        if (runtime) runtimePreparing = false;
        boolean session = XServerDisplayActivity.hasPd2Session();
        boolean locked = isOperationInProgress() || Pd2ContainerMaintenance.isDeletionInProgress()
                || (!session && XServerDisplayActivity.isPd2RuntimeWorkInProgress());
        play.setText(session ? "Resume client" : "Play");
        play.setEnabled(!locked && (session || (runtime && installation != null && installation.valid)));
        stop.setEnabled(session && !locked);
        folder.setEnabled(!locked && !session); zip.setEnabled(!locked && !session);
        settings.setEnabled(!locked && !session); prepare.setEnabled(!locked && !session && !runtime);
        status.setText((session ? "Client running" : runtime ? "Runtime ready" : "Runtime not prepared") + "\n" +
            (Pd2ContainerMaintenance.isDeletionInProgress() ? "Deleting an older container…"
                    : runtimePreparing ? "Preparing bundled runtime files…" : containerPreparing ? "Preparing the PD2 Wine container…" : operation));
    }
    private void prepareRuntime() {
        synchronized (Pd2ContainerMaintenance.class) {
            if (isOperationInProgress() || Pd2ContainerMaintenance.isDeletionInProgress()
                    || XServerDisplayActivity.isPd2RuntimeWorkInProgress()) return;
            runtimePreparing = true;
        }
        updateUi();
        RootFSInstaller.installIfNeeded(this);
        // A failed extractor can be retried when its progress dialog closes.
        handler.postDelayed(() -> {
            if (!Pd2Runtime.hasRuntime(this)) {
                runtimePreparing = false;
                operation = "Runtime preparation did not finish. Check free space and retry Prepare runtime.";
                updateUi();
            }
        }, 5 * 60 * 1000L);
    }
    private void play() {
        if (Pd2ContainerMaintenance.isDeletionInProgress()) return;
        if (XServerDisplayActivity.resumePd2Session(this)) return;
        synchronized (Pd2ContainerMaintenance.class) {
            if (isOperationInProgress() || Pd2ContainerMaintenance.isDeletionInProgress()
                    || XServerDisplayActivity.isPd2RuntimeWorkInProgress()
                    || !Pd2Runtime.hasRuntime(this) || installation == null || !installation.valid) return;
            containerPreparing = true;
        }
        updateUi();
        Pd2Runtime.getOrCreateContainer(this, container -> {
            if (container == null) { containerPreparing = false; operation = "Cannot prepare the Wine container. Check free storage and retry."; updateUi(); return; }
            try {
                Pd2Runtime.configure(this, container, preferences.getString("pd2_renderer", "turnip,zink"));
                File game = new File(Pd2Installer.installedDirectory(this), installation.gameExecutableRelativePath);
                if (!game.isFile()) throw new IOException("Imported Game.exe is missing; import your installation again.");
                String arguments = preferences.getString("pd2_arguments", "-3dfx -w");
                String launchId = Pd2LaunchDiagnostics.begin(this, container, arguments);
                Pd2ControllerDiagnostics.clearPreviousReport(this);
                appendLauncherLog("Launching PD2; id=" + launchId + "; renderer=" + container.getGraphicsDriver()
                        + "; arguments=" + arguments + "; CPU=" + (preferences.getBoolean(Pd2LaunchPolicy.CPU_PREFERENCE, false) ? "interpreter" : "stability"));
                Intent intent = new Intent(this, XServerDisplayActivity.class)
                    .putExtra("pd2_session", true).putExtra("container_id", container.id)
                    .putExtra("pd2_launch_id", launchId).putExtra("exec_path", game.getPath()).putExtra("exec_args", arguments);
                XServerDisplayActivity.setPd2LaunchPending(true);
                startActivity(intent);
            } catch (Exception e) {
                XServerDisplayActivity.setPd2LaunchPending(false);
                operation = "Launch setup failed: " + e.getMessage(); appendLauncherLog(operation);
            } finally { containerPreparing = false; }
            updateUi();
        });
    }
    private void showSettings() {
        String[] choices = {"Turnip + Zink · D2GL / Glide", "Turnip + Zink · Wine DirectDraw (compatibility)", "Turnip + VirGL · D2GL / Glide", "Turnip + VirGL · Wine DirectDraw (compatibility)", "Turnip + Zink · Glide (GameNative arguments)"};
        String renderer = preferences.getString("pd2_renderer", "turnip,zink");
        String args = preferences.getString("pd2_arguments", "-3dfx -w");
        int selected = Pd2LaunchPolicy.GAMENATIVE_ARGUMENTS.equals(args) && renderer.equals("turnip,zink")
                ? 4 : (renderer.equals("turnip,virgl") ? 2 : 0) + (args.contains("-ddraw") ? 1 : 0);
        Pd2ControllerDialogs.enable(new AlertDialog.Builder(this).setTitle("Launch settings").setSingleChoiceItems(choices, selected, (d, which) -> {
            preferences.edit().putString("pd2_renderer", which == 2 || which == 3 ? "turnip,virgl" : "turnip,zink")
                .putString("pd2_arguments", which == 4 ? Pd2LaunchPolicy.GAMENATIVE_ARGUMENTS
                        : which % 2 == 0 ? "-3dfx -w" : "-ddraw -w").apply();
            operation = which == 4 ? "Glide with your GameNative arguments selected."
                    : "Launch profile saved.";
            d.dismiss(); updateUi();
        }).setNeutralButton("CPU mode", (d, w) -> showCpuSettings())
          .setPositiveButton("Controller", (d, w) -> showControllerSettings()).setNegativeButton("Close", null).show());
    }
    private void showControllerSettings() {
        String[] modes = {"Controller notifications enabled (default)", "Controller notifications disabled"};
        int selected = Pd2ControllerRuntime.enabled(this) ? 0 : 1;
        Pd2ControllerDialogs.enable(new AlertDialog.Builder(this).setTitle("Native controller")
            .setSingleChoiceItems(modes, selected, (d, which) -> {
                preferences.edit().putBoolean(Pd2ControllerRuntime.ENABLED_PREFERENCE, which == 0).apply();
                operation = "Controller setting saved. Restart the app before launching the game to apply it.";
                d.dismiss(); updateUi();
            }).setNegativeButton("Close", null).show());
    }
    private void showCpuSettings() {
        String[] modes = {"Stability (default)", "Interpreter (diagnostic; very slow)"};
        int selected = preferences.getBoolean(Pd2LaunchPolicy.CPU_PREFERENCE, false) ? 1 : 0;
        Pd2ControllerDialogs.enable(new AlertDialog.Builder(this).setTitle("CPU mode")
            .setSingleChoiceItems(modes, selected, (d, which) -> {
                preferences.edit().putBoolean(Pd2LaunchPolicy.CPU_PREFERENCE, which == 1).apply();
                operation = which == 1 ? "Interpreter selected for a short startup diagnostic. Return to Stability afterward."
                        : "Stability selected.";
                d.dismiss(); updateUi();
            }).setNegativeButton("Close", null).show());
    }
    public static void recordRuntimeExit(android.content.Context context, int status) {
        operation = runtimeExitMessage(status);
        appendLauncherLog(context, operation);
    }
    public static void recordLaunchFailure(android.content.Context context, String message) {
        operation = launchFailureMessage(message);
        appendLauncherLog(context, operation);
    }
    private static String launchFailureMessage(String message) {
        return "Client launch stopped: " + (message == null ? "runtime startup failed" : message)
                + ". Export support logs.";
    }
    private static String runtimeExitMessage(int status) {
        return "Windows runtime exited (status " + status + "). If the title screen did not appear, export support logs.";
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || (request != IMPORT_FOLDER && request != IMPORT_ZIP)) return;
        if (isOperationInProgress() || Pd2ContainerMaintenance.isDeletionInProgress()
                || XServerDisplayActivity.isPd2RuntimeWorkInProgress()) { operation = "Finish runtime work before importing."; return; }
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
        runOperation("Importing your installation", () -> {
            Pd2Installer.Progress progress = (name, bytes, files) -> operation = "Importing · " + files + " files · " + (bytes / 1048576) + " MB\n" + name;
            installation = request == IMPORT_FOLDER ? Pd2Installer.importFolder(getApplicationContext(), uri, progress) : Pd2Installer.importZip(getApplicationContext(), uri, progress);
            operation = "Installation ready. " + installation.details;
            appendLauncherLog("Import completed: " + installation.details);
        });
    }
    private interface Job { void run() throws Exception; }
    private void runOperation(String title, Job job) {
        runOperation(title, true, job);
    }
    private void runOperation(String title, boolean protect, Job job) {
        synchronized (Pd2ContainerMaintenance.class) {
            if (busy || Pd2ContainerMaintenance.isDeletionInProgress()) return;
            busy = true;
        }
        operation = title;
        Runnable execute = () -> WORKER.execute(() -> {
            try { job.run(); }
            catch (Exception e) { operation = title + " failed: " + e.getMessage(); appendLauncherLog(operation); }
            finally { busy = false; if (protect) Pd2WorkService.stop(getApplicationContext()); }
        });
        // Automatic validation needs no service. Explicit jobs wait until the
        // service has entered foreground before they can finish and stop it.
        try { if (protect) Pd2WorkService.start(getApplicationContext(), execute); else execute.run(); }
        catch (Exception e) { busy = false; operation = "Cannot start " + title.toLowerCase(Locale.US) + ": " + e.getMessage(); appendLauncherLog(operation); }
    }
    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return "unknown"; }
    }
    private void appendLauncherLog(String text) {
        appendLauncherLog(this, text);
    }
    public static void appendLauncherLog(android.content.Context context, String text) {
        try {
            File file = new File(context.getFilesDir(), "pd2/logs/launcher.log"); file.getParentFile().mkdirs();
            if (file.length() > 1024 * 1024) file.delete();
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((new Date() + " " + text + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { }
    }
    private void exportSupport() {
        if (busy) { operation = "Finish the current operation before exporting logs."; return; }
        runOperation("Exporting support logs", () -> {
            XServerDisplayActivity.savePd2ControllerDiagnostics();
            File directory = new File(getFilesDir(), "pd2/exports"); directory.mkdirs();
            File[] old = directory.listFiles();
            if (old != null) for (File file : old) if (file.getName().startsWith("pd2-support-") && file.lastModified() < System.currentTimeMillis() - 2 * 86400000L) file.delete();
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File archive = new File(directory, "pd2-support-" + stamp + ".zip");
            JSONObject report = new JSONObject().put("appVersion", appVersion()).put("android", Build.VERSION.RELEASE)
                .put("device", Build.MANUFACTURER + " " + Build.MODEL).put("supportedAbis", new JSONArray(Build.SUPPORTED_ABIS))
                .put("runtimePrepared", Pd2Runtime.hasRuntime(this)).put("sessionRunning", XServerDisplayActivity.hasPd2Session())
                .put("wineVersion", WineInfo.MAIN_WINE_INFO.identifier())
                .put("runtimeRevision", Pd2Runtime.RUNTIME_REVISION)
                .put("rootfsVersion", RootFS.find(this).getVersion())
                .put("renderer", preferences.getString("pd2_renderer", "turnip,zink"))
                .put("inputMode", preferences.getBoolean("pd2_mouse_keyboard", false) ? "mouse_keyboard" : "native")
                .put("inputModeScope", "Saved gameplay preference; current session mode and transitions are in controller.json")
                .put("controllerRuntime", Pd2ControllerRuntime.status(this))
                .put("arguments", preferences.getString("pd2_arguments", "-3dfx -w"))
                .put("cpuMode", preferences.getBoolean(Pd2LaunchPolicy.CPU_PREFERENCE, false) ? "interpreter" : "stability")
                .put("installation", installation == null ? "unchecked" : installation.details).put("lastOperation", operation);
            if (Build.VERSION.SDK_INT >= 30) {
                ActivityManager manager = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
                Pd2AndroidExitDiagnostics.appendTo(report, manager, getPackageName());
            }
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(archive))) {
                out.putNextEntry(new ZipEntry("support.json")); out.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); out.closeEntry();
                zipLog(out, "launcher.log", new File(getFilesDir(), "pd2/logs/launcher.log"));
                zipLog(out, "crash.txt", Pd2CrashLog.getCrashFile(this));
                File runtimeLog = new File(getFilesDir(), "pd2/logs/runtime.log");
                if (runtimeLog.isFile()) {
                    out.putNextEntry(new ZipEntry("runtime.log"));
                    Pd2SessionLog.writeSnapshot(runtimeLog, out);
                    out.closeEntry();
                }
                zipLog(out, "launch.json", new File(getFilesDir(), "pd2/logs/launch.json"));
                zipControllerDiagnostics(out, Pd2ControllerDiagnostics.getFile(this),
                        new File(getFilesDir(), "pd2/logs/launch.json"));
                zipSessionDiagnostics(out, "memory.json", Pd2MemoryDiagnostics.getFile(this),
                        new File(getFilesDir(), "pd2/logs/launch.json"), Pd2MemoryDiagnostics.MAX_REPORT_BYTES);
                Pd2AttemptReports.exportArchived(getFilesDir(), out);
                if (installation != null && installation.valid) {
                    out.putNextEntry(new ZipEntry("installation-files.json"));
                    out.write(Pd2LaunchDiagnostics.installationFiles(Pd2Installer.installedDirectory(this), installation)
                            .toString(2).getBytes(StandardCharsets.UTF_8));
                    out.closeEntry();
                    Pd2LaunchDiagnostics.exportGameLogs(Pd2Installer.installedDirectory(this), installation, out);
                }
            }
            operation = "Support logs ready.";
            runOnUiThread(() -> {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".FileProvider", archive);
                Intent share = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(share, "Save or share PD2 support logs"));
            });
        });
    }
    private static void zipLog(ZipOutputStream out, String name, File file) throws IOException {
        if (!file.isFile()) return;
        out.putNextEntry(new ZipEntry(name));
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long start = Math.max(0, in.length() - 4 * 1024 * 1024L); in.seek(start);
            byte[] buffer = new byte[32768]; int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        out.closeEntry();
    }
    private static void zipControllerDiagnostics(ZipOutputStream out, File file, File launch) throws IOException {
        zipSessionDiagnostics(out, "controller.json", file, launch, Pd2ControllerDiagnostics.MAX_REPORT_BYTES);
    }
    static void zipSessionDiagnostics(ZipOutputStream out, String name, File file, File launch, int limit) throws IOException {
        byte[] bytes = readBoundedReport(file, limit);
        byte[] launchBytes = readBoundedReport(launch, 128 * 1024);
        if (bytes == null || launchBytes == null) return;
        try {
            String launchId = new JSONObject(new String(launchBytes, StandardCharsets.UTF_8)).optString("launchId");
            if (launchId.isEmpty() || !launchId.equals(new JSONObject(new String(bytes, StandardCharsets.UTF_8)).optString("launchId"))) return;
        }
        catch (org.json.JSONException invalidReport) { return; }
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
    private static byte[] readBoundedReport(File file, int limit) throws IOException {
        if (!file.isFile() || file.length() > limit) return null;
        try (FileInputStream in = new FileInputStream(file);
             java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = in.read(buffer)) != -1) {
                if (data.size() + read > limit) return null;
                data.write(buffer, 0, read);
            }
            return data.toByteArray();
        }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A && event.getAction() == KeyEvent.ACTION_UP) {
            View focus = getCurrentFocus(); if (focus != null) focus.performClick(); return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A) return true;
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK) {
            float x = event.getAxisValue(MotionEvent.AXIS_X), y = event.getAxisValue(MotionEvent.AXIS_Y);
            long now = android.os.SystemClock.uptimeMillis();
            if (Math.max(Math.abs(x), Math.abs(y)) > .65f && now - lastStickNavigation > 220) {
                lastStickNavigation = now;
                int direction = Math.abs(x) > Math.abs(y) ? (x < 0 ? View.FOCUS_LEFT : View.FOCUS_RIGHT) : (y < 0 ? View.FOCUS_UP : View.FOCUS_DOWN);
                View focus = getCurrentFocus();
                if (focus != null) { View next = focus.focusSearch(direction); if (next != null) next.requestFocus(); }
            }
            return true;
        }
        return super.onGenericMotionEvent(event);
    }
}
