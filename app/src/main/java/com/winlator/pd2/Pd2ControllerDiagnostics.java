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
import java.util.ArrayList;
import java.util.UUID;

/** Bounded capabilities, bridge counts and pointer routing context; never pressed keys or axis values. */
public final class Pd2ControllerDiagnostics {
    public static final int MAX_REPORT_BYTES = 64 * 1024;
    public static final int MAX_DEVICES = 32;
    public static final int MAX_LEGACY_CLIENTS = 16;
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
    private final ArrayList<LegacyClient> legacyClients = new ArrayList<>(MAX_LEGACY_CLIENTS);
    private long legacyClientOverflowRequests, invalidLegacyDiscoveries;
    private final long[] controllerRequestSources = new long[4];
    private final long[] controllerReplyDestinations = new long[4];
    private int connectedPhysicalControllers, assignedSlots, assignedPhysicalSlots;
    private int distinctAssignedAndroidDevices, assignedVirtualSlots;
    private boolean slotTopologyKnown, connectedPhysicalControllersTruncated;
    private long slotTopologyUpdatedAt;
    private long hidDiscoveryRequests, hidDeviceReplies, hidStateReplies;
    private long lastHandledMotionAt, lastHandledKeyAt, lastHidReplyAt, lastXInputReplyAt;
    private long nativeFocusRequests, lastNativeFocusRequestAt;
    private String lastNativeFocusReason = "";
    private long reconnectRequests, reconnectDetachSent, reconnectAttachSent, reconnectCompleted;
    private long reconnectCancelled, reconnectUnavailable, reconnectSendFailures, reconnectTimedOut, lastReconnectAt;
    private String lastReconnectPhase = "";
    private long hidNonNeutralStateSent, hidNeutralStateSent, xinputNonNeutralStateSent, xinputNeutralStateSent;
    private long nativeStateSendFailures, lastNonNeutralStateSentAt;
    private long producerGeneration, androidDeviceChanges;
    private int selectedAndroidDeviceId = -1;
    private long identityRequests, identityDetachSent, identityDetachObserved, identityAttachSent, identityBackendObserved;
    private long identityStartedObserved, identityCancelled, identityUnavailable, identityTimedOut, identitySendFailures;
    private String lastIdentityPhase = "";
    private long lastIdentityAt;
    private int hidDeviceUid;
    private JSONObject latestHidBackend;
    private long hidBackendIdentityChanges, hidOutOfOrderAcks;
    private JSONObject latestRejectedHidBackend;
    private boolean gateKnown, windowFocus, paused, quickMenu, drawer;
    private final ArrayDeque<JSONObject> recentTransitions = new ArrayDeque<>();
    private final ArrayDeque<JSONObject> recentPointerContexts = new ArrayDeque<>();
    private static final String[] MODES = {"native", "menu_cursor", "mouse_keyboard"};
    private final ModeInput[] inputByMode = {new ModeInput(), new ModeInput(), new ModeInput()};
    private final PointerOutput pointerOutput = new PointerOutput();

    private static final class LegacyClient {
        final int windowsProcessId;
        final long firstSeenAt = System.currentTimeMillis();
        long lastSeenAt, xinputDiscoveries, dinputDiscoveries, notifyDiscoveries;
        long source7948Requests, source7949Requests;

        LegacyClient(int pid) { windowsProcessId = pid; }

        void record(boolean xinput, boolean notify, int sourcePort) {
            lastSeenAt = System.currentTimeMillis();
            if (xinput) xinputDiscoveries = increment(xinputDiscoveries);
            else dinputDiscoveries = increment(dinputDiscoveries);
            if (notify) notifyDiscoveries = increment(notifyDiscoveries);
            if (sourcePort == 7948) source7948Requests = increment(source7948Requests);
            else source7949Requests = increment(source7949Requests);
        }

