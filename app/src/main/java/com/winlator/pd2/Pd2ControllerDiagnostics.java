package com.winlator.pd2;

import android.content.Context;
import android.view.InputDevice;
import android.view.MotionEvent;

import com.winlator.inputcontrols.ExternalController;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.UUID;

/** Device capabilities and aggregate bridge counts, never key presses or axis values. */
public final class Pd2ControllerDiagnostics {
    public static final int MAX_REPORT_BYTES = 64 * 1024;
    public static final int MAX_DEVICES = 32;
    private static final Object FILE_LOCK = new Object();
    private final Context context;
    private final String sessionId = UUID.randomUUID().toString();
    private final long createdAt = System.currentTimeMillis();
    private String launchId = "";
    private String mode = "native";
    private boolean inputAvailable = true;
    private boolean nativeInputEnabled = true;
    private boolean socketReady;
    private boolean initReceived;
    private String selectedDevice;
    private long initMessages, dinputRequests, xinputRequests, otherRequests, statePolls;
    private long deviceReplies, stateReplies, replyFailures, invalidPackets, socketFailures;
    private long motionEvents, handledMotionEvents, keyEvents, handledKeyEvents;
    private long legacyXInputDiscovery, legacyDInputDiscovery, notifySubscriptions;
    private long hidDiscoveryRequests, hidDeviceReplies, hidStateReplies;
    private long lastHandledMotionAt, lastHandledKeyAt, lastHidReplyAt, lastXInputReplyAt;
    private boolean gateKnown, windowFocus, paused, quickMenu, drawer;
    private final ArrayDeque<JSONObject> recentTransitions = new ArrayDeque<>();

    public Pd2ControllerDiagnostics(Context context) {
        // WinHandler is an Activity field initialized before attachBaseContext.
        // Keep the reference without touching the not-yet-attached context.
        this.context = context;
    }

    public static File getFile(Context context) {
        return new File(context.getFilesDir(), "pd2/logs/controller.json");
    }

    public static void clearPreviousReport(Context context) {
        synchronized (FILE_LOCK) { getFile(context).delete(); }
    }

    public synchronized void setMode(String mode, boolean inputAvailable) {
        String selected = "menu_cursor".equals(mode) ? "menu_cursor" : "mouse_keyboard".equals(mode) ? "mouse_keyboard" : "native";
        boolean changed = !this.mode.equals(selected) || this.inputAvailable != inputAvailable;
        this.mode = selected;
        this.inputAvailable = inputAvailable;
        if (changed || recentTransitions.isEmpty()) transition("mode");
    }

    public synchronized void setInputGate(boolean focus, boolean paused, boolean quickMenu, boolean drawer) {
        boolean changed = !gateKnown || windowFocus != focus || this.paused != paused
                || this.quickMenu != quickMenu || this.drawer != drawer;
        gateKnown = true;
        windowFocus = focus;
        this.paused = paused;
        this.quickMenu = quickMenu;
        this.drawer = drawer;
        if (changed) transition("gate");
    }

    private void transition(String event) {
        try {
            if (recentTransitions.size() == 32) recentTransitions.removeFirst();
            recentTransitions.addLast(new JSONObject().put("at", System.currentTimeMillis()).put("event", event)
                    .put("mode", mode).put("inputAvailable", inputAvailable).put("gateKnown", gateKnown)
                    .put("windowFocus", windowFocus).put("paused", paused).put("quickMenu", quickMenu).put("drawer", drawer));
        }
        catch (JSONException ignored) { }
    }

    public synchronized void setLaunchId(String value) {
        launchId = value != null && value.length() <= 128 ? value : "";
    }

    public synchronized void setNativeInputEnabled(boolean enabled) { nativeInputEnabled = enabled; }
    public synchronized void setSocketReady(boolean ready) { socketReady = ready; }
    public synchronized void setSelectedDevice(String name) { selectedDevice = bounded(name); }
    public synchronized void recordInit() { initReceived = true; initMessages = increment(initMessages); }
    public synchronized void recordInvalidPacket() { invalidPackets = increment(invalidPackets); }
    public synchronized void recordSocketFailure() { socketFailures = increment(socketFailures); }

