package com.winlator;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.Button;
import android.widget.Toast;
import android.view.Gravity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.alsaserver.ALSAClient;
import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.container.GraphicsDrivers;
import com.winlator.container.Shortcut;
import com.winlator.contentdialog.ActiveWindowsDialog;
import com.winlator.contentdialog.AudioDriverConfigDialog;
import com.winlator.contentdialog.ContentDialog;
import com.winlator.contentdialog.DXVKConfigDialog;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.contentdialog.ScreenEffectDialog;
import com.winlator.contentdialog.TurnipConfigDialog;
import com.winlator.contentdialog.VKD3DConfigDialog;
import com.winlator.contentdialog.VirGLConfigDialog;
import com.winlator.contentdialog.WineD3DConfigDialog;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.LocaleHelper;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.ProcessHelper;
import com.winlator.core.StringUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.Win32AppWorkarounds;
import com.winlator.core.WineInfo;
import com.winlator.core.WineInstaller;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineStartMenuCreator;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.pd2.Pd2InputRouter;
import com.winlator.pd2.Pd2ControllerDialogs;
import com.winlator.pd2.Pd2ControllerRuntime;
import com.winlator.pd2.Pd2Activity;
import com.winlator.pd2.Pd2LaunchDiagnostics;
import com.winlator.math.Mathf;
import com.winlator.renderer.GLRenderer;
import com.winlator.services.ForegroundService;
import com.winlator.widget.FrameRating;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.MagnifierView;
import com.winlator.widget.TouchpadView;
import com.winlator.widget.XServerView;
import com.winlator.winhandler.TaskManagerDialog;
import com.winlator.winhandler.WinHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.ALSAServerComponent;
import com.winlator.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.xenvironment.components.VirGLRendererComponent;
import com.winlator.xenvironment.components.VortekRendererComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.Atom;
import com.winlator.xserver.Property;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XServer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.Pointer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.Executors;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private RootFS rootFS;
    private FrameRating frameRating;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private String[] graphicsDriver = {GraphicsDrivers.DEFAULT_VULKAN_DRIVER, GraphicsDrivers.DEFAULT_OPENGL_DRIVER};
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private ScreenInfo screenInfo = new ScreenInfo(Container.DEFAULT_SCREEN_SIZE);
    private KeyValueSet[] dxwrapperConfig;
    private KeyValueSet[] graphicsDriverConfig = {new KeyValueSet(), new KeyValueSet()};
    private KeyValueSet audioDriverConfig;
    private String wincomponents;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private EnvVars overrideEnvVars;
    private ClipboardManager clipboardManager;
    private SharedPreferences preferences;
    private final WinHandler winHandler = new WinHandler(this);
    private float globalCursorSpeed = 1.0f;
    private boolean capturePointerOnExternalMouse = true;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    public int frameRatingWindowId = -1;
    private Win32AppWorkarounds win32AppWorkarounds;
    private String screenEffectProfile;
    private boolean pd2Session;
    private boolean pd2MouseKeyboard;
    private boolean pd2MenuCursor;
    private boolean pd2MenuOpen;
    private boolean pd2Paused;
    private boolean pd2HasWindowFocus = true;
    private volatile boolean pd2StopRequested;
    private Integer pd2RuntimeExitStatus;
    private boolean pd2LeftThumbDown, pd2RightThumbDown, pd2ChordConsumed;
    private boolean pd2LeftThumbDeferred, pd2RightThumbDeferred;
    private Pd2InputRouter pd2InputRouter;
    private AlertDialog pd2QuickDialog;
    private Button pd2Gear;
    private static WeakReference<XServerDisplayActivity> pd2ActiveSession = new WeakReference<>(null);

    public static boolean hasPd2Session() {
        XServerDisplayActivity activity = pd2ActiveSession.get();
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    public static void savePd2ControllerDiagnostics() {
        XServerDisplayActivity activity = pd2ActiveSession.get();
        if (activity != null) activity.winHandler.controllerDiagnostics.save();
    }

    public static boolean resumePd2Session(Context context) {
        if (!hasPd2Session()) return false;
        Intent intent = new Intent(context, XServerDisplayActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    public static boolean stopPd2Session(Context context) {
        XServerDisplayActivity activity = pd2ActiveSession.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        activity.runOnUiThread(activity::exit);
        return true;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);
        ForegroundService.startSession(this);

        final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        pd2Session = getIntent().getBooleanExtra("pd2_session", false);
        pd2MouseKeyboard = preferences.getBoolean("pd2_mouse_keyboard", false);
        if (pd2Session) {
            pd2ActiveSession = new WeakReference<>(this);
            winHandler.controllerDiagnostics.setLaunchId(getIntent().getStringExtra("pd2_launch_id"));
            winHandler.controllerDiagnostics.setMode(pd2MouseKeyboard ? "mouse_keyboard" : "native", false);
            winHandler.controllerDiagnostics.setNativeInputEnabled(false);
            winHandler.controllerDiagnostics.save();
        }
        boolean useAndroidClipboardOnWine = preferences.getBoolean("use_android_clipboard_on_wine", false);
        clipboardManager = useAndroidClipboardOnWine ? (ClipboardManager)getSystemService(CLIPBOARD_SERVICE) : null;

        drawerLayout = findViewById(R.id.DrawerLayout);
        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerOpened(View drawerView) {
                if (pd2Session) { releasePd2Input(); updatePd2InputMode(); }
            }
            @Override public void onDrawerClosed(View drawerView) {
                if (pd2Session) updatePd2InputMode();
            }
        });

        NavigationView navigationView = findViewById(R.id.NavigationView);
        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = pd2Session || preferences.getBoolean("enable_wine_debug", false) || preferences.getInt("box64_logs", 0) >= 1;
        if (enableLogs) {
            ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
            if (pd2Session) debugDialog.call("=== PD2 launch ===\n" + FileUtils.readString(new File(getFilesDir(), "pd2/logs/launch.json")));
        }
        Menu menu = navigationView.getMenu();
        menu.findItem(R.id.menu_item_logs).setVisible(enableLogs);
        navigationView.setNavigationItemSelectedListener(this);

        rootFS = RootFS.find(this);

        if (!isGenerateWineprefix()) {
            ContainerManager containerManager = new ContainerManager(this);
            container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
            containerManager.activateContainer(container);

            boolean wineprefixNeedsUpdate = container.getExtra("wineprefixNeedsUpdate").equals("t");
            if (wineprefixNeedsUpdate) {
                preloaderDialog.show(R.string.updating_system_files);
                WineUtils.updateWineprefix(this, (status) -> {
                    if (status == 0) {
                        container.putExtra("wineprefixNeedsUpdate", null);
                        container.putExtra("wincomponents", null);
                        container.saveData();
                        AppUtils.restartActivity(this);
                    }
                    else finish();
                });
                return;
            }

            win32AppWorkarounds = new Win32AppWorkarounds(this);

            String wineVersion = container.getWineVersion();
            wineInfo = WineInfo.fromIdentifier(this, wineVersion);

            if (wineInfo != WineInfo.MAIN_WINE_INFO) rootFS.setWinePath(wineInfo.path);

            String shortcutPath = getIntent().getStringExtra("shortcut_path");
            if (shortcutPath != null && !shortcutPath.isEmpty()) shortcut = new Shortcut(container, new File(shortcutPath));

            String graphicsDriver = container.getGraphicsDriver();
            audioDriver = container.getAudioDriver();
            String dxwrapper = container.getDXWrapper();
            wincomponents = container.getWinComponents();
            String dxwrapperConfig = container.getDXWrapperConfig();
            String graphicsDriverConfig = container.getGraphicsDriverConfig();
            audioDriverConfig = new KeyValueSet(container.getAudioDriverConfig());
            screenInfo = new ScreenInfo(container.getScreenSize());

            if (shortcut != null) {
                graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
                audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
                dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
                wincomponents = shortcut.getExtra("wincomponents", container.getWinComponents());
                dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
                graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
                audioDriverConfig = new KeyValueSet(shortcut.getExtra("audioDriverConfig", container.getAudioDriverConfig()));
                screenInfo = new ScreenInfo(shortcut.getExtra("screenSize", container.getScreenSize()));

                String dinputMapperType = shortcut.getExtra("dinputMapperType");
                if (!dinputMapperType.isEmpty()) winHandler.gamepadHandler.setDInputMapperType(Byte.parseByte(dinputMapperType));

                win32AppWorkarounds.applyStartupWorkarounds(!shortcut.wmClass.isEmpty() ? shortcut.wmClass : shortcut.path);
            }
            else {
                Intent intent = getIntent();
                if (intent.hasExtra("exec_path")) win32AppWorkarounds.applyStartupWorkarounds(FileUtils.getName(intent.getStringExtra("exec_path")));
            }

            this.graphicsDriver = GraphicsDrivers.parseIdentifiers(graphicsDriver);
            this.graphicsDriverConfig = GraphicsDrivers.parseConfigs(graphicsDriver, graphicsDriverConfig);
            this.dxwrapper = DXWrappers.parseIdentifier(dxwrapper);
            this.dxwrapperConfig = DXWrappers.parseConfigs(dxwrapper, dxwrapperConfig);
        }

        preloaderDialog.show(R.string.starting_up);

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(this, screenInfo);
        xServer.setWinHandler(winHandler);
        final boolean[] flags = {false, shortcut != null || getIntent().hasExtra("exec_path")};
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (window.id == frameRatingWindowId) frameRating.update();
            }

            @Override
            public void onMapWindow(Window window) {
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    flags[0] = true;
                }

                if (flags[1] && window.attributes.isViewable() && window.isDesktopWindow()) {
                    window.attributes.setViewable(false);
                    if (window.attributes.isEnabled()) window.disableAllDescendants();
                }

                if (win32AppWorkarounds != null) win32AppWorkarounds.applyWindowWorkarounds(window);
                changeFrameRatingVisibility(window, true);
            }

            @Override
            public void onUnmapWindow(Window window) {
                changeFrameRatingVisibility(window, false);
            }
        });

        setupUI();

        Executors.newSingleThreadExecutor().execute(() -> {
            if (!isGenerateWineprefix()) {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
            }
            setupXEnvironment();
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.setSystemLocale(newBase));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (pd2Session) {
            pd2HasWindowFocus = hasFocus;
            if (!hasFocus) {
                pd2LeftThumbDown = pd2RightThumbDown = pd2ChordConsumed = false;
                pd2LeftThumbDeferred = pd2RightThumbDeferred = false;
                releasePd2Input();
            }
            updatePd2InputMode();
        }

        if (hasFocus && touchpadView != null) {
            if (capturePointerOnExternalMouse) touchpadView.requestPointerCapture();

            if (winHandler != null && clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                ClipData primaryClip = clipboardManager.getPrimaryClip();
                if (primaryClip != null && primaryClip.getItemCount() > 0) {
                    winHandler.setClipboardData(primaryClip.getItemAt(0).getText().toString());
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (environment != null) {
            xServerView.onResume();
            environment.onResume();
        }
        ForegroundService.onResumeSession(this);
        if (pd2Session) {
            pd2Paused = false;
            updatePd2InputMode();
        }
    }

    @Override
    public void onPause() {
        if (pd2Session) {
            pd2Paused = true;
            pd2LeftThumbDown = pd2RightThumbDown = pd2ChordConsumed = false;
            pd2LeftThumbDeferred = pd2RightThumbDeferred = false;
            releasePd2Input();
            winHandler.gamepadHandler.setInputEnabled(false);
            updatePd2InputMode();
        }
        ForegroundService.onPauseSession(this);
        super.onPause();
        if (environment != null && !isInPictureInPictureMode()) {
            // PD2 online connections continue while the launcher is in front.
            if (!pd2Session) environment.onPause();
            xServerView.onPause();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        ForegroundService.setPipMode(isInPictureInPictureMode);
    }

    @Override
    protected synchronized void onDestroy() {
        if (pd2Session) {
            pd2StopRequested = true;
            releasePd2Input();
            if (pd2ActiveSession.get() == this) pd2ActiveSession.clear();
        }
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();
        ForegroundService.stopSession(this);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (pd2Session) {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawers();
                return;
            }
            showPd2QuickMenu();
            return;
        }
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        switch (item.getItemId()) {
            case R.id.menu_item_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_input_controls:
                if (pd2Session) new Handler(Looper.getMainLooper()).post(this::showPd2QuickMenu);
                else showInputControlsDialog();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_toggle_fullscreen:
                renderer.toggleFullscreen();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_task_manager:
                (new TaskManagerDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_active_windows:
                (new ActiveWindowsDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_magnifier:
                if (magnifierView == null) {
                    final FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback((value) -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_screen_effect:
                (new ScreenEffectDialog(this)).show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_pip_mode:
                PictureInPictureParams pipParams = (new PictureInPictureParams.Builder())
                    .setAspectRatio(screenInfo.aspectRatio())
                    .build();
                enterPictureInPictureMode(pipParams);
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                break;
            case R.id.menu_item_touchpad_help:
                showTouchpadHelpDialog();
                break;
            case R.id.menu_item_exit:
                exit();
                break;
        }
        return true;
    }

    public SharedPreferences getPreferences() {
        return preferences;
    }

    private synchronized void exit() {
        if (pd2Session) {
            if (pd2StopRequested) return;
            pd2StopRequested = true;
            releasePd2Input();
            winHandler.stop();
            if (environment != null) environment.stopEnvironmentComponents();
            environment = null;
            pd2ActiveSession.clear();
            ForegroundService.stopSession(this);
            Intent launcher = new Intent().setClassName(getPackageName(), "com.winlator.pd2.Pd2Activity")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (pd2RuntimeExitStatus != null) launcher.putExtra("pd2_runtime_exit_status", pd2RuntimeExitStatus.intValue());
            startActivity(launcher);
            finish();
            return;
        }
        winHandler.stop();
        if (environment != null) environment.stopEnvironmentComponents();

        Intent intent = getIntent();
        if (intent.hasExtra("exec_path")) {
            AppUtils.RestartApplicationOptions options = new AppUtils.RestartApplicationOptions();
            options.containerId = container.id;
            options.startPath = FileUtils.getDirname(intent.getStringExtra("exec_path"));
            AppUtils.restartApplication(this, options);
        }
        else AppUtils.restartApplication(this);
        ForegroundService.stopSession(this);
    }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String rfsVersion = String.valueOf(rootFS.getVersion());
        boolean containerDataChanged = false;

        boolean wineprefixWasUpdated = WineUtils.isWineprefixWasUpdated(container);
        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("rfsVersion").equals(rfsVersion) || wineprefixWasUpdated) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("rfsVersion", rfsVersion);
            containerDataChanged = true;
        }

        if (verifyUserRegistry()) containerDataChanged = true;
        if (extractDXWrapperFiles()) containerDataChanged = true;

        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        if (!(desktopTheme+","+xServer.screenInfo).equals(container.getExtra("desktopTheme"))) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", desktopTheme+","+xServer.screenInfo);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container, true);

        String startupSelection = String.valueOf(container.getStartupSelection());
        if (!startupSelection.equals(container.getExtra("startupSelection")) || wineprefixWasUpdated) {
            WineUtils.changeServicesStatus(container, container.getStartupSelection());
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }

        boolean openAndroidBrowserFromWine = preferences.getBoolean("open_android_browser_from_wine", true);
        String openAndroidBrowserFromWineStr = openAndroidBrowserFromWine ? "t" : "f";
        if (!openAndroidBrowserFromWineStr.equals(container.getExtra("openAndroidBrowserFromWine")) || wineprefixWasUpdated) {
            WineUtils.changeBrowsersRegistryKey(container, openAndroidBrowserFromWine);
            container.putExtra("openAndroidBrowserFromWine", openAndroidBrowserFromWineStr);
            containerDataChanged = true;
        }

        if (containerDataChanged) container.saveData();
    }

    private synchronized void setupXEnvironment() {
        if (pd2StopRequested || isFinishing()) return;
        String rootPath = rootFS.getRootDir().getPath();
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", rootPath+RootFS.WINEPREFIX);
        envVars.put("WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER", "1");

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : "-all");

        FileUtils.clear(rootFS.getTmpDir());

        GuestProgramLauncherComponent guestProgramLauncherComponent = new GuestProgramLauncherComponent();

        if (container != null) {
            if (container.getHUDMode() == FrameRating.Mode.FULL.ordinal()) envVars.put("X11_WND_GPU_INFO", "1");

            String desktopName = shortcut != null || getIntent().hasExtra("exec_path") ? "nogui" : "shell";
            String guestExecutable = "wine explorer /desktop="+desktopName+","+xServer.screenInfo+" "+getWineStartCommand();
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            envVars.putAll(container.getEnvVars());
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));
            if (!envVars.has("WINEESYNC")) envVars.put("WINEESYNC", "1");

            guestProgramLauncherComponent.setBox64Preset(shortcut != null ? shortcut.getExtra("box64Preset", container.getBox64Preset()) : container.getBox64Preset());
        }

        environment = new XEnvironment(this, rootFS);
        environment.addComponent(new SysVSharedMemoryComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.XSERVER_PATH)));
        environment.addComponent(new NetworkInfoUpdateComponent());

        if (audioDriver.equals(AudioDrivers.ALSA)) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath+UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", ALSAClient.USE_SHARED_MEMORY ? "true" : "false");

            ALSAClient.Options options = ALSAClient.Options.fromKeyValueSet(audioDriverConfig);
            environment.addComponent(new ALSAServerComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.ALSA_SERVER_PATH), options));
        }
        else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
            PulseAudioComponent pulseAudioComponent = new PulseAudioComponent(UnixSocketConfig.create(rootPath, UnixSocketConfig.PULSE_SERVER_PATH));
            envVars.put("PULSE_SERVER", rootPath+UnixSocketConfig.PULSE_SERVER_PATH);

            if (!audioDriverConfig.isEmpty()) {
                envVars.put("PULSE_LATENCY_MSEC", audioDriverConfig.getInt("latencyMillis", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS));
                pulseAudioComponent.setVolume(audioDriverConfig.getFloat("volume", AudioDriverConfigDialog.DEFAULT_VOLUME));
                pulseAudioComponent.setPerformanceMode(audioDriverConfig.getInt("performanceMode", AudioDriverConfigDialog.DEFAULT_PERFORMANCE_MODE));
            }
            else envVars.put("PULSE_LATENCY_MSEC", AudioDriverConfigDialog.DEFAULT_LATENCY_MILLIS);
            environment.addComponent(pulseAudioComponent);
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) {
            VortekRendererComponent.Options options = VortekRendererComponent.Options.fromKeyValueSet(this, graphicsDriverConfig[0]);
            VortekRendererComponent vortekRendererComponent = new VortekRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH), options);
            environment.addComponent(vortekRendererComponent);
        }
        if (graphicsDriver[1].equals(GraphicsDrivers.VIRGL)) {
            environment.addComponent(new VirGLRendererComponent(xServer, UnixSocketConfig.create(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH)));
        }

        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> runOnUiThread(() -> {
            if (pd2Session && !pd2StopRequested) {
                pd2RuntimeExitStatus = status;
                String id = getIntent().getStringExtra("pd2_launch_id");
                if (id != null) Pd2LaunchDiagnostics.exited(this, id, status);
                Pd2Activity.recordRuntimeExit(this, status);
                if (debugDialog != null) debugDialog.call("=== Windows runtime exit status: " + status + " ===");
            }
            exit();
        }));
        environment.addComponent(guestProgramLauncherComponent);

        if (isGenerateWineprefix()) {
            wineInfo = getIntent().getParcelableExtra("wine_info");
            if (wineInfo != null) WineInstaller.generateWineprefix(wineInfo, environment);
        }
        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars = null;
        }
        environment.startEnvironmentComponents();

        winHandler.start();
        envVars.clear();
        graphicsDriver = null;
        dxwrapperConfig = null;
        graphicsDriverConfig = null;
        audioDriver = null;
        audioDriverConfig = null;
        wincomponents = null;
    }

    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);
        renderer.setCursorColor(preferences.getInt("cursor_color", 0xffffff));
        renderer.setCursorScale(preferences.getFloat("cursor_scale", 1.0f));
        renderer.setForceWindowsFullscreen(shortcut != null && shortcut.getExtra("forceFullscreen", "0").equals("1"));

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        capturePointerOnExternalMouse = preferences.getBoolean("capture_pointer_on_external_mouse", true);
        touchpadView = new TouchpadView(this, xServer, capturePointerOnExternalMouse);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setMoveCursorToTouchpoint(preferences.getBoolean("move_cursor_to_touchpoint", false));
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
        });
        rootView.addView(touchpadView);

        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);

        if (container != null && container.getHUDMode() != FrameRating.Mode.DISABLED.ordinal()) {
            frameRating = new FrameRating(this);
            frameRating.setMode(FrameRating.Mode.values()[container.getHUDMode()]);
            frameRating.setVisibility(View.GONE);
            rootView.addView(frameRating);
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
        }

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
        if (pd2Session) {
            hideInputControls();
            pd2InputRouter = new Pd2InputRouter(xServer);
            pd2InputRouter.setCursorSpeed(preferences.getFloat("pd2_cursor_speed", 1.0f));
            pd2InputRouter.setDeadzone(preferences.getFloat("pd2_deadzone", 0.18f));
            pd2Gear = new Button(this);
            pd2Gear.setText("⚙");
            pd2Gear.setTextSize(22);
            pd2Gear.setContentDescription("Project Diablo 2 quick menu");
            pd2Gear.setAlpha(0.75f);
            int size = Math.round(54 * getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams gearParams = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.RIGHT);
            gearParams.topMargin = Math.round(8 * getResources().getDisplayMetrics().density);
            gearParams.rightMargin = gearParams.topMargin;
            rootView.addView(pd2Gear, gearParams);
            pd2Gear.setOnClickListener(view -> showPd2QuickMenu());
            updatePd2InputMode();
        }
    }

    private void releasePd2Input() {
        if (pd2InputRouter != null) pd2InputRouter.releaseAll();
        if (inputControlsView != null) inputControlsView.releaseAll();
        winHandler.gamepadHandler.neutralizeAll();
        if (xServer != null) {
            for (Pointer.Button button : Pointer.Button.values()) xServer.injectPointerButtonRelease(button);
            for (XKeycode keycode : XKeycode.values()) xServer.injectKeyRelease(keycode);
        }
    }

    private boolean pd2PointerControls() { return pd2MouseKeyboard || pd2MenuCursor; }

    private String pd2InputModeName() {
        return pd2MenuCursor ? "menu_cursor" : pd2MouseKeyboard ? "mouse_keyboard" : "native";
    }

    private String pd2InputModeLabel() {
        return pd2MenuCursor ? "Menu cursor" : pd2MouseKeyboard ? "Mouse / keyboard layout" : "Native controller";
    }

    private void updatePd2InputMode() {
        if (!pd2Session || pd2InputRouter == null) return;
        boolean inputAvailable = !pd2MenuOpen && !pd2Paused && pd2HasWindowFocus && !drawerLayout.isDrawerOpen(GravityCompat.START);
        winHandler.gamepadHandler.setStickDeadzone(pd2InputRouter.getDeadzone());
        pd2InputRouter.setMenuControls(pd2MenuCursor);
        winHandler.gamepadHandler.setInputEnabled(!pd2PointerControls() && inputAvailable);
        winHandler.controllerDiagnostics.setMode(pd2InputModeName(), inputAvailable);
        winHandler.controllerDiagnostics.setInputGate(pd2HasWindowFocus, pd2Paused, pd2MenuOpen, drawerLayout.isDrawerOpen(GravityCompat.START));
        winHandler.controllerDiagnostics.save();
        // Touch remains usable for inventory, login and text in every mode.
        touchpadView.setEnabled(inputAvailable);
        xServerView.getRenderer().setCursorVisible(true);
        if (pd2Gear != null) pd2Gear.setContentDescription("Quick menu. " + pd2InputModeLabel() + " mode");
    }

    private void showPd2QuickMenu() {
        if (!pd2Session || pd2InputRouter == null || pd2MenuOpen) return;
        releasePd2Input();
        pd2MenuOpen = true;
        updatePd2InputMode();
        String current = pd2InputModeLabel();
        String[] items = {
            "Resume game",
            "Native controller" + (!pd2PointerControls() ? " ✓" : ""),
            "Menu cursor" + (pd2MenuCursor ? " ✓" : ""),
            "Mouse / keyboard layout" + (pd2MouseKeyboard ? " ✓" : ""),
            "Show keyboard",
            "Send Esc",
            "Cursor speed: " + pd2InputRouter.getCursorSpeed() + "× (tap to change)",
            "Stick deadzone: " + Math.round(pd2InputRouter.getDeadzone() * 100) + "% (tap to change)",
            "Controller bindings",
            "Runtime settings and logs",
            "Back to Launcher Menu",
            "Stop game",
            "Controller status"
        };
        pd2QuickDialog = new AlertDialog.Builder(this)
            .setTitle("PD2 quick menu · " + current)
            .setItems(items, (dialog, which) -> {
                switch (which) {
                    case 1:
                    case 2:
                    case 3:
                        releasePd2Input();
                        pd2MenuCursor = which == 2;
                        pd2MouseKeyboard = which == 3;
                        preferences.edit().putBoolean("pd2_mouse_keyboard", pd2MouseKeyboard).apply();
                        Toast.makeText(this, pd2MenuCursor ? "Menu cursor · choose Native controller after entering your character" : pd2InputModeLabel(), Toast.LENGTH_SHORT).show();
                        break;
                    case 4: new Handler(Looper.getMainLooper()).post(() -> AppUtils.showKeyboard(this)); break;
                    case 5:
                        xServer.injectKeyPress(XKeycode.KEY_ESC);
                        xServer.injectKeyRelease(XKeycode.KEY_ESC);
                        break;
                    case 6:
                        float speed = pd2InputRouter.getCursorSpeed();
                        speed = speed >= 2.0f ? 0.5f : speed + 0.5f;
                        pd2InputRouter.setCursorSpeed(speed);
                        preferences.edit().putFloat("pd2_cursor_speed", speed).apply();
                        Toast.makeText(this, "Cursor speed " + speed + "×", Toast.LENGTH_SHORT).show();
                        break;
                    case 7:
                        float deadzone = pd2InputRouter.getDeadzone();
                        deadzone = deadzone >= 0.29f ? 0.10f : deadzone + 0.05f;
                        pd2InputRouter.setDeadzone(deadzone);
                        preferences.edit().putFloat("pd2_deadzone", deadzone).apply();
                        Toast.makeText(this, "Stick deadzone " + Math.round(deadzone * 100) + "%", Toast.LENGTH_SHORT).show();
                        break;
                    case 8: new Handler(Looper.getMainLooper()).post(this::showPd2Bindings); break;
                    case 9: new Handler(Looper.getMainLooper()).post(() -> drawerLayout.openDrawer(GravityCompat.START)); break;
                    case 10:
                        startActivity(new Intent().setClassName(getPackageName(), "com.winlator.pd2.Pd2Activity")
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                        break;
                    case 11: exit(); break;
                    case 12: new Handler(Looper.getMainLooper()).post(this::showPd2ControllerStatus); break;
                }
            }).create();
        pd2QuickDialog.setOnDismissListener(dialog -> {
            pd2MenuOpen = false;
            pd2QuickDialog = null;
            updatePd2InputMode();
        });
        pd2QuickDialog.show();
        Pd2ControllerDialogs.enable(pd2QuickDialog);
        pd2QuickDialog.getListView().setSelection(0);
        pd2QuickDialog.getListView().requestFocus();
    }

    private void showPd2Bindings() {
        releasePd2Input();
        pd2MenuOpen = true;
        updatePd2InputMode();
        AlertDialog help = new AlertDialog.Builder(this).setTitle(pd2MenuCursor ? "Menu cursor controls" : "Mouse / keyboard layout")
                .setMessage(pd2MenuCursor ? Pd2InputRouter.MENU_HELP : Pd2InputRouter.LAYOUT_HELP).setPositiveButton("Done", null).create();
        help.setOnDismissListener(dialog -> { pd2MenuOpen = false; updatePd2InputMode(); });
        help.show();
        Pd2ControllerDialogs.enable(help);
    }

    private void showPd2ControllerStatus() {
        releasePd2Input();
        pd2MenuOpen = true;
        updatePd2InputMode();
        JSONObject report = winHandler.controllerDiagnostics.snapshot();
        StringBuilder message = new StringBuilder("Layout: ")
                .append(pd2InputModeLabel())
                .append("\nController notifications: ").append(Pd2ControllerRuntime.enabled(this) ? "Enabled" : "Disabled")
                .append("\n\nDetected controllers:");
        JSONArray devices = report.optJSONArray("devices");
        int accepted = 0;
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i);
            if (device != null && device.optBoolean("accepted")) {
                message.append("\n• ").append(device.optString("name", "Controller"));
                accepted++;
            }
        }
        if (accepted == 0) message.append("\nNone detected by Android as a gamepad.");
        JSONObject counts = report.optJSONObject("counts");
        if (counts != null) message.append("\n\nController buttons received: ")
                .append(counts.optLong("handledKeyEvents"))
                .append("\nStick events received: ").append(counts.optLong("handledMotionEvents"))
                .append("\nGame discovery replies: ").append(counts.optLong("deviceReplies"))
                .append("\nGame state replies: ").append(counts.optLong("stateReplies"));
        if (counts != null) message.append("\nWindows gamepad discovery requests: ")
                .append(counts.optLong("hidDiscoveryRequests7950"))
                .append("\nWindows gamepad discovery replies: ")
                .append(counts.optLong("hidDeviceReplies7950"))
                .append("\nWindows gamepad state replies: ").append(counts.optLong("hidStateReplies7950"));
        message.append("\n\nInput pauses while this menu is open. Use Menu cursor for the title and character screens. After entering your character, choose Native controller, close the quick menu, and press a controller button.")
                .append("\n\nIf input still fails, export support logs from the launcher after trying the controller.");
        AlertDialog status = new AlertDialog.Builder(this).setTitle("Controller status")
                .setMessage(message.toString()).setPositiveButton("Done", null).create();
        status.setOnDismissListener(dialog -> { pd2MenuOpen = false; updatePd2InputMode(); });
        status.show();
        Pd2ControllerDialogs.enable(status);
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);
        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile == inputControlsView.getProfile()) selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbRelativeMouseMovement = dialog.findViewById(R.id.CBRelativeMouseMovement);
        cbRelativeMouseMovement.setChecked(xServer.isRelativeMouseMovement());

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
            };
            startActivityForResult(intent, MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
        });

        dialog.setOnConfirmCallback(() -> {
            xServer.setRelativeMouseMovement(cbRelativeMouseMovement.isChecked());
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            }
            else hideInputControls();
        });

        dialog.show();
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        GLRenderer renderer = xServerView.getRenderer();
        if (profile.isDisableMouseInput()) {
            renderer.setCursorVisible(false);
            touchpadView.setEnabled(false);
        }
        else {
            renderer.setCursorVisible(true);
            touchpadView.setEnabled(true);
        }

        inputControlsView.invalidate();
    }

    private void hideInputControls() {
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        if (!touchpadView.isEnabled()) {
            touchpadView.setEnabled(true);
            xServerView.getRenderer().setCursorVisible(true);
        }

        inputControlsView.invalidate();
    }

    private void extractGraphicsDriverFiles() {
        envVars.put("vblank_mode", "0");

        String cacheId = "";
        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            cacheId += graphicsDriver[0]+"-"+graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
        }
        else cacheId += graphicsDriver[0]+"-"+DefaultVersion.valueOf(graphicsDriver[0]);
        cacheId += "-"+graphicsDriver[1]+"-"+DefaultVersion.valueOf(graphicsDriver[1]);

        String currentGraphicsDriver = preferences.getString("current_graphics_driver", "");
        boolean changed = !cacheId.equals(currentGraphicsDriver);
        File rootDir = rootFS.getRootDir();
        File libDir = rootFS.getLibDir();

        if (changed) {
            FileUtils.delete(new File(libDir, "libvulkan_freedreno.so"));
            FileUtils.delete(new File(libDir, "libvulkan_vortek.so"));
            FileUtils.delete(new File(libDir, "libGL.so.1.7.0"));

            File vulkanICDDir = new File(rootDir, "/usr/share/vulkan/icd.d");
            FileUtils.delete(vulkanICDDir);
            vulkanICDDir.mkdirs();

            preferences.edit().putString("current_graphics_driver", cacheId).apply();
        }

        if (graphicsDriver[0].equals(GraphicsDrivers.TURNIP)) {
            TurnipConfigDialog.setEnvVars(this, graphicsDriverConfig[0], envVars);

            if (changed) {
                String version = graphicsDriverConfig[0].get("version", DefaultVersion.TURNIP);
                GeneralComponents.extractFile(GeneralComponents.Type.TURNIP, this, version, DefaultVersion.TURNIP);
            }
        }
        else if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK) && (changed || MainActivity.DEBUG_MODE)) {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/vortek-" + DefaultVersion.VORTEK + ".tzst", rootDir);
        }

        switch (graphicsDriver[1]) {
            case GraphicsDrivers.ZINK:
                envVars.put("GALLIUM_DRIVER", "zink");
                envVars.put("ZINK_CONTEXT_THREADED", "1");
                if (graphicsDriver[0].equals(GraphicsDrivers.VORTEK)) envVars.put("MESA_GL_VERSION_OVERRIDE", "3.3");

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/zink-"+DefaultVersion.ZINK+".tzst", rootDir);
                break;
            case GraphicsDrivers.VIRGL:
                envVars.put("GALLIUM_DRIVER", "virpipe");
                envVars.put("VIRGL_NO_READBACK", "true");
                envVars.put("VIRGL_SERVER_PATH", rootDir+UnixSocketConfig.VIRGL_SERVER_PATH);
                VirGLConfigDialog.setEnvVars(graphicsDriverConfig[1], envVars);

                if (changed) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/virgl-"+DefaultVersion.VIRGL+".tzst", rootDir);
                break;
            case GraphicsDrivers.GLADIO:
                envVars.put("GLADIO_NO_ERROR", "1");

                if (changed || MainActivity.DEBUG_MODE) TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "graphics_driver/gladio-"+DefaultVersion.GLADIO+".tzst", rootDir);
                break;
        }
    }

    private void showTouchpadHelpDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.touchpad_help_dialog);
        dialog.setTitle(R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (pd2Session && pd2InputRouter != null) {
            if (pd2MenuOpen || !pd2HasWindowFocus || pd2Paused || drawerLayout.isDrawerOpen(GravityCompat.START)) return super.dispatchGenericMotionEvent(event);
            if (pd2PointerControls() && ExternalController.isJoystickDevice(event)) {
                winHandler.controllerDiagnostics.recordMotion(false);
                if (pd2InputRouter.motion(event)) {
                    winHandler.controllerDiagnostics.recordMotion(true);
                    return true;
                }
            }
            if (!pd2PointerControls() && winHandler.onGenericMotionEvent(event)) return true;
            if (touchpadView.onExternalMouseEvent(event)) return true;
            return super.dispatchGenericMotionEvent(event);
        }
        return !winHandler.onGenericMotionEvent(event) && !touchpadView.onExternalMouseEvent(event) && super.dispatchGenericMotionEvent(event);
    }

    private boolean routePd2PointerKey(KeyEvent event) {
        winHandler.controllerDiagnostics.recordKey(false);
        boolean handled = pd2InputRouter.keyEvent(event);
        if (handled) winHandler.controllerDiagnostics.recordKey(true);
        return handled;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (pd2Session && pd2InputRouter != null) {
            if (ExternalController.isGameController(event.getDevice())) {
                int code = event.getKeyCode();
                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                if (code == KeyEvent.KEYCODE_BUTTON_THUMBL || code == KeyEvent.KEYCODE_BUTTON_THUMBR) {
                    if (event.getRepeatCount() != 0) return true;
                    boolean left = code == KeyEvent.KEYCODE_BUTTON_THUMBL;
                    boolean deferred = left ? pd2LeftThumbDeferred : pd2RightThumbDeferred;
                    if (left) { pd2LeftThumbDown = down; pd2LeftThumbDeferred = down && event.getRepeatCount() == 0; }
                    else { pd2RightThumbDown = down; pd2RightThumbDeferred = down && event.getRepeatCount() == 0; }
                    if (pd2LeftThumbDown && pd2RightThumbDown && !pd2MenuOpen) {
                        pd2ChordConsumed = true;
                        pd2LeftThumbDeferred = pd2RightThumbDeferred = false;
                        showPd2QuickMenu();
                    } else if (!down && deferred && !pd2ChordConsumed && !pd2MenuOpen && pd2HasWindowFocus && !pd2Paused && !drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        KeyEvent press = KeyEvent.changeAction(event, KeyEvent.ACTION_DOWN);
                        if (pd2PointerControls()) { routePd2PointerKey(press); routePd2PointerKey(event); }
                        else { winHandler.onKeyEvent(press); winHandler.onKeyEvent(event); }
                    }
                    if (!pd2LeftThumbDown && !pd2RightThumbDown) pd2ChordConsumed = false;
                    return true;
                }
                if (pd2MenuOpen || !pd2HasWindowFocus || pd2Paused || drawerLayout.isDrawerOpen(GravityCompat.START)) return super.dispatchKeyEvent(event);
                if (pd2PointerControls()) return routePd2PointerKey(event);
                if (winHandler.onKeyEvent(event)) return true;
                return true;
            }
            if (pd2MenuOpen || !pd2HasWindowFocus || pd2Paused || drawerLayout.isDrawerOpen(GravityCompat.START)) return super.dispatchKeyEvent(event);
        }
        return (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
               (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
    }

    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private boolean extractDXWrapperFiles() {
        String cacheId = "";
        if (dxwrapper.equals(DXWrappers.DXVK)) {
            DXVKConfigDialog.setEnvVars(this, dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.DXVK(graphicsDriver[0]));
        }
        else if (dxwrapper.equals(DXWrappers.WINED3D)) {
            WineD3DConfigDialog.setEnvVars(dxwrapperConfig[0], envVars);
            cacheId += dxwrapper+"-"+dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
        }

        String ddrawWrapper = dxwrapperConfig[0].get("ddrawWrapper", DXWrappers.WINED3D);
        cacheId += "-"+DXWrappers.VKD3D+"-"+dxwrapperConfig[1].get("version", DefaultVersion.VKD3D)+"-"+ddrawWrapper;
        boolean changed = !cacheId.equals(container.getExtra("dxwrapper"));
        VKD3DConfigDialog.setEnvVars(dxwrapperConfig[1], envVars);

        if (ddrawWrapper.equals(DXWrappers.CNC_DDRAW)) envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\ProgramData\\cnc-ddraw\\ddraw.ini");

        if (!changed) return false;
        container.putExtra("dxwrapper", cacheId);

        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");

        if (dxwrapper.equals(DXWrappers.WINED3D)) {
            String version = dxwrapperConfig[0].get("version", DefaultVersion.WINED3D);
            if (version.equals(WineInfo.MAIN_WINE_VERSION)) {
                final String[] dlls = {"d3d8.dll", "d3d9.dll", "d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "dxgi.dll", "ddraw.dll", "wined3d.dll"};
                restoreBuiltinDllFiles(dlls);
            }
            else GeneralComponents.extractFile(GeneralComponents.Type.WINED3D, this, version, DefaultVersion.WINED3D);
        }
        else if (dxwrapper.equals(DXWrappers.DXVK)) {
            final boolean[] hasD3D8DllFile = {false};
            final boolean[] hasD3D10DllFile = {false};

            GeneralComponents.extractFile(GeneralComponents.Type.DXVK, this, dxwrapperConfig[0].get("version"), DefaultVersion.DXVK(graphicsDriver[0]), (destination, size) -> {
                String name = destination.getName();
                if (name.equals("d3d10.dll")) {
                    hasD3D10DllFile[0] = true;
                }
                else if (name.equals("d3d8.dll")) {
                    hasD3D8DllFile[0] = true;
                }
                return destination;
            });

            if (!hasD3D8DllFile[0]) {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d8vk-"+DefaultVersion.D8VK+".tzst", windowsDir);
            }
            if (!hasD3D10DllFile[0]) restoreBuiltinDllFiles("d3d10.dll", "d3d10_1.dll");
        }

        GeneralComponents.extractFile(GeneralComponents.Type.VKD3D, this, dxwrapperConfig[1].get("version"), DefaultVersion.VKD3D);

        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");
        FileUtils.delete(new File(containerSysWoW64Dir, "ddraw_.dll"));

        switch (ddrawWrapper) {
            case DXWrappers.CNC_DDRAW:
                final String assetDir = "dxwrapper/cnc-ddraw-"+DefaultVersion.CNC_DDRAW;
                File configFile = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/ddraw.ini");
                if (!configFile.isFile()) FileUtils.copy(this, assetDir+"/ddraw.ini", configFile);
                File shadersDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/ProgramData/cnc-ddraw/Shaders");
                FileUtils.delete(shadersDir);
                FileUtils.copy(this, assetDir+"/Shaders", shadersDir);
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, assetDir+"/ddraw.tzst", windowsDir);
                break;
            case DXWrappers.D7VK:
                restoreBuiltinDllFiles("ddraw.dll");
                (new File(containerSysWoW64Dir, "ddraw.dll")).renameTo(new File(containerSysWoW64Dir, "ddraw_.dll"));
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "dxwrapper/d7vk-"+DefaultVersion.D7VK+".tzst", windowsDir);
                break;
            default:
                restoreBuiltinDllFiles("ddraw.dll");
                break;
        }
        return true;
    }

    private void extractWinComponentFiles() {
        File rootDir = rootFS.getRootDir();
        File windowsDir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, RootFS.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();
            ArrayList<String> builtinDlls = new ArrayList<>();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1])) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir);
                }
                else {
                    JSONObject wincomponentJSONObject = wincomponentsJSONObject.getJSONObject(identifier);
                    if (wincomponentJSONObject.getBoolean("restoreBuiltinDlls")) {
                        JSONArray dlnames = wincomponentJSONObject.getJSONArray("dlnames");
                        for (int i = 0; i < dlnames.length(); i++) {
                            String dlname = dlnames.getString(i);
                            builtinDlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                        }
                    }
                    else {
                        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, (destination, size) -> {
                            String name = destination.getName();
                            if (name.endsWith(".dll") || name.endsWith(".manifest") || name.endsWith("_deadbeef")) FileUtils.delete(destination);
                            return null;
                        });
                    }
                }

                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative);
            }

            if (!builtinDlls.isEmpty()) restoreBuiltinDllFiles(builtinDlls.toArray(new String[0]));
            WineUtils.overrideWinComponentDlls(this, container, wincomponents);
        }
        catch (JSONException e) {}
    }

    private void restoreBuiltinDllFiles(final String... dlls) {
        File rootDir = rootFS.getRootDir();
        File wineDir = new File(rootDir, rootFS.getWinePath());
        File wineSystem32Dir = new File(wineDir, "/lib/wine/x86_64-windows");
        File wineSysWoW64Dir = new File(wineDir, "/lib/wine/i386-windows");
        File containerSystem32Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/system32");
        File containerSysWoW64Dir = new File(rootDir, RootFS.WINEPREFIX+"/drive_c/windows/syswow64");;

        for (String dll : dlls) {
            FileUtils.copy(new File(wineSysWoW64Dir, dll), new File(containerSysWoW64Dir, dll));
            FileUtils.copy(new File(wineSystem32Dir, dll), new File(containerSystem32Dir, dll));
        }
    }

    private boolean isGenerateWineprefix() {
        return getIntent().getBooleanExtra("generate_wineprefix", false);
    }

    private String getWineStartCommand() {
        String cmdArgs = "";
        String execPath = null;
        String execArgs = "";

        if (shortcut != null) {
            execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " "+execArgs : "";

            if (shortcut.isLinkPath()) {
                cmdArgs = "\""+shortcut.path+"\""+execArgs;
            }
            else execPath = shortcut.path;
        }
        else {
            Intent intent = getIntent();
            if (intent.hasExtra("exec_path")) {
                execPath = WineUtils.unixToDOSPath(intent.getStringExtra("exec_path"), container);
                String suppliedArgs = intent.getStringExtra("exec_args");
                if (suppliedArgs != null && !suppliedArgs.trim().isEmpty()) execArgs = " " + suppliedArgs.trim();

                if (execPath.endsWith(".lnk")) {
                    cmdArgs = "\""+execPath+"\"";
                    execPath = null;
                }
            }
        }

        if (execPath != null) {
            String execDir = FileUtils.getDirname(execPath);
            String filename = FileUtils.getName(execPath);
            int dotIndex, spaceIndex;
            if ((dotIndex = filename.lastIndexOf(".")) != -1 && (spaceIndex = filename.indexOf(" ", dotIndex)) != -1) {
                execArgs = filename.substring(spaceIndex+1)+execArgs;
                filename = filename.substring(0, spaceIndex);
            }
            cmdArgs = "/dir "+StringUtils.escapeDOSPath(execDir)+" \""+filename+"\""+execArgs;
        }

        if (cmdArgs.isEmpty()) cmdArgs = "/dir C:\\windows \"wfm.exe\"";

        if (overrideEnvVars != null && overrideEnvVars.has("EXTRA_EXEC_ARGS")) {
            cmdArgs += " "+overrideEnvVars.get("EXTRA_EXEC_ARGS");
            overrideEnvVars.remove("EXTRA_EXEC_ARGS");
        }
        return "C:\\windows\\winhandler.exe "+cmdArgs;
    }

    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public RootFS getRootFs() {
        return rootFS;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) overrideEnvVars = new EnvVars();
        return overrideEnvVars;
    }

    public String getDXWrapper() {
        return dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    public void setScreenInfo(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
    }

    public String getWinComponents() {
        return wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public DebugDialog getDebugDialog() {
        return debugDialog;
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = rootFS.getRootDir();
            File userRegFile = new File(rootDir, RootFS.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals(AudioDrivers.ALSA)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals(AudioDrivers.PULSEAUDIO)) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = rootFS.getRootDir();
        FileUtils.delete(new File(rootDir, "/opt/apps"));
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "rootfs_patches.tzst", rootDir);
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "pulseaudio.tzst", new File(getFilesDir(), "pulseaudio"));
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("dxwrapper", null);
        container.putExtra("desktopTheme", null);
        SettingsFragment.resetPreferenceVersions(this);
    }

    public void changeFrameRatingVisibility(Window window, boolean visible) {
        if (frameRating == null) return;
        if (visible) {
            if (window.id == frameRatingWindowId) return;
            Window child = window.getChildAt(0);
            boolean viewable = window.attributes.isMapped() && window.getWidth() >= ScreenInfo.MIN_WIDTH && window.getHeight() >= ScreenInfo.MIN_HEIGHT;
            Window frameRatingWindow = null;
            if (viewable && (window.isSurface() || (child != null && child.isSurface()))) {
                frameRatingWindow = window.isSurface() ? window : child;
            }
            else if (window.isSurface() && !window.isApplicationWindow()) {
                Window parent = window.getParent();
                if (parent != null && parent.isApplicationWindow() && !parent.isSurface()) frameRatingWindow = window;
            }

            if (frameRatingWindow != null) {
                if (frameRating.getMode() == FrameRating.Mode.FULL) {
                    Property gpuInfo = frameRatingWindow.getProperty(Atom._NET_WM_GPU_INFO);
                    frameRating.setGPUInfo(gpuInfo != null ? new String(gpuInfo.data.array()) : "N/A");
                }
                frameRatingWindowId = frameRatingWindow.id;
                frameRating.reset();
            }
        }
        else if (window.id == frameRatingWindowId) {
            frameRatingWindowId = -1;
            runOnUiThread(() -> frameRating.setVisibility(View.GONE));
        }
    }

    public boolean verifyUserRegistry() {
        File userRegFile = new File(rootFS.getRootDir(), RootFS.WINEPREFIX+"/user.reg");
        String lastModified = String.valueOf(userRegFile.lastModified());

        if (!lastModified.equals(container.getExtra("userRegLastModified"))) {
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                registryEditor.removeKey("Software\\Wow6432Node\\Wine", true);
            }

            container.putExtra("userRegLastModified", lastModified);
            return true;
        }
        else return false;
    }
}