        JSONObject snapshot() throws JSONException {
            return new JSONObject().put("windowsProcessId", windowsProcessId)
                    .put("firstSeenAt", firstSeenAt).put("lastSeenAt", lastSeenAt)
                    .put("xinputDiscoveries", xinputDiscoveries).put("dinputDiscoveries", dinputDiscoveries)
                    .put("notifyDiscoveries", notifyDiscoveries)
                    .put("source7948Requests", source7948Requests).put("source7949Requests", source7949Requests);
        }
    }

    private static final class ModeInput {
        long handledMotionEvents, handledKeyEvents, lastHandledMotionAt, lastHandledKeyAt;
        final PointerOutput pointerOutput = new PointerOutput();
        final PointerRouting pointerRouting = new PointerRouting();
        JSONObject latestPointerContext;

        JSONObject snapshot() throws JSONException {
            return new JSONObject().put("handledMotionEvents", handledMotionEvents).put("handledKeyEvents", handledKeyEvents)
                    .put("lastHandledMotionAt", lastHandledMotionAt).put("lastHandledKeyAt", lastHandledKeyAt)
                    .put("pointerOutput", pointerOutput.snapshot()).put("pointerRouting", pointerRouting.snapshot())
                    .put("latestPointerContext", latestPointerContext == null ? JSONObject.NULL
                            : new JSONObject(latestPointerContext.toString()));
        }
    }

    private static final class PointerRouting {
        private static final String[] SOURCES = {"captured", "external", "touch"};
        private static final String[] KINDS = {"disabled", "zero", "move", "button", "scroll"};
        private final long[][] counts = new long[SOURCES.length][KINDS.length];
        private long lastEmissionAt;

        void record(String source, String kind, long at) {
            for (int sourceIndex = 0; sourceIndex < SOURCES.length; sourceIndex++) {
                if (!SOURCES[sourceIndex].equals(source)) continue;
                for (int kindIndex = 0; kindIndex < KINDS.length; kindIndex++) {
                    if (!KINDS[kindIndex].equals(kind)) continue;
                    if (kindIndex < 2 && sourceIndex != 0) return;
                    counts[sourceIndex][kindIndex] = increment(counts[sourceIndex][kindIndex]);
                    if (kindIndex >= 2) lastEmissionAt = at;
                    return;
                }
            }
        }

        JSONObject snapshot() throws JSONException {
            JSONObject result = new JSONObject();
            for (int source = 0; source < SOURCES.length; source++) {
                JSONObject counters = new JSONObject();
                for (int kind = source == 0 ? 0 : 2; kind < KINDS.length; kind++)
                    counters.put(KINDS[kind] + "Events", counts[source][kind]);
                result.put(SOURCES[source], counters);
            }
            return result.put("lastEmissionAt", lastEmissionAt)
                    .put("scope", "Touch, external and captured pointer emission categories; no coordinates, device IDs or game acknowledgment");
        }
    }

    private static final class PointerOutput {
        long moveEvents, buttonEvents, keyEvents, feedbackEvents;
        long lastMoveAt, lastButtonAt, lastKeyAt, lastFeedbackAt;

        void record(String kind, long at) {
            switch (kind) {
                case "move": moveEvents = increment(moveEvents); lastMoveAt = at; break;
                case "button": buttonEvents = increment(buttonEvents); lastButtonAt = at; break;
                case "key": keyEvents = increment(keyEvents); lastKeyAt = at; break;
                case "feedback": feedbackEvents = increment(feedbackEvents); lastFeedbackAt = at; break;
            }
        }

        JSONObject snapshot() throws JSONException {
            return new JSONObject().put("moveEvents", moveEvents).put("buttonEvents", buttonEvents)
                    .put("keyEvents", keyEvents).put("feedbackEvents", feedbackEvents)
                    .put("lastMoveAt", lastMoveAt).put("lastButtonAt", lastButtonAt)
                    .put("lastKeyAt", lastKeyAt).put("lastFeedbackAt", lastFeedbackAt);
        }
    }

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
    public synchronized void setProducerGeneration(long generation) { producerGeneration = generation; }
    public synchronized void setSelectedAndroidDevice(int id) {
        if (selectedAndroidDeviceId != id) androidDeviceChanges = increment(androidDeviceChanges);
        selectedAndroidDeviceId = id;
    }
    public synchronized void setHidDeviceUid(int uid) { hidDeviceUid = uid; }