    public synchronized void recordRequest(byte code, int port) {
        if (code == 8) {
            if (port == 7948) dinputRequests = increment(dinputRequests);
            else if (port == 7949) xinputRequests = increment(xinputRequests);
            else if (port == 7950) hidDiscoveryRequests = increment(hidDiscoveryRequests);
            else otherRequests = increment(otherRequests);
        }
        else if (code == 9) statePolls = increment(statePolls);
    }

    public synchronized void recordLegacyDiscovery(boolean xinput, boolean notify) {
        if (xinput) legacyXInputDiscovery = increment(legacyXInputDiscovery);
        else legacyDInputDiscovery = increment(legacyDInputDiscovery);
        if (notify) notifySubscriptions = increment(notifySubscriptions);
    }

    public synchronized void recordReply(byte code, int port, boolean success) {
        if (code != 8 && code != 9) return;
        if (!success) replyFailures = increment(replyFailures);
        else if (code == 8) {
            deviceReplies = increment(deviceReplies);
            if (port == 7950) hidDeviceReplies = increment(hidDeviceReplies);
        }
        else {
            stateReplies = increment(stateReplies);
            if (port == 7950) hidStateReplies = increment(hidStateReplies);
            if (port == 7950) lastHidReplyAt = System.currentTimeMillis();
            else if (port == 7949) lastXInputReplyAt = System.currentTimeMillis();
        }
    }

    public synchronized void recordMotion(boolean handled) {
        if (handled) { handledMotionEvents = increment(handledMotionEvents); lastHandledMotionAt = System.currentTimeMillis(); }
        else motionEvents = increment(motionEvents);
    }

    public synchronized void recordKey(boolean handled) {
        if (handled) { handledKeyEvents = increment(handledKeyEvents); lastHandledKeyAt = System.currentTimeMillis(); }
        else keyEvents = increment(keyEvents);
    }

