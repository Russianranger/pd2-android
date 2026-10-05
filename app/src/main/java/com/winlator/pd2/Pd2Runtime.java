package com.winlator.pd2;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import com.winlator.container.AudioDrivers;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.DXWrappers;
import com.winlator.core.Callback;
import com.winlator.core.FileUtils;
import com.winlator.core.WineInfo;
import com.winlator.core.WineRegistryEditor;
import com.winlator.core.WineThemeManager;
import com.winlator.core.WineUtils;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.RootFSInstaller;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

/** PD2 integration added October 3, 2026; the imported game remains unmodified. */
public final class Pd2Runtime {
    public static final String TURNIP_ZINK = "turnip,zink";
    public static final String TURNIP_VIRGL = "turnip,virgl";
    public static final String DEFAULT_RENDERER = TURNIP_ZINK;
    public static final String CONTAINER_NAME = "Project Diablo 2";
    public static final String RUNTIME_REVISION = "wine-9.2-pd2-1";
    private static final String CONTAINER_ID = "pd2_container_id";
    private static final String MANAGED = "pd2Managed";
    private static final String REVISION = "pd2RuntimeRevision";
    private static final String WIN_COMPONENTS =
            "direct3d=0,directsound=0,directmusic=0,directshow=0,directplay=0,"+
            "xaudio=0,vcrun2005=0,vcrun2010=1,wmdecoder=0";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayList<Callback<Container>> WAITING = new ArrayList<>();
    private static boolean creating;

    private Pd2Runtime() {}

    /** Validate the installed base, including the x86 game and controller modules. */
    public static boolean hasRuntime(Context context) {
        try {
            RootFS rootFS = RootFS.find(context);
            if (!rootFS.isValid() || rootFS.getVersion() < RootFSInstaller.LATEST_VERSION) return false;
            File root = rootFS.getRootDir();
            for (String path : new String[]{
                    "opt/wine/bin/wine", "opt/wine/bin/wineserver", "usr/lib/libc.so.6",
                    "opt/wine/lib/wine/i386-windows/ntdll.dll",
                    "opt/wine/lib/wine/i386-windows/xinput1_3.dll",
                    "opt/wine/lib/wine/i386-windows/winexinput.sys"}) {
                if (!new File(root, path).isFile()) return false;
            }
            return true;
        }
        catch (RuntimeException error) {
            return false;
        }
    }

    /**
     * Reuse the managed prefix, recovering a stale preference by its marker.
     * Creation and callbacks run through the main thread. A null result means
     * creation failed; callers can leave the existing installation intact.
     */
    public static void getOrCreateContainer(Activity activity, Callback<Container> callback) {
        MAIN.post(() -> {
            if (callback == null) return;
            if (!hasRuntime(activity)) {
                callback.call(null);
                return;
            }
            if (creating) {
                WAITING.add(callback);
                return;
            }

            try {
                Pd2ControllerRuntime.prepare(activity);
                ContainerManager manager = new ContainerManager(activity);
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
                Container selected = findCurrentContainer(activity, manager);
                if (selected != null) {
                    String renderer = selected.getGraphicsDriver();
                    configure(activity, selected, isRenderer(renderer) ? renderer : DEFAULT_RENDERER);
                    callback.call(selected);
                    return;
                }

                // No valid prefix is removed while recovering a missing/stale ID.
                preferences.edit().remove(CONTAINER_ID).apply();
                creating = true;
                WAITING.add(callback);
                manager.createContainerAsync(defaults(activity), container -> {
                    Container ready = container;
                    try {
                        if (ready != null) {
                            if (!hasPrefixFiles(ready)) throw new IllegalStateException("The fresh Wine prefix is incomplete");
                            configure(activity, ready, DEFAULT_RENDERER);
                        }
                    }
                    catch (RuntimeException error) {
                        ready = null;
                    }
                    deliver(ready);
                });
            }
            catch (RuntimeException | JSONException | java.io.IOException error) {
                Pd2Activity.appendLauncherLog(activity, "Runtime/controller setup failed: " + error.getMessage());
                if (creating) deliver(null);
                else callback.call(null);
            }
        });
    }