    /** Latest Java slot assignment only; it does not count Windows enumeration or accepted input. */
    public synchronized void setControllerSlotTopology(int connectedPhysical, int slots, int physicalSlots,
            int distinctAndroidDevices, int virtualSlots) {
        if (connectedPhysical < 0 || slots < 0 || slots > 4 || physicalSlots < 0 || physicalSlots > slots
                || distinctAndroidDevices < 0 || distinctAndroidDevices > physicalSlots
                || virtualSlots < 0 || virtualSlots != slots - physicalSlots) return;
        slotTopologyKnown = true;
        connectedPhysicalControllers = Math.min(connectedPhysical, MAX_DEVICES);
        connectedPhysicalControllersTruncated = connectedPhysical > MAX_DEVICES;
        assignedSlots = slots;
        assignedPhysicalSlots = physicalSlots;
        distinctAssignedAndroidDevices = distinctAndroidDevices;
        assignedVirtualSlots = virtualSlots;
        slotTopologyUpdatedAt = System.currentTimeMillis();
    }

    /** An observed Unix HID callback is not a PD2 input or Windows enumeration acknowledgement. */
    public synchronized void recordNativeIdentityRecovery(String phase) {
        if (phase == null) return;
        switch (phase) {
            case "requested": identityRequests = increment(identityRequests); break;
            case "detachSent": identityDetachSent = increment(identityDetachSent); break;
            case "detachObserved": identityDetachObserved = increment(identityDetachObserved); break;
            case "attachSent": identityAttachSent = increment(identityAttachSent); break;
            case "backendObserved": identityBackendObserved = increment(identityBackendObserved); break;
            case "deviceStartObserved": identityStartedObserved = increment(identityStartedObserved); break;
            case "cancelled": identityCancelled = increment(identityCancelled); break;
            case "unavailable": identityUnavailable = increment(identityUnavailable); break;
            case "timedOut": identityTimedOut = increment(identityTimedOut); break;
            case "sendFailure": identitySendFailures = increment(identitySendFailures); break;
            default: return;
        }
        lastIdentityPhase = phase;
        lastIdentityAt = System.currentTimeMillis();
    }

    public synchronized void recordHidBackend(int uid, int pid, int flags, int stage, long socketInode,
            long monotonicMillis, long created, long removed, long started, long stopped,
            long stateReceived, long reportsQueued, long invalidPackets) {
        try {
            latestHidBackend = new JSONObject().put("deviceUid", uid).put("pid", pid).put("flags", flags)
                    .put("stage", stage).put("socketInode", socketInode).put("monotonicMillis", monotonicMillis)
                    .put("created", created).put("removed", removed).put("started", started).put("stopped", stopped)
                    .put("stateReceived", stateReceived).put("reportsQueued", reportsQueued)
                    .put("invalidPackets", invalidPackets).put("receivedAt", System.currentTimeMillis());
        } catch (JSONException ignored) { }
    }

    public synchronized void recordRejectedHidBackend(String reason, int pid, long socketInode, int uid) {
        if ("identityChanged".equals(reason)) hidBackendIdentityChanges = increment(hidBackendIdentityChanges);
        else if ("outOfOrder".equals(reason)) hidOutOfOrderAcks = increment(hidOutOfOrderAcks);
        else return;
        try {
            latestRejectedHidBackend = new JSONObject().put("reason", reason).put("pid", pid)
                    .put("socketInode", socketInode).put("deviceUid", uid).put("receivedAt", System.currentTimeMillis())
                    .put("previousPid", latestHidBackend == null ? JSONObject.NULL : latestHidBackend.optInt("pid"))
                    .put("previousSocketInode", latestHidBackend == null ? JSONObject.NULL : latestHidBackend.optLong("socketInode"));
        } catch (JSONException ignored) { }
    }
    public synchronized void recordInit() { initReceived = true; initMessages = increment(initMessages); }
    public synchronized void recordInvalidPacket() { invalidPackets = increment(invalidPackets); }
    public synchronized void recordSocketFailure() { socketFailures = increment(socketFailures); }