    /** Snapshot is safe to show in the quick menu or include in a support export. */
    public JSONObject snapshot() {
        JSONObject report = new JSONObject();
        try {
            synchronized (this) {
                JSONArray history = new JSONArray();
                for (JSONObject entry : recentTransitions) history.put(new JSONObject(entry.toString()));
                report.put("sessionId", sessionId).put("launchId", launchId).put("createdAt", createdAt)
                        .put("capturedAt", System.currentTimeMillis())
                        .put("scope", "Android input capabilities, counters, mode history and event times; no pressed keys or axis values")
                        .put("mode", mode).put("inputAvailable", inputAvailable)
                        .put("recentTransitions", history)
                        .put("inputGate", new JSONObject().put("known", gateKnown).put("windowFocus", windowFocus)
                                .put("paused", paused).put("quickMenu", quickMenu).put("drawer", drawer))
                        .put("lastEvents", new JSONObject().put("handledMotionAt", lastHandledMotionAt)
                                .put("handledKeyAt", lastHandledKeyAt).put("hidStateReplyAt", lastHidReplyAt)
                                .put("xinputStateReplyAt", lastXInputReplyAt))
                        .put("nativeBridge", "legacy_xinput_7949_and_hid_7950")
                        .put("bridgeRevision", "java-hid-7950-v1")
                        .put("nativeInputEnabled", nativeInputEnabled)
                        .put("socketReady", socketReady).put("winHandlerInitReceived", initReceived)
                        .put("selectedDevice", selectedDevice == null ? JSONObject.NULL : selectedDevice)
                        .put("counts", new JSONObject().put("initMessages", initMessages)
                                .put("dinputRequests7948", dinputRequests).put("xinputRequests7949", xinputRequests)
                                .put("hidDiscoveryRequests7950", hidDiscoveryRequests)
                                .put("hidDeviceReplies7950", hidDeviceReplies).put("hidStateReplies7950", hidStateReplies)
                                .put("otherGamepadRequests", otherRequests).put("statePolls", statePolls)
                                .put("legacyXInputDiscovery", legacyXInputDiscovery)
                                .put("legacyDInputDiscovery", legacyDInputDiscovery)
                                .put("notifySubscriptions", notifySubscriptions)
                                .put("deviceReplies", deviceReplies).put("stateReplies", stateReplies)
                                .put("replyFailures", replyFailures).put("invalidPackets", invalidPackets)
                                .put("socketFailures", socketFailures).put("motionEvents", motionEvents)
                                .put("handledMotionEvents", handledMotionEvents)
                                .put("keyEvents", keyEvents).put("handledKeyEvents", handledKeyEvents));
            }
            JSONArray devices = new JSONArray();
            int[] ids = InputDevice.getDeviceIds();
            Arrays.sort(ids);
            report.put("totalDevices", ids.length).put("devicesTruncated", ids.length > MAX_DEVICES);
            for (int index = 0; index < Math.min(ids.length, MAX_DEVICES); index++) {
                InputDevice device = InputDevice.getDevice(ids[index]);
                if (device == null) continue;
                JSONObject capabilities = new JSONObject().put("id", device.getId()).put("name", bounded(device.getName()))
                        .put("sources", device.getSources()).put("virtual", device.isVirtual())
                        .put("vendorId", device.getVendorId()).put("productId", device.getProductId());
                try {
                    boolean keys = ExternalController.hasGamepadKeys(device);
                    boolean x = ExternalController.hasControllerAxis(device, MotionEvent.AXIS_X);
                    boolean y = ExternalController.hasControllerAxis(device, MotionEvent.AXIS_Y);
                    capabilities.put("hasGamepadKeys", keys).put("controllerAxisX", x).put("controllerAxisY", y)
                            .put("controllerAxisZ", ExternalController.hasControllerAxis(device, MotionEvent.AXIS_Z))
                            .put("controllerAxisRZ", ExternalController.hasControllerAxis(device, MotionEvent.AXIS_RZ))
                            .put("controllerAxisRX", ExternalController.hasControllerAxis(device, MotionEvent.AXIS_RX))
                            .put("controllerAxisRY", ExternalController.hasControllerAxis(device, MotionEvent.AXIS_RY))
                            .put("accepted", ExternalController.acceptsController(device.getName(), device.isVirtual(),
                                    device.getSources(), keys, x || y));
                }
                catch (RuntimeException error) {
                    capabilities.put("accepted", false).put("capabilityError", error.getClass().getSimpleName());
                }
                devices.put(capabilities);
            }
            report.put("devices", devices);
        }
        catch (JSONException | RuntimeException error) {
            try { report.put("snapshotError", error.getClass().getSimpleName()); }
            catch (JSONException ignored) { }
        }
        return report;
    }

    /** Persist on active/paused/stopped boundaries; input events only update memory counters. */
    public void save() {
        if (context == null) return;
        JSONObject report = snapshot();
        String id = report.optString("launchId", "");
        if (id.isEmpty()) return;
        byte[] bytes = report.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REPORT_BYTES) return;
        final File target;
        try { target = getFile(context); }
        catch (RuntimeException notAttached) { return; }
        synchronized (FILE_LOCK) {
            File parent = target.getParentFile();
            File temporary = new File(parent, "controller.json.tmp");
            try {
                File launch = new File(parent, "launch.json");
                if (!matchesLaunch(launch, id)) return;
                if (!parent.isDirectory() && !parent.mkdirs()) return;
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(bytes);
                    output.getFD().sync();
                }
                if (matchesLaunch(launch, id)) temporary.renameTo(target);
            }
            catch (IOException | RuntimeException ignored) { }
            finally { temporary.delete(); }
        }
    }

    private static boolean matchesLaunch(File launch, String id) throws IOException {
        if (!launch.isFile() || launch.length() > MAX_REPORT_BYTES) return false;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(launch)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (bytes.size() + read > MAX_REPORT_BYTES) return false;
                bytes.write(buffer, 0, read);
            }
        }
        try { return id.equals(new JSONObject(bytes.toString("UTF-8")).optString("launchId", "")); }
        catch (JSONException malformed) { return false; }
    }

    private static String bounded(String value) {
        return value == null ? null : value.substring(0, Math.min(value.length(), 96));
    }

    private static long increment(long value) { return value == Long.MAX_VALUE ? value : value + 1; }
}
