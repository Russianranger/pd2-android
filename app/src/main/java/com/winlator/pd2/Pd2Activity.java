package com.winlator.pd2;

import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
    private Button play, prepare, folder, zip, settings, stop, performance;
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
        setTheme(R.style.Pd2LauncherTheme);
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
        scroll.setBackgroundResource(R.drawable.pd2_launcher_background);
        scroll.setClipToPadding(false);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(16), dp(18), dp(20));
        scroll.addView(layout);

        LinearLayout hero = new LinearLayout(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(6), 0, dp(6), dp(14));
        ImageView crest = new ImageView(this);
        crest.setImageResource(R.drawable.pd2_launcher_crest);
        crest.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hero.addView(crest, new LinearLayout.LayoutParams(dp(48), dp(64)));
        LinearLayout wordmark = new LinearLayout(this);
        wordmark.setOrientation(LinearLayout.VERTICAL);
        wordmark.setPadding(dp(14), 0, 0, 0);
        TextView title = Pd2LauncherTheme.heading(this, "PROJECT DIABLO II", 25);
        title.setMaxLines(2);
        wordmark.addView(title);
        TextView subtitle = Pd2LauncherTheme.body(this, "Android launcher · Preview " + appVersion(), 12);
        subtitle.setPadding(0, dp(4), 0, 0);
        wordmark.addView(subtitle);
        hero.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        layout.addView(columns, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout session = section(columns, "Your journey", wide);
        status = Pd2LauncherTheme.body(this, "", 14);
        status.setTextColor(Pd2LauncherTheme.BONE);
        status.setPadding(0, dp(8), 0, dp(10));
        session.addView(status);
        LinearLayout row = row(session, false);
        play = button(row, "Play", this::play);
        Pd2LauncherTheme.styleButton(play, true);
        stop = button(row, "Stop client", () -> Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this)
            .setTitle("Stop client?").setMessage("Save and exit your game first. This stops the running Windows client.")
            .setNegativeButton("Cancel", null).setPositiveButton("Stop", (d, w) -> XServerDisplayActivity.stopPd2Session(this)).show()));

        LinearLayout launch = section(columns, "Launch & display", wide);
        TextView launchHelp = Pd2LauncherTheme.body(this, "Prepare once. Keep your preferred launch settings.", 13);
        launchHelp.setPadding(0, dp(7), 0, dp(7));
        launch.addView(launchHelp);
        row = row(launch);
        prepare = button(row, "Prepare runtime", this::prepareRuntime);
        settings = button(row, "Launch settings", this::showSettings);
        performance = button(row(launch, true), "Performance & temperature", this::showPerformanceSettings);

        LinearLayout installationPanel = section(layout, "Your installation", false);
        TextView importHelp = Pd2LauncherTheme.body(this,
                "Import the complete Diablo II folder, including Lord of Destruction and ProjectD2. Your original files stay in place.", 13);
        importHelp.setPadding(0, dp(7), 0, dp(7));
        installationPanel.addView(importHelp);
        row = row(installationPanel);
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
        LinearLayout utilities = section(layout, "Tools & support", false);
        row = row(utilities);
        button(row, "Export support logs", this::exportSupport);
        button(row, "Advanced runtime settings", () -> startActivity(new Intent(this, MainActivity.class)));
        TextView help = Pd2LauncherTheme.body(this,
                "IN GAME  ·  Gear or L3 + R3 opens the quick menu for controller and mouse controls.\n"
                + "Your runtime and game files stay private to this app. Winlator or GameNative does not need to be installed.", 12);
        help.setPadding(dp(6), dp(6), dp(6), 0);
        layout.addView(help);
        setContentView(scroll);
        updateUi();
        if (!play.requestFocus() && !prepare.requestFocus()) settings.requestFocus();
    }

    private LinearLayout section(LinearLayout parent, String title, boolean weighted) {
        LinearLayout panel = Pd2LauncherTheme.panel(this);
        panel.addView(Pd2LauncherTheme.heading(this, title, 16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(weighted ? 0 : -1, -2, weighted ? 1 : 0);
        params.setMargins(dp(4), 0, dp(4), dp(12));
        parent.addView(panel, params);
        return panel;
    }

    private LinearLayout row(LinearLayout layout) {
        int screenWidth = getResources().getConfiguration().screenWidthDp;
        boolean stack = screenWidth < 480 || screenWidth >= 600 && screenWidth < 800
                || getResources().getConfiguration().fontScale > 1.15f;
        return row(layout, stack);
    }

    private LinearLayout row(LinearLayout layout, boolean stack) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, 0);
        layout.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }
    private Button button(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        Pd2LauncherTheme.styleButton(button, false);
        boolean stack = row.getOrientation() == LinearLayout.VERTICAL;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(stack ? -1 : 0, -2, stack ? 0 : 1);
        params.setMargins(dp(3), dp(4), dp(3), dp(4));
        row.addView(button, params);
        button.setOnClickListener(v -> action.run()); return button;
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (status != null) buildUi();
    }
    @Override protected void onResume() { super.onResume(); if (status != null) handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }

    private void updateUi() {
        boolean runtime = Pd2Runtime.hasRuntime(this);
        if (runtime) runtimePreparing = false;
        boolean session = XServerDisplayActivity.hasPd2Session();
        boolean locked = isOperationInProgress() || Pd2ContainerMaintenance.isDeletionInProgress()
                || (!session && XServerDisplayActivity.isPd2RuntimeWorkInProgress());
        String playLabel = session ? "Resume client" : "Play";
        if (!playLabel.contentEquals(play.getText())) play.setText(playLabel);
        play.setEnabled(!locked && (session || (runtime && installation != null && installation.valid)));
        stop.setEnabled(session && !locked);
        folder.setEnabled(!locked && !session); zip.setEnabled(!locked && !session);
        settings.setEnabled(!locked && !session); prepare.setEnabled(!locked && !session && !runtime);
        performance.setEnabled(!locked && !session);
        String message = (session ? "Client running" : runtime ? "Runtime ready" : "Runtime not prepared") + "\n" +
            (Pd2ContainerMaintenance.isDeletionInProgress() ? "Deleting an older container…"
                    : runtimePreparing ? "Preparing bundled runtime files…" : containerPreparing ? "Preparing the PD2 Wine container…" : operation);
        if (!message.contentEquals(status.getText())) status.setText(message);
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
                    .putExtra("pd2_frame_limit", Pd2FramePacingPolicy.requestedFps(preferences))
                    .putExtra("pd2_light_graphics", Pd2FramePacingPolicy.lightGraphics(preferences))
                    .putExtra("pd2_classic_cursor", Pd2FramePacingPolicy.classicCursor(preferences))
                    .putExtra("pd2_audio_eco", Pd2AudioPolicy.ecoMode(preferences))
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
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this).setTitle("Launch settings").setSingleChoiceItems(choices, selected, (d, which) -> {
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
        String[] choices = {"Native input path", "Controller notifications", "Save/Quit experiment", "Input trace"};
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this).setTitle("Controller")
            .setItems(choices, (d, which) -> {
                if (which == 0) showNativeInputSettings();
                else if (which == 1) showControllerNotificationsSettings();
                else if (which == 2) showHidReadSettings();
                else showInputTraceSettings();
            }).setNegativeButton("Close", null).show());
    }
    private void showNativeInputSettings() {
        String[] choices = {"Standard Native", "XInput compatibility (previous test)",
                "XInput reader repair (default)"};
        int selected = Pd2ControllerBackendPreferences.get(preferences);
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this)
            .setCustomTitle(controllerSettingsHeader("Native input path",
                    "Reader repair is the default and includes diagnostics. Changes apply after Stop + Play. "
                    + "Standard Native restores the original XInput module. Export logs after testing."))
            .setSingleChoiceItems(choices, selected, (d, which) -> {
                Pd2ControllerBackendPreferences.set(preferences, which);
                if (which == Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY)
                    preferences.edit().putBoolean(Pd2InputTraceRuntime.PREFERENCE, true).apply();
                operation = which == Pd2ControllerBackendPolicy.XINPUT_READER_REPAIR
                        ? "XInput reader repair selected. Stop, Play, test Save/Quit and export logs."
                        : which == Pd2ControllerBackendPolicy.XINPUT_COMPATIBILITY
                        ? "Previous XInput compatibility test selected for the next Play."
                        : "Standard Native selected for the next clean Play.";
                d.dismiss(); updateUi();
            }).setNegativeButton("Close", null).show());
    }
    private void showControllerNotificationsSettings() {
        String[] modes = {"Controller notifications enabled (default)", "Controller notifications disabled"};
        int selected = Pd2ControllerRuntime.enabled(this) ? 0 : 1;
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this).setTitle("Native controller")
            .setSingleChoiceItems(modes, selected, (d, which) -> {
                preferences.edit().putBoolean(Pd2ControllerRuntime.ENABLED_PREFERENCE, which == 0).apply();
                operation = "Controller setting saved. Restart the app before launching the game to apply it.";
                d.dismiss(); updateUi();
            }).setNegativeButton("Close", null).show());
    }
    private void showInputTraceSettings() {
        String[] choices = {"Off (default)", "Trace the next Play only"};
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this)
            .setCustomTitle(controllerSettingsHeader("Controller input trace",
                    "Records Windows API calls and return codes for one clean Play. Test briefly and export before Stop. "
                    + "Previous Wine settings return after cleanup."))
            .setSingleChoiceItems(choices, Pd2InputTraceRuntime.requested(this) ? 1 : 0, (d, which) -> {
                preferences.edit().putBoolean(Pd2InputTraceRuntime.PREFERENCE, which == 1).apply();
                operation = which == 1 ? "Input trace selected for the next clean Play. Export after a short test, before Stop."
                        : "Input trace off. Stop client to finish any running trace.";
                d.dismiss(); updateUi();
            }).setNegativeButton("Close", null).show());
    }
    /** AlertDialog cannot show its message panel and a choice list together. */
    private View controllerSettingsHeader(String title, String explanation) {
        return Pd2LauncherTheme.dialogHeader(this, title, explanation);
    }

    private void showPerformanceSettings() {
        String[] choices = {"60 fps game limit", "30 fps game limit", "Unlimited", "Game frame settings (default)"};
        int fps = Pd2FramePacingPolicy.requestedFps(preferences);
        int selected = fps == 30 ? 1 : fps == 0 ? 2 : fps == Pd2FramePacingPolicy.GAME_SETTINGS ? 3 : 0;
        String last = Pd2LaunchDiagnostics.graphicsSettingsSummary(this);
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this)
            .setCustomTitle(controllerSettingsHeader("Performance & temperature",
                    last.isEmpty() ? "Game settings restores prior frame timing. Changes apply after Stop + Play."
                            : last + " Applies after Stop + Play."))
            .setSingleChoiceItems(choices, selected, (d, which) -> {
                int chosen = which == 1 ? 30 : which == 2 ? 0 : which == 3 ? Pd2FramePacingPolicy.GAME_SETTINGS : 60;
                preferences.edit().putInt(Pd2FramePacingPolicy.PREFERENCE, chosen).apply();
                operation = chosen == Pd2FramePacingPolicy.GAME_SETTINGS ? "Game frame settings will be restored on the next clean Play."
                        : chosen == 0 ? "Unlimited selected for the next Play."
                        : chosen + " fps D2GL game limit saved for the next Play.";
                d.dismiss(); updateUi();
            }).setNeutralButton("Display refresh", (d, which) -> showDisplayRefreshSettings())
            .setPositiveButton("Graphics & audio", (d, which) -> showGraphicsAudioSettings())
            .setNegativeButton("Close", null).show());
    }

    private void showGraphicsAudioSettings() {
        String[] choices = {"Graphics effects", "Audio buffering", "Audio quality", "Game cursor"};
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this).setTitle("Graphics & audio")
            .setItems(choices, (d, which) -> {
                if (which == 0) showGraphicsEffectsSettings();
                else if (which == 1) showAudioBufferSettings();
                else if (which == 2) showAudioQualitySettings();
                else showGameCursorSettings();
            }).setNegativeButton("Close", null).show());
    }

    private void showGameCursorSettings() {
        String[] choices = {"Use game cursor setting (default)", "Classic cursor · black-square fallback"};
        String last = Pd2LaunchDiagnostics.cursorSettingsSummary(this);
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this)
            .setCustomTitle(controllerSettingsHeader("Game cursor",
                    "Classic uses the original game cursor instead of D2GL's HD cursor. Applies after Stop + Play."
                    + (last.isEmpty() ? "" : "\n" + last)))
            .setSingleChoiceItems(choices, Pd2FramePacingPolicy.classicCursor(preferences) ? 1 : 0, (d, which) -> {
                preferences.edit().putBoolean(Pd2FramePacingPolicy.CLASSIC_CURSOR_PREFERENCE, which == 1).apply();
                operation = which == 1 ? "Classic game cursor selected for the next Play."
                        : "Previous game cursor setting will be restored on the next clean Play.";
                d.dismiss(); updateUi();
            }).setNegativeButton("Close", null).show());
    }

    private void showGraphicsEffectsSettings() {
        String[] choices = {"Lighter effects (default)", "Restore game effects"};
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this)
            .setCustomTitle(controllerSettingsHeader("Graphics effects",
                    "Lighter disables supported bloom, FXAA and sharpening effects to reduce rendering work. "
                    + "Resolution stays the same. Applies after Stop + Play."))
            .setSingleChoiceItems(choices, Pd2FramePacingPolicy.lightGraphics(preferences) ? 0 : 1, (d, which) -> {
                preferences.edit().putBoolean(Pd2FramePacingPolicy.LIGHT_PREFERENCE, which == 0).apply();
                operation = which == 0 ? "Lighter graphics effects selected for the next Play."
                        : "Game effects will be restored on the next clean Play.";
                d.dismiss(); updateUi();
            }).setNegativeButton("Close", null).show());
    }

    private void showAudioBufferSettings() {
        String[] choices = {"Balanced · 32 ms (default)", "More buffering · 64 ms", "Low latency · 16 ms (previous)"};
        int latency = Pd2AudioPolicy.latencyMillis(preferences);
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this)
            .setCustomTitle(controllerSettingsHeader("Audio buffering",
                    "More buffering can smooth brief audio interruptions, with slightly later sound. "
                    + "Support logs include audio underruns. Applies after Stop + Play."))
            .setSingleChoiceItems(choices, latency == 64 ? 1 : latency == 16 ? 2 : 0, (d, which) -> {
                int chosen = which == 1 ? 64 : which == 2 ? 16 : 32;
                preferences.edit().putInt(Pd2AudioPolicy.PREFERENCE, chosen).apply();
                operation = chosen + " ms audio buffering saved for the next Play.";
                d.dismiss(); updateUi();
            }).setNegativeButton("Close", null).show());
    }

    private void showAudioQualitySettings() {
        String[] choices = {"Standard audio (default)", "Eco audio · 22.05 kHz"};
        String last = Pd2LaunchDiagnostics.audioSettingsSummary(this);
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this)
            .setCustomTitle(controllerSettingsHeader("Audio quality",
                    "Eco lowers the audio mixing rate to reduce CPU work; sound may be less crisp. "
                    + "This is separate from buffering. Applies after Stop + Play."
                    + (last.isEmpty() ? "" : "\n" + last)))
            .setSingleChoiceItems(choices, Pd2AudioPolicy.ecoMode(preferences) ? 1 : 0, (d, which) -> {
                preferences.edit().putBoolean(Pd2AudioPolicy.ECO_PREFERENCE, which == 1).apply();
                operation = which == 1 ? "Eco audio selected for the next Play."
                        : "Standard audio selected for the next Play.";
                d.dismiss(); updateUi();
            }).setNegativeButton("Close", null).show());
    }

    private void showDisplayRefreshSettings() {
        String[] choices = {"60 Hz display request (default)", "Device refresh rate"};
        int selected = preferences.getBoolean(Pd2DisplayPolicy.REQUEST_PREFERENCE, true) ? 0 : 1;
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this)
            .setCustomTitle(controllerSettingsHeader("Display refresh",
                    "60 Hz can reduce excess display work when Android supports the request. Applies next Play; "
                    + "this is not a game FPS cap. For a heat or battery check, export support logs after playing."))
            .setSingleChoiceItems(choices, selected, (d, which) -> {
                preferences.edit().putBoolean(Pd2DisplayPolicy.REQUEST_PREFERENCE, which == 0).apply();
                operation = which == 0 ? "60 Hz display request saved for the next Play."
                        : "Device refresh rate selected for the next Play.";
                d.dismiss(); updateUi();
            }).setNegativeButton("Close", null).show());
    }
    private void showHidReadSettings() {
        String[] modes = {"Original Wine driver (default)", "HID read cancellation fix (experimental)"};
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this).setTitle("Save/Quit experiment")
            .setSingleChoiceItems(modes, Pd2HidReadRuntime.enabled(this) ? 1 : 0, (d, which) -> {
                preferences.edit().putBoolean(Pd2HidReadRuntime.ENABLED_PREFERENCE, which == 1).apply();
                operation = "Setting saved. Stop client, then Play to apply it. Export logs after the Save/Quit test.";
                d.dismiss(); updateUi();
            }).setNegativeButton("Close", null).show());
    }
    private void showCpuSettings() {
        String[] modes = {"Stability (default)", "Interpreter (diagnostic; very slow)"};
        int selected = preferences.getBoolean(Pd2LaunchPolicy.CPU_PREFERENCE, false) ? 1 : 0;
        Pd2ControllerDialogs.enable(Pd2LauncherTheme.dialog(this).setTitle("CPU mode")
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
                .put("controllerBackendRequested", Pd2ControllerBackendPolicy.modeName(Pd2ControllerBackendPreferences.get(preferences)))
                .put("xinputReaderRuntime", Pd2XInputReaderRuntime.describe(this,
                        Pd2ControllerBackendPreferences.get(preferences) == Pd2ControllerBackendPolicy.XINPUT_READER_REPAIR))
                .put("hidReadRuntime", Pd2HidReadRuntime.status(this))
                .put("inputTraceRuntime", Pd2InputTraceRuntime.status(this))
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
                    out.write(Pd2LaunchDiagnostics.installationFiles(this, Pd2Installer.installedDirectory(this), installation)
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