    /** Records a queued foreground request, not proof that Windows or PD2 accepted focus. */
    public synchronized void recordNativeFocusRequest(String reason) {
        if (!"route".equals(reason) && !"window".equals(reason) && !"settle".equals(reason)) return;
        nativeFocusRequests = increment(nativeFocusRequests);
        lastNativeFocusRequestAt = System.currentTimeMillis();
        lastNativeFocusReason = reason;
    }

    public synchronized void recordNativeReconnect(String phase) {
        if (phase == null) return;
        switch (phase) {
            case "requested": reconnectRequests = increment(reconnectRequests); break;
            case "detachSent": reconnectDetachSent = increment(reconnectDetachSent); break;
            case "attachSent": reconnectAttachSent = increment(reconnectAttachSent); break;
            case "completed": reconnectCompleted = increment(reconnectCompleted); break;
            case "cancelled": reconnectCancelled = increment(reconnectCancelled); break;
            case "unavailable": reconnectUnavailable = increment(reconnectUnavailable); break;
            case "sendFailure": reconnectSendFailures = increment(reconnectSendFailures); break;
            case "timedOut": reconnectTimedOut = increment(reconnectTimedOut); break;
            default: return;
        }
        lastReconnectPhase = phase;
        lastReconnectAt = System.currentTimeMillis();
    }

    public synchronized void recordNativeStateDelivery(int port, boolean nonNeutral, boolean success) {
        if (port != 7950 && port != 7949) return;
        if (!success) { nativeStateSendFailures = increment(nativeStateSendFailures); return; }
        if (port == 7950) {
            if (nonNeutral) hidNonNeutralStateSent = increment(hidNonNeutralStateSent);
            else hidNeutralStateSent = increment(hidNeutralStateSent);
        } else {
            if (nonNeutral) xinputNonNeutralStateSent = increment(xinputNonNeutralStateSent);
            else xinputNeutralStateSent = increment(xinputNeutralStateSent);
        }
        if (nonNeutral) lastNonNeutralStateSentAt = System.currentTimeMillis();
    }