    /** Read-only selection shared by Play and the container list; never creates or migrates a prefix. */
    public static Container findCurrentContainer(Context context, ContainerManager manager) {
        Container selected = manager.getContainerById(savedContainerId(context));
        if (isManagedAndReady(selected)) return selected;
        for (Container candidate : manager.getContainers()) {
            if (isManagedAndReady(candidate)) return candidate;
        }
        return null;
    }

    static int savedContainerId(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getInt(CONTAINER_ID, 0);
    }

    public static boolean isManagedContainer(Container container) {
        return container != null && "1".equals(container.getExtra(MANAGED));
    }

    /** Save runtime-only defaults and the private P: drive before launching. */
    public static void configure(Context context, Container container, String renderer) {
        if (container == null) throw new IllegalArgumentException("PD2 container is missing");
        if (!isRenderer(renderer)) throw new IllegalArgumentException("Unsupported PD2 renderer");
        File installed = Pd2Installer.installedDirectory(context);
        Pd2InstallValidator.Result installation = Pd2InstallValidator.validate(installed);
        if (!installation.valid) throw new IllegalStateException(installation.message);
        seedInstallationRegistry(container, installed, installation);
        seedFogDiagnostics(new File(container.getRootDir(), ".wine/user.reg"));
        seedControllerRegistry(new File(container.getRootDir(), ".wine/system.reg"), Pd2ControllerRuntime.enabled(context));
        container.setName(CONTAINER_NAME);
        container.setScreenSize("1280x720");
        container.setGraphicsDriver(renderer);
        container.setGraphicsDriverConfig("");
        container.setDXWrapper(DXWrappers.WINED3D);
        container.setDXWrapperConfig("");
        container.setAudioDriver(AudioDrivers.ALSA);
        container.setAudioDriverConfig("");
        container.setBox64Preset(Pd2LaunchPolicy.cpuPreset());
        container.setWinComponents(WIN_COMPONENTS);
        container.setStartupSelection(Container.STARTUP_SELECTION_ESSENTIAL);
        container.putExtra(MANAGED, "1");
        String notifications = Pd2ControllerRuntime.enabled(context) ? "1" : "0";
        if (!notifications.equals(container.getExtra("pd2ControllerNotifications"))) {
            container.putExtra("startupSelection", "");
        }
        container.putExtra("pd2ControllerNotifications", notifications);
        WineUtils.changeServicesStatus(container, container.getStartupSelection());
        container.setEnvVars(environment(context));
        container.setDrives("P:" + installed.getAbsolutePath());
        container.putExtra(MANAGED, "1");
        container.putExtra(REVISION, RUNTIME_REVISION);
        container.saveData();
        // Do not publish a new container ID until its complete configuration
        // has been persisted. A failed migration leaves the old prefix intact.
        try {
            JSONObject persisted = new JSONObject(FileUtils.readString(container.getConfigFile()));
            if (!RUNTIME_REVISION.equals(persisted.getJSONObject("extraData").optString(REVISION)))
                throw new IllegalStateException("Cannot save the private Wine container");
        }
        catch (JSONException | RuntimeException error) {
            throw new IllegalStateException("Cannot save the private Wine container", error);
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = preferences.edit().putInt(CONTAINER_ID, container.id);
        // Winlator auto-assigns physical devices when player slots have no name.
        // A Windows Xbox identity lets SDL/PD2 recognize the normal XInput layout.
        if (!preferences.contains("gamepad_model")) {
            editor.putString("gamepad_model", "VID_045E&PID_028E");
        }
        editor.apply();
    }

    /** Recreate the base installer's paths in this app's prefix, never the source prefix. */
    private static void seedInstallationRegistry(Container container, File installed,
                                                 Pd2InstallValidator.Result installation) {
        String relativeRoot = installation.clientRootRelativePath;
        String installPath = windowsPath(relativeRoot);
        String gamePath = windowsPath(installation.gameExecutableRelativePath);
        File base = relativeRoot.isEmpty() ? installed : new File(installed, relativeRoot);
        File[] files = base.listFiles();
        if (files != null) for (File file : files) {
            if (file.isFile() && file.getName().equalsIgnoreCase("Game.exe")) {
                String relative = relativeRoot.isEmpty() ? file.getName() : relativeRoot + "/" + file.getName();
                gamePath = windowsPath(relative);
                break;
            }
        }
        writeAndVerifyRegistry(new File(container.getRootDir(), ".wine/user.reg"),
                new String[]{"Software\\Blizzard Entertainment\\Diablo II"}, installPath, gamePath);
        writeAndVerifyRegistry(new File(container.getRootDir(), ".wine/system.reg"),
                new String[]{"Software\\Blizzard Entertainment\\Diablo II",
                        "Software\\Wow6432Node\\Blizzard Entertainment\\Diablo II"}, installPath, gamePath);
    }

    private static String windowsPath(String relative) {
        return "P:\\" + relative.replace('/', '\\');
    }

    /** The Wine 9 PE bus uses its SDL slots to start the Android HID producer. */
    private static void seedControllerRegistry(File hive, boolean notifications) {
        if (!hive.isFile()) throw new IllegalStateException("The private Wine registry is missing");
        try (WineRegistryEditor registry = new WineRegistryEditor(hive)) {
            String key = "System\\CurrentControlSet\\Services\\winebus";
            String controlSet = registry.getSymlinkValue("System\\CurrentControlSet", "SymbolicLinkValue");
            if (controlSet != null) key = controlSet + "\\Services\\winebus";
            registry.setDwordValue(key, "Enable SDL", 1);
            // This synthetic pad has no host hidraw endpoint to prefer.
            registry.setDwordValue(key, "DisableHidraw", notifications ? 1 : 0);
        }
    }

    /** Trace only native Fog exports in this app's dedicated prefix. */
    private static void seedFogDiagnostics(File hive) {
        if (!hive.isFile()) throw new IllegalStateException("The private Wine registry is missing");
        String key = "Software\\Wine\\Debug";
        String[] names = {"SnoopInclude", "SnoopExclude", "SnoopFromInclude", "SnoopFromExclude"};
        try (WineRegistryEditor registry = new WineRegistryEditor(hive)) {
            for (String name : names) {
                String value = name.equals("SnoopInclude") ? "Fog.*" : "";
                if (!value.equals(registry.getStringValue(key, name))) registry.setStringValue(key, name, value);
            }
        }
        try (WineRegistryEditor registry = new WineRegistryEditor(hive)) {
            for (String name : names) {
                String value = name.equals("SnoopInclude") ? "Fog.*" : "";
                if (!value.equals(registry.getStringValue(key, name)))
                    throw new IllegalStateException("Cannot prepare the Fog startup trace");
            }
        }
    }

    /** Selected persisted startup settings only; never export a complete hive. */
    static JSONObject registrySnapshot(Container container) throws JSONException {
        JSONObject report = new JSONObject();
        File root = container.getRootDir();
        if (root == null) return report.put("available", false);
        String[] hives = {"user.reg", "system.reg"};
        for (String hive : hives) {
            File file = new File(root, ".wine/" + hive);
            if (!file.isFile()) { report.put(hive, new JSONObject().put("available", false)); continue; }
            JSONObject values = new JSONObject();
            try (WineRegistryEditor registry = new WineRegistryEditor(file)) {
                String[] keys = hive.equals("user.reg")
                        ? new String[]{"Software\\Blizzard Entertainment\\Diablo II"}
                        : new String[]{"Software\\Blizzard Entertainment\\Diablo II",
                                "Software\\Wow6432Node\\Blizzard Entertainment\\Diablo II"};
                for (String key : keys) {
                    JSONObject paths = new JSONObject();
                    for (String name : new String[]{"InstallPath", "GamePath"}) {
                        String value = registry.getStringValue(key, name);
                        paths.put(name, value == null ? JSONObject.NULL : value);
                    }
                    values.put(key, paths);
                }
                if (hive.equals("user.reg")) {
                    for (String key : new String[]{"Software\\Wine", "Software\\Wine\\AppDefaults\\Game.exe"}) {
                        String version = registry.getStringValue(key, "Version");
                        values.put(key, new JSONObject().put("Version", version == null ? JSONObject.NULL : version));
                    }
                    String filter = registry.getStringValue("Software\\Wine\\Debug", "SnoopInclude");
                    values.put("SnoopInclude", filter == null ? JSONObject.NULL : filter);
                }
            }
            report.put(hive, values);
        }
        return report;
    }

    private static void writeAndVerifyRegistry(File hive, String[] keys, String installPath, String gamePath) {
        if (!hive.isFile()) throw new IllegalStateException("The private Wine registry is missing");
        try (WineRegistryEditor registry = new WineRegistryEditor(hive)) {
            for (String key : keys) {
                if (!installPath.equals(registry.getStringValue(key, "InstallPath")))
                    registry.setStringValue(key, "InstallPath", installPath);
                if (!gamePath.equals(registry.getStringValue(key, "GamePath")))
                    registry.setStringValue(key, "GamePath", gamePath);
            }
        }
        // The upstream editor reports write failures silently; persisted readback
        // stops startup if an interrupted write did not save the expected paths.
        try (WineRegistryEditor registry = new WineRegistryEditor(hive)) {
            for (String key : keys) {
                if (!installPath.equals(registry.getStringValue(key, "InstallPath"))
                        || !gamePath.equals(registry.getStringValue(key, "GamePath"))) {
                    throw new IllegalStateException("Cannot prepare the private Diablo II registry paths");
                }
            }
        }
    }

    private static boolean isRenderer(String renderer) {
        return TURNIP_ZINK.equals(renderer) || TURNIP_VIRGL.equals(renderer);
    }

    static boolean isManagedAndReady(Container container) {
        if (container == null || !"1".equals(container.getExtra(MANAGED))
                || !RUNTIME_REVISION.equals(container.getExtra(REVISION))
                || !WineInfo.MAIN_WINE_INFO.identifier().equals(container.getWineVersion())) return false;
        return hasPrefixFiles(container);
    }

    private static boolean hasPrefixFiles(Container container) {
        File root = container.getRootDir();
        return root != null && container.getConfigFile().isFile()
                && new File(root, ".wine/user.reg").isFile()
                && new File(root, ".wine/system.reg").isFile()
                && new File(root, ".wine/drive_c/windows/system32/kernel32.dll").isFile()
                && new File(root, ".wine/drive_c/windows/syswow64/ntdll.dll").isFile()
                && new File(root, ".wine/drive_c/windows/syswow64/xinput1_3.dll").isFile()
                && new File(root, ".wine/drive_c/windows/system32/drivers/winexinput.sys").isFile();
    }

    private static JSONObject defaults(Context context) throws JSONException {
        return new JSONObject()
                .put("name", CONTAINER_NAME)
                .put("wineVersion", WineInfo.MAIN_WINE_INFO.identifier())
                .put("screenSize", "1280x720")
                .put("envVars", environment(context))
                .put("graphicsDriver", DEFAULT_RENDERER)
                .put("graphicsDriverConfig", "")
                .put("dxwrapper", DXWrappers.WINED3D)
                .put("dxwrapperConfig", "")
                .put("audioDriver", AudioDrivers.ALSA)
                .put("audioDriverConfig", "")
                .put("wincomponents", WIN_COMPONENTS)
                .put("drives", "P:" + Pd2Installer.installedDirectory(context).getAbsolutePath())
                .put("hudMode", 0)
                .put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL)
                .put("extraData", new JSONObject().put(MANAGED, "1")
                        .put("pd2ControllerNotifications", Pd2ControllerRuntime.enabled(context) ? "1" : "0"))
                .put("box64Preset", Pd2LaunchPolicy.cpuPreset())
                .put("desktopTheme", WineThemeManager.DEFAULT_DESKTOP_THEME);
    }

    private static String environment(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return Pd2LaunchPolicy.environment(preferences.getString("pd2_arguments", "-3dfx -w"),
                preferences.getBoolean(Pd2LaunchPolicy.CPU_PREFERENCE, false));
    }

    private static void deliver(Container result) {
        creating = false;
        ArrayList<Callback<Container>> callbacks = new ArrayList<>(WAITING);
        WAITING.clear();
        for (Callback<Container> callback : callbacks) callback.call(result);
    }
}