    public synchronized void recordRequest(byte code, int port) {
        if (code == 8 || code == 9) {
            int category = controllerPortCategory(port);
            controllerRequestSources[category] = increment(controllerRequestSources[category]);
        }
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

    /**
     * Retain the first sixteen distinct declared Windows PIDs for this diagnostics session.
     * This observes existing discovery packets; no helper process opens an XInput DLL.
     * The declared PID is not authenticated Linux socket ownership, and overflow counts requests.
     */
    public synchronized boolean recordLegacyDiscovery(boolean xinput, boolean notify, int windowsProcessId,
            int sourcePort) {
        if (windowsProcessId <= 0 || (sourcePort != 7948 && sourcePort != 7949)) {
            recordInvalidLegacyDiscovery();
            return false;
        }
        recordLegacyDiscovery(xinput, notify);
        for (LegacyClient client : legacyClients) {
            if (client.windowsProcessId == windowsProcessId) {
                client.record(xinput, notify, sourcePort);
                return true;
            }
        }
        if (legacyClients.size() == MAX_LEGACY_CLIENTS) {
            legacyClientOverflowRequests = increment(legacyClientOverflowRequests);
            return true;
        }
        LegacyClient client = new LegacyClient(windowsProcessId);
        client.record(xinput, notify, sourcePort);
        legacyClients.add(client);
        return true;
    }

    public synchronized void recordInvalidLegacyDiscovery() {
        invalidLegacyDiscoveries = increment(invalidLegacyDiscoveries);
    }

    public synchronized void recordReply(byte code, int port, boolean success) {
        if (code != 8 && code != 9) return;
        int category = controllerPortCategory(port);
        controllerReplyDestinations[category] = increment(controllerReplyDestinations[category]);
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

    private static int controllerPortCategory(int port) {
        return port == 7948 ? 0 : port == 7949 ? 1 : port == 7950 ? 2 : 3;
    }

    private static JSONObject controllerPortCounts(long[] counts) throws JSONException {
        return new JSONObject().put("legacyDinput7948", counts[0]).put("legacyXinput7949", counts[1])
                .put("hid7950", counts[2]).put("other", counts[3]);
    }

    private JSONObject controllerExposureSnapshot() throws JSONException {
        JSONArray clients = new JSONArray();
        int xinputClients = 0, dinputClients = 0;
        for (LegacyClient client : legacyClients) {
            clients.put(client.snapshot());
            if (client.xinputDiscoveries > 0) xinputClients++;
            if (client.dinputDiscoveries > 0) dinputClients++;
        }
        return new JSONObject().put("slotTopologyKnown", slotTopologyKnown)
                .put("slotTopologyUpdatedAt", slotTopologyUpdatedAt)
                .put("connectedPhysicalControllers", connectedPhysicalControllers)
                .put("connectedPhysicalControllersTruncated", connectedPhysicalControllersTruncated)
                .put("assignedSlots", assignedSlots).put("assignedPhysicalSlots", assignedPhysicalSlots)
                .put("distinctAssignedAndroidDevices", distinctAssignedAndroidDevices)
                .put("assignedVirtualSlots", assignedVirtualSlots)
                .put("legacyClients", clients).put("trackedLegacyClientCount", legacyClients.size())
                .put("trackedXinputClientCount", xinputClients).put("trackedDinputClientCount", dinputClients)
                .put("maxLegacyClients", MAX_LEGACY_CLIENTS)
                .put("legacyClientOverflowRequests", legacyClientOverflowRequests)
                .put("invalidLegacyDiscoveries", invalidLegacyDiscoveries)
                .put("requestSourceCategories", controllerPortCounts(controllerRequestSources))
                .put("replyDestinationCategories", controllerPortCounts(controllerReplyDestinations))
                .put("producerPort", 7947)
                .put("scope", "Java slot topology and bounded existing UDP discovery metadata; declared Windows PIDs are not authenticated Linux socket owners or simultaneous listeners. Retained per session; overflow counts requests, reply destinations include failed sends. No helper process loads XInput; no input values or session tokens.");
    }

    public synchronized void recordMotion(boolean handled) {
        if (handled) {
            handledMotionEvents = increment(handledMotionEvents); lastHandledMotionAt = System.currentTimeMillis();
            ModeInput selected = inputByMode[modeIndex()];
            selected.handledMotionEvents = increment(selected.handledMotionEvents);
            selected.lastHandledMotionAt = lastHandledMotionAt;
        }
        else motionEvents = increment(motionEvents);
    }

    public synchronized void recordKey(boolean handled) {
        if (handled) {
            handledKeyEvents = increment(handledKeyEvents); lastHandledKeyAt = System.currentTimeMillis();
            ModeInput selected = inputByMode[modeIndex()];
            selected.handledKeyEvents = increment(selected.handledKeyEvents);
            selected.lastHandledKeyAt = lastHandledKeyAt;
        }
        else keyEvents = increment(keyEvents);
    }

    /** Aggregate output categories only: no button code, key code or input value is retained. */
    public synchronized void recordPointerOutput(String kind) {
        if (!"move".equals(kind) && !"button".equals(kind) && !"key".equals(kind) && !"feedback".equals(kind)) return;
        long at = System.currentTimeMillis();
        pointerOutput.record(kind, at);
        inputByMode[modeIndex()].pointerOutput.record(kind, at);
    }

    /** Includes X11 pointer paths that are separate from the temporary menu helper. */
    public synchronized void recordPointerRoute(String source, String kind) {
        inputByMode[modeIndex()].pointerRouting.record(source, kind, System.currentTimeMillis());
    }

    /** Copy only pointer geometry and fixed routing metadata, dropping titles, paths and other fields. */
    public synchronized void recordPointerContext(JSONObject state) {
        if (state == null) return;
        try {
            JSONObject clean = new JSONObject();
            for (String name : new String[]{"screenWidth", "screenHeight"}) copyInteger(state, clean, name, 0, 65536);
            for (String name : new String[]{"pointerX", "pointerY"}) copyInteger(state, clean, name, -65536, 65536);
            for (String name : new String[]{"relative", "gameCursorVisible", "forceRoot", "rootCursorVisible", "cursorOverlayVisible"}) {
                Object value = state.opt(name);
                if (value instanceof Boolean) clean.put(name, value);
            }
            for (String name : new String[]{"focusWindow", "pointWindow", "grabWindow", "menuWindow"}) {
                Object value = state.opt(name);
                if (value == JSONObject.NULL) clean.put(name, JSONObject.NULL);
                else if (value instanceof JSONObject) {
                    JSONObject window = new JSONObject();
                    JSONObject source = (JSONObject)value;
                    copyInteger(source, window, "id", Integer.MIN_VALUE, 0xffffffffL);
                    for (String size : new String[]{"width", "height"}) copyInteger(source, window, size, 0, 65536);
                    for (String position : new String[]{"x", "y"}) copyInteger(source, window, position, -65536, 65536);
                    Object windowClass = source.opt("class");
                    if (windowClass instanceof String && ((String)windowClass).matches("[A-Za-z0-9_. -]{1,96}"))
                        window.put("class", windowClass);
                    if (window.length() > 0) clean.put(name, window);
                }
            }
            if (clean.length() == 0) return;
            clean.put("at", System.currentTimeMillis()).put("mode", mode);
            inputByMode[modeIndex()].latestPointerContext = clean;
            if (recentPointerContexts.size() == 16) recentPointerContexts.removeFirst();
            recentPointerContexts.addLast(clean);
        } catch (JSONException ignored) { }
    }

    private int modeIndex() { return "menu_cursor".equals(mode) ? 1 : "mouse_keyboard".equals(mode) ? 2 : 0; }

    private static void copyInteger(JSONObject source, JSONObject target, String name, long min, long max) throws JSONException {
        Object value = source.opt(name);
        if (!(value instanceof Number)) return;
        double numeric = ((Number)value).doubleValue();
        if (Double.isNaN(numeric) || Double.isInfinite(numeric) || numeric < min || numeric > max || numeric != Math.rint(numeric)) return;
        target.put(name, (long)numeric);
    }

    /** Snapshot is safe to show in the quick menu or include in a support export. */
    public JSONObject snapshot() {
        JSONObject report = new JSONObject();
        try {
            synchronized (this) {
                JSONArray history = new JSONArray();
                for (JSONObject entry : recentTransitions) history.put(new JSONObject(entry.toString()));
                JSONArray pointerContexts = new JSONArray();
                for (JSONObject entry : recentPointerContexts) pointerContexts.put(new JSONObject(entry.toString()));
                JSONObject modes = new JSONObject();
                for (int index = 0; index < MODES.length; index++) modes.put(MODES[index], inputByMode[index].snapshot());
                report.put("sessionId", sessionId).put("launchId", launchId).put("createdAt", createdAt)
                        .put("capturedAt", System.currentTimeMillis())
                        .put("scope", "Android input capabilities, counters, mode history, pointer geometry and routing; no pressed keys, axis values or window titles")
                        .put("mode", mode).put("inputAvailable", inputAvailable)
                        .put("recentTransitions", history)
                        .put("inputByMode", modes).put("pointerOutput", pointerOutput.snapshot())
                        .put("nativeFocusRecovery", new JSONObject().put("requests", nativeFocusRequests)
                                .put("lastRequestAt", lastNativeFocusRequestAt).put("lastReason", lastNativeFocusReason)
                                .put("scope", "Queued Game.exe foreground requests; Windows acceptance is not acknowledged"))
                        .put("nativeReconnect", new JSONObject().put("requests", reconnectRequests)
                                .put("detachSent", reconnectDetachSent).put("attachSent", reconnectAttachSent)
                                .put("completed", reconnectCompleted).put("cancelled", reconnectCancelled)
                                .put("unavailable", reconnectUnavailable).put("sendFailures", reconnectSendFailures)
                                .put("timedOut", reconnectTimedOut)
                                .put("lastPhase", lastReconnectPhase).put("lastPhaseAt", lastReconnectAt)
                                .put("scope", "Explicit HID and legacy XInput absent/present UDP sends; Windows disconnection and game acceptance are not acknowledged"))
                        .put("nativeStateDelivery", new JSONObject().put("hidNonNeutralSent", hidNonNeutralStateSent)
                                .put("hidNeutralSent", hidNeutralStateSent).put("xinputNonNeutralSent", xinputNonNeutralStateSent)
                                .put("xinputNeutralSent", xinputNeutralStateSent).put("sendFailures", nativeStateSendFailures)
                                .put("lastNonNeutralSentAt", lastNonNeutralStateSentAt)
                                .put("scope", "Successful UDP state sends by neutral/nonneutral category; no values or game acceptance"))
                        .put("recentPointerContexts", pointerContexts)
                        .put("inputGate", new JSONObject().put("known", gateKnown).put("windowFocus", windowFocus)
                                .put("paused", paused).put("quickMenu", quickMenu).put("drawer", drawer))
                        .put("lastEvents", new JSONObject().put("handledMotionAt", lastHandledMotionAt)
                                .put("handledKeyAt", lastHandledKeyAt).put("hidStateReplyAt", lastHidReplyAt)
                                .put("xinputStateReplyAt", lastXInputReplyAt))
                        .put("nativeBridge", "legacy_xinput_7949_and_hid_7950")
                        .put("bridgeRevision", "java-native-identity-v1")
                        .put("controllerProducer", new JSONObject().put("generation", producerGeneration)
                                .put("selectedAndroidDeviceId", selectedAndroidDeviceId).put("androidDeviceChanges", androidDeviceChanges)
                                .put("hidDeviceUid", hidDeviceUid))
                        .put("controllerExposure", controllerExposureSnapshot())
                        .put("nativeIdentityRecovery", new JSONObject().put("requests", identityRequests)
                                .put("detachSent", identityDetachSent).put("detachObserved", identityDetachObserved).put("attachSent", identityAttachSent)
                                .put("backendObserved", identityBackendObserved).put("deviceStartObserved", identityStartedObserved)
                                .put("cancelled", identityCancelled).put("unavailable", identityUnavailable)
                                .put("timedOut", identityTimedOut).put("sendFailures", identitySendFailures)
                                .put("lastPhase", lastIdentityPhase).put("lastPhaseAt", lastIdentityAt)
                                .put("scope", "Explicit fresh Wine HID instance; matched backend callbacks do not acknowledge Windows enumeration or PD2 acceptance"))
                        .put("hidBackend", latestHidBackend == null ? JSONObject.NULL : new JSONObject(latestHidBackend.toString()))
                        .put("hidAcknowledgements", new JSONObject().put("backendIdentityChanges", hidBackendIdentityChanges)
                                .put("outOfOrder", hidOutOfOrderAcks).put("latestRejected", latestRejectedHidBackend == null
                                        ? JSONObject.NULL : new JSONObject(latestRejectedHidBackend.toString())))
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
                            .put("controllerAxisLTRIGGER", ExternalController.hasControllerAxis(device, MotionEvent.AXIS_LTRIGGER))
                            .put("controllerAxisRTRIGGER", ExternalController.hasControllerAxis(device, MotionEvent.AXIS_RTRIGGER))
                            .put("controllerAxisBRAKE", ExternalController.hasControllerAxis(device, MotionEvent.AXIS_BRAKE))
                            .put("controllerAxisGAS", ExternalController.hasControllerAxis(device, MotionEvent.AXIS_GAS))
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

    /** Persist on lifecycle boundaries and bounded memory checkpoints; events only update counters. */
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
