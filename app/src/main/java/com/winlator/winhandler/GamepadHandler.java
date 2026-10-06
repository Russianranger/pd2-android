package com.winlator.winhandler;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import com.winlator.R;
import com.winlator.core.ArrayUtils;
import com.winlator.core.FileUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.GamepadSlot;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.inputcontrols.GamepadVibration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class GamepadHandler {
    public static final byte DINPUT_MAPPER_TYPE_STANDARD = 0;
    public static final byte DINPUT_MAPPER_TYPE_XINPUT = 1;
    public static final byte AXIS_MODE_X_Y_Z_RZ = 0;
    public static final byte AXIS_MODE_X_Y_RX_RY_Z_RZ = 1;
    private static final byte GAMEPAD_MAX_COUNT = 4;
    private final WinHandler winHandler;
    private final List<Integer> gamepadClients = new CopyOnWriteArrayList<>();
    private final List<Integer> legacyGamepadClients = new CopyOnWriteArrayList<>();
    private final HashSet<Integer> legacyXInputProcesses = new HashSet<>();
    private volatile GamepadSlot legacyGamepad;
    private volatile GamepadSlot hidGamepad;
    private volatile boolean hidSubscribed;
    private byte dinputMapperType = DINPUT_MAPPER_TYPE_XINPUT;
    private final GamepadSlot[] gamepadSlots = new GamepadSlot[GAMEPAD_MAX_COUNT];
    private final ArrayList<ExternalController> connectedControllers = new ArrayList<>(GAMEPAD_MAX_COUNT);
    private GamepadPlayerConfig[] gamepadPlayerConfigs;
    private short[] gamepadModelIds;
    private volatile boolean inputEnabled = true;
    private volatile long inputGeneration;
    private volatile long hidTopologyGeneration;
    private volatile long hidReconnectGeneration;
    // 1: disconnected interval; 2: attach queued, controls still neutral; 0: ordinary delivery.
    private volatile int hidReconnectPhase;
    private volatile boolean stopped;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private static final long HID_RECONNECT_GAP_MS = 600;
    private static final long HID_IDENTITY_TIMEOUT_MS = 8000;
    private static final AtomicLong PRODUCER_GENERATION = new AtomicLong();
    private final long producerGeneration = PRODUCER_GENERATION.incrementAndGet();
    private final int hidSessionToken = new SecureRandom().nextInt(Integer.MAX_VALUE - 1) + 1;
    private volatile int hidDeviceUid;
    private volatile boolean identityRecovery;
    private volatile int identityExpectedUid;
    private volatile long identityStartBaseline;
    private volatile long identityRemoveBaseline;
    private volatile long identityStopBaseline;
    private volatile long identityDetachSentAt;
    private volatile boolean identityDetachObserved;
    private volatile boolean identityBackendObserved;
    private volatile Pd2HidProtocol.Ack lastHidAck;
    private Consumer<String> identityCallback;
    private float stickDeadzone;

    long getProducerGeneration() { return producerGeneration; }

    private byte[] hidDevicePacket(GamepadSlot device) {
        return Pd2HidProtocol.tag(ModernGamepadProtocol.hidDevice(device), hidDeviceUid, hidSessionToken);
    }

    /** Opt-in experiment: a new Wine PnP instance, retaining the controller's mapping and model. */
    public boolean recoverNativeIdentity(Consumer<String> callback) {
        winHandler.controllerDiagnostics.recordNativeIdentityRecovery("requested");
        Pd2HidProtocol.Ack backend = lastHidAck;
        if (stopped || !inputEnabled || !hidSubscribed || hidGamepad == null || hidReconnectPhase != 0
                || !legacyNativeReady() || !winHandler.isInputReady() || hidDeviceUid == Integer.MAX_VALUE
                || backend == null || backend.uid != hidDeviceUid
                || (backend.flags & Pd2HidProtocol.CONNECTIVITY_MASK) != 3) {
            winHandler.controllerDiagnostics.recordNativeIdentityRecovery("unavailable");
            return false;
        }
        reconnectHandler.removeCallbacksAndMessages(null);
        final long epoch = ++hidReconnectGeneration;
        identityRecovery = true;
        identityCallback = callback;
        identityExpectedUid = hidDeviceUid + 1;
        identityStartBaseline = backend.started;
        identityRemoveBaseline = backend.removed;
        identityStopBaseline = backend.stopped;
        identityDetachSentAt = 0;
        identityDetachObserved = false;
        identityBackendObserved = false;
        inputGeneration++;
        hidTopologyGeneration++;
        hidReconnectPhase = 1;
        notifyIdentity(epoch, "requested", false);
        reconnectHandler.postDelayed(() -> {
            if (identityRecovery && hidReconnectGeneration == epoch && hidReconnectPhase != 0) {
                cancelNativeReconnect(true, "timedOut");
            }
        }, HID_IDENTITY_TIMEOUT_MS);
        neutralizeAll();
        winHandler.addControllerAction(() -> {
            if (!reconnectCurrent(epoch, 1)) return;
            boolean legacySent = sendLegacyDeviceAndNeutral(null);
            boolean hidSent = winHandler.sendPacket(ModernGamepadProtocol.HID_PORT, hidDevicePacket(null));
            if (legacySent && hidSent) {
                identityDetachSentAt = SystemClock.uptimeMillis();
                notifyIdentity(epoch, "detachSent", false);
                reconnectHandler.postDelayed(() -> checkIdentityDetach(epoch), HID_RECONNECT_GAP_MS);
            } else reconnectHandler.post(() -> {
                if (!reconnectCurrent(epoch, 1)) return;
                cancelNativeReconnect(true, "sendFailure");
            });
        });
        return true;
    }

    /** A successful UDP send alone cannot authorize a new device UID. */
    private void checkIdentityDetach(long epoch) {
        if (!identityRecovery || !reconnectCurrent(epoch, 1)) return;
        if (identityDetachObserved && SystemClock.uptimeMillis() - identityDetachSentAt >= HID_RECONNECT_GAP_MS) {
            attachNativeDevice(epoch);
            return;
        }
        winHandler.addControllerAction(() -> {
            if (!identityRecovery || !reconnectCurrent(epoch, 1)) return;
            boolean legacySent = sendLegacyDeviceAndNeutral(null);
            boolean hidSent = winHandler.sendPacket(ModernGamepadProtocol.HID_PORT, hidDevicePacket(null));
            if (!legacySent || !hidSent) reconnectHandler.post(() -> {
                if (identityRecovery && reconnectCurrent(epoch, 1)) cancelNativeReconnect(true, "sendFailure");
            });
        });
        reconnectHandler.postDelayed(() -> checkIdentityDetach(epoch), 300);
    }

    private void notifyIdentity(long epoch, String phase, boolean terminal) {
        if (!"requested".equals(phase)) winHandler.controllerDiagnostics.recordNativeIdentityRecovery(phase);
        Consumer<String> callback = identityCallback;
        if (terminal) identityCallback = null;
        if (callback != null) reconnectHandler.post(() -> {
            if (stopped || hidReconnectGeneration != epoch) return;
            try { callback.accept(phase); } catch (RuntimeException ignored) { }
        });
    }

    /** WinHandler already confines the datagram to the loopback HID port. */
    boolean handleHidAcknowledgement(ByteBuffer packet, int port) {
        Pd2HidProtocol.Ack ack = Pd2HidProtocol.parseAck(packet);
        if (port != ModernGamepadProtocol.HID_PORT || ack == null || ack.sessionToken != hidSessionToken
                || ack.uid > hidDeviceUid) return false;
        if (ack.uid < hidDeviceUid) return true; // A valid old-instance stop cannot complete or overwrite the new instance.
        Pd2HidProtocol.Ack previous = lastHidAck;
        if (previous != null) {
            if (ack.pid != previous.pid || (ack.socketInode != 0 && previous.socketInode != 0
                    && ack.socketInode != previous.socketInode)) {
                winHandler.controllerDiagnostics.recordRejectedHidBackend("identityChanged", ack.pid, ack.socketInode, ack.uid);
                observeHidBackend(ack); // A replacement owner starts a new bounded baseline, not a completed recovery.
                final long epoch = hidReconnectGeneration;
                reconnectHandler.post(() -> {
                    if (identityRecovery && hidReconnectGeneration == epoch) cancelNativeReconnect(true);
                });
                return true;
            }
            if (ack.monotonicMillis < previous.monotonicMillis || ack.created < previous.created
                    || ack.removed < previous.removed || ack.started < previous.started || ack.stopped < previous.stopped
                    || ack.stateReceived < previous.stateReceived || ack.reportsQueued < previous.reportsQueued
                    || ack.invalidPackets < previous.invalidPackets) {
                winHandler.controllerDiagnostics.recordRejectedHidBackend("outOfOrder", ack.pid, ack.socketInode, ack.uid);
                return true;
            }
        }
        observeHidBackend(ack);
        final long epoch = hidReconnectGeneration;
        // A prior ordinary reconnect can leave another object with the same UID.
        // The per-object retirement flag prevents its delayed stop from authorizing this detach.
        if (identityRecovery && hidReconnectPhase == 1
                && (ack.flags & Pd2HidProtocol.CONNECTIVITY_MASK) == 0
                && (ack.flags & Pd2HidProtocol.LAST_REMOVAL_STOPPED) != 0
                && ack.removed > identityRemoveBaseline && ack.stopped > identityStopBaseline) {
            reconnectHandler.post(() -> {
                if (!identityRecovery || !reconnectCurrent(epoch, 1) || identityDetachObserved) return;
                identityDetachObserved = true;
                notifyIdentity(epoch, "detachObserved", false);
            });
        }
        if (identityRecovery && hidReconnectPhase == 2 && ack.uid == identityExpectedUid) {
            reconnectHandler.post(() -> {
                if (!identityRecovery || !reconnectCurrent(epoch, 2) || ack.uid != identityExpectedUid) return;
                if (!identityBackendObserved) {
                    identityBackendObserved = true;
                    notifyIdentity(epoch, "backendObserved", false);
                }
                if ((ack.flags & Pd2HidProtocol.CONNECTIVITY_MASK) == 3
                        && (ack.stage == 4 || ack.started > identityStartBaseline)) {
                    inputGeneration++;
                    hidTopologyGeneration++;
                    hidReconnectPhase = 0;
                    reconnectHandler.removeCallbacksAndMessages(null);
                    identityRecovery = false;
                    notifyIdentity(epoch, "deviceStartObserved", true);
                }
            });
        }
        return true;
    }

    private void observeHidBackend(Pd2HidProtocol.Ack ack) {
        lastHidAck = ack;
        winHandler.controllerDiagnostics.recordHidBackend(ack.uid, ack.pid, ack.flags, ack.stage,
                ack.socketInode, ack.monotonicMillis, ack.created, ack.removed, ack.started, ack.stopped,
                ack.stateReceived, ack.reportsQueued, ack.invalidPackets);
    }

    public void setStickDeadzone(float value) {
        stickDeadzone = Math.max(0, Math.min(0.4f, value));
    }

    private float filterStick(float value) {
        return Math.abs(value) <= stickDeadzone ? 0 : Math.copySign((Math.abs(value) - stickDeadzone) / (1 - stickDeadzone), value);
    }

    /** Keep the Wine device connected, but never deliver controls while a modal or fallback layout owns them. */
    public void setInputEnabled(boolean enabled) {
        if (inputEnabled != enabled) inputGeneration++;
        inputEnabled = enabled;
        winHandler.controllerDiagnostics.setNativeInputEnabled(enabled);
        if (!enabled) { cancelNativeReconnect(true); neutralizeAll(); }
    }

    /** Explicit experiment: recycle both Wine's HID device and the legacy XInput connection. */
    public boolean reconnectNativeDevice() {
        winHandler.controllerDiagnostics.recordNativeReconnect("requested");
        if (stopped || !inputEnabled || !hidSubscribed || hidGamepad == null
                || !legacyNativeReady() || !winHandler.isInputReady() || identityRecovery) {
            winHandler.controllerDiagnostics.recordNativeReconnect("unavailable");
            return false;
        }
        reconnectHandler.removeCallbacksAndMessages(null);
        final long epoch = ++hidReconnectGeneration;
        inputGeneration++;
        hidTopologyGeneration++;
        hidReconnectPhase = 1;
        reconnectHandler.postDelayed(() -> {
            if (!stopped && hidReconnectGeneration == epoch && hidReconnectPhase != 0) {
                winHandler.controllerDiagnostics.recordNativeReconnect("timedOut");
                cancelNativeReconnect(true);
            }
        }, 2500);
        neutralizeAll();
        winHandler.addControllerAction(() -> {
            if (!reconnectCurrent(epoch, 1)) return;
            // Wine's removal notification makes the game rescan XInput. Advertise
            // the legacy device absent first, so that scan can observe disconnect.
            boolean legacySent = sendLegacyDeviceAndNeutral(null);
            boolean hidSent = winHandler.sendPacket(ModernGamepadProtocol.HID_PORT, hidDevicePacket(null));
            boolean sent = legacySent && hidSent;
            winHandler.controllerDiagnostics.recordNativeReconnect(sent ? "detachSent" : "sendFailure");
            if (sent) reconnectHandler.postDelayed(() -> attachNativeDevice(epoch), HID_RECONNECT_GAP_MS);
            else reconnectHandler.post(() -> { if (reconnectCurrent(epoch, 1)) cancelNativeReconnect(true); });
        });
        return true;
    }

    private boolean reconnectCurrent(long epoch, int phase) {
        return !stopped && inputEnabled && hidSubscribed && legacyNativeReady()
                && hidReconnectGeneration == epoch && hidReconnectPhase == phase;
    }

    private boolean legacyNativeReady() {
        return legacyGamepad != null && legacyGamepadClients.contains(LegacyGamepadProtocol.XINPUT_PORT);
    }

    /** Runs only on the serialized controller queue; an absent state cannot resurrect Wine's device. */
    private boolean sendLegacyDeviceAndNeutral(GamepadSlot device) {
        boolean sent = true;
        int id = device != null ? LegacyGamepadProtocol.GAMEPAD_ID : 0;
        for (int port : legacyGamepadClients) {
            sent &= winHandler.sendPacket(port, LegacyGamepadProtocol.device(id, dinputMapperType,
                    device != null ? device.getName() : ""));
            sent &= sendNativeStatePacket(port, LegacyGamepadProtocol.state(id, device != null, new GamepadState()));
        }
        return sent;
    }

    private void attachNativeDevice(long epoch) {
        if (!reconnectCurrent(epoch, 1)) return;
        if (identityRecovery) {
            hidDeviceUid = identityExpectedUid;
            winHandler.controllerDiagnostics.setHidDeviceUid(hidDeviceUid);
        }
        hidReconnectPhase = 2;
        hidTopologyGeneration++;
        winHandler.addControllerAction(() -> {
            if (!reconnectCurrent(epoch, 2)) return;
            final GamepadSlot current = hidGamepad;
            boolean legacySent = sendLegacyDeviceAndNeutral(legacyGamepad);
            boolean hidSent = winHandler.sendPacket(ModernGamepadProtocol.HID_PORT,
                    hidDevicePacket(current));
            boolean neutralSent = sendNativeStatePacket(ModernGamepadProtocol.HID_PORT,
                    ModernGamepadProtocol.state(0, DINPUT_MAPPER_TYPE_XINPUT, new GamepadState()));
            boolean sent = legacySent && hidSent && current != null;
            final boolean prepared = sent && neutralSent;
            if (identityRecovery) {
                if (prepared) notifyIdentity(epoch, "attachSent", false);
            }
            else {
                winHandler.controllerDiagnostics.recordNativeReconnect(sent ? "attachSent" : "sendFailure");
                if (sent && !prepared) winHandler.controllerDiagnostics.recordNativeReconnect("sendFailure");
            }
            reconnectHandler.post(() -> {
                if (!reconnectCurrent(epoch, 2)) return;
                if (prepared) {
                    if (identityRecovery) return; // Wait for the matched Unix device-start acknowledgement.
                    // Old reports captured before/during the blackout cannot replay into the fresh HID instance.
                    inputGeneration++;
                    hidTopologyGeneration++;
                    hidReconnectPhase = 0;
                    reconnectHandler.removeCallbacksAndMessages(null);
                    winHandler.controllerDiagnostics.recordNativeReconnect("completed");
                } else cancelNativeReconnect(true, identityRecovery ? "sendFailure" : "cancelled");
            });
        });
    }

    private void cancelNativeReconnect(boolean restore) {
        cancelNativeReconnect(restore, "cancelled");
    }

    private void cancelNativeReconnect(boolean restore, String identityTerminal) {
        if (hidReconnectPhase == 0) return;
        final long epoch = ++hidReconnectGeneration;
        inputGeneration++;
        hidTopologyGeneration++;
        hidReconnectPhase = 0;
        reconnectHandler.removeCallbacksAndMessages(null);
        if (identityRecovery) {
            identityRecovery = false;
            notifyIdentity(epoch, identityTerminal, true);
        } else winHandler.controllerDiagnostics.recordNativeReconnect("cancelled");
        // A modal may interrupt after removal: keep the device advertised, with neutral controls.
        if (restore && !stopped && hidSubscribed) winHandler.addControllerAction(() -> {
            if (stopped || hidReconnectGeneration != epoch || hidReconnectPhase != 0) return;
            GamepadSlot current = hidGamepad;
            boolean legacyRestored = sendLegacyDeviceAndNeutral(legacyGamepad);
            boolean hidRestored = winHandler.sendPacket(ModernGamepadProtocol.HID_PORT, hidDevicePacket(current));
            boolean prepared = current == null || sendNativeStatePacket(ModernGamepadProtocol.HID_PORT,
                    ModernGamepadProtocol.state(0, DINPUT_MAPPER_TYPE_XINPUT, new GamepadState()));
            if (!legacyRestored || !hidRestored || !prepared) winHandler.controllerDiagnostics.recordNativeReconnect("sendFailure");
        });
    }

    public void stop() {
        stopped = true;
        cancelNativeReconnect(false);
        inputGeneration++;
        reconnectHandler.removeCallbacksAndMessages(null);
    }

    private boolean nativeControlsAllowed(long generation) {
        return !stopped && inputEnabled && hidReconnectPhase == 0 && generation == inputGeneration;
    }

    /** Successful packet categories only; never exports values, key codes or button identities. */
    private boolean sendNativeStatePacket(int port, byte[] packet) {
        if (stopped) return false;
        if (port == ModernGamepadProtocol.HID_PORT) packet = Pd2HidProtocol.tag(packet, hidDeviceUid, hidSessionToken);
        boolean sent = winHandler.sendPacket(port, packet);
        boolean nonNeutral = false;
        int start = port == ModernGamepadProtocol.HID_PORT ? 2 : 6;
        int hat = port == ModernGamepadProtocol.HID_PORT ? 4 : 8;
        if (port == ModernGamepadProtocol.HID_PORT || port == LegacyGamepadProtocol.XINPUT_PORT) {
            for (int index = start; index < 17; index++)
                nonNeutral |= index == hat ? packet[index] != (byte)0xff : packet[index] != 0;
            winHandler.controllerDiagnostics.recordNativeStateDelivery(port, nonNeutral, sent);
        }
        return sent;
    }

    public void neutralizeAll() {
        final GamepadState neutral = new GamepadState();
        synchronized (connectedControllers) {
            for (ExternalController controller : connectedControllers) controller.getGamepadState().copy(neutral);
        }
        for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) {
            final byte slot = i;
            if (gamepadSlots[i] != null) gamepadSlots[i].getGamepadState().copy(neutral);
            final byte mapper = dinputMapperType;
            for (final int port : gamepadClients) winHandler.addControllerAction(() ->
                    winHandler.sendPacket(port, ModernGamepadProtocol.state(slot, mapper, neutral)));
        }
        sendLegacyState(neutral);
        if (hidSubscribed) sendHidState(neutral);
    }

    public static class GamepadModel {
        public final String name;
        public final short vendorId;
        public final short productId;

        public GamepadModel(String name, short vendorId, short productId) {
            this.name = name;
            this.vendorId = vendorId;
            this.productId = productId;
        }

        public String identifier() {
            return String.format(Locale.ENGLISH, "VID_%04X&PID_%04X", vendorId, productId);
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }

    public GamepadHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    private void updateGamepadSlots() {
        if (gamepadPlayerConfigs == null) {
            SharedPreferences preferences = winHandler.activity.getPreferences();
            gamepadPlayerConfigs = new GamepadPlayerConfig[GAMEPAD_MAX_COUNT];
            for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) {
                gamepadPlayerConfigs[i] = new GamepadPlayerConfig(preferences.getString("gamepad_player"+i, ""));
            }
        }

        if (gamepadModelIds == null) {
            SharedPreferences preferences = winHandler.activity.getPreferences();
            String gamepadModel = preferences.getString("gamepad_model", null);
            if (gamepadModel != null) {
                gamepadModelIds = new short[]{
                    (short)Integer.parseInt(gamepadModel.substring(4, 8), 16),
                    (short)Integer.parseInt(gamepadModel.substring(13, 17), 16),
                };
            }
            else gamepadModelIds = new short[0];
        }

        ControlsProfile profile = winHandler.activity.getInputControlsView().getProfile();
        boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();

        for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) gamepadSlots[i] = null;

        synchronized (connectedControllers) {
            ExternalController.updateConnectedControllers(connectedControllers);
        }

        boolean autoAssign = true;
        for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) {
            GamepadPlayerConfig config = gamepadPlayerConfigs[i];
            if (config.name.isEmpty()) continue;
            if (config.mode == GamepadPlayerConfig.MODE_EXTERNAL_CONTROLLER) {
                for (ExternalController controller : connectedControllers) {
                    if (controller.getName().equals(config.name)) {
                        gamepadSlots[i] = controller;
                        autoAssign = false;
                        break;
                    }
                }
            }
            else if (useVirtualGamepad && profile.getName().equals(config.name)) {
                gamepadSlots[i] = profile;
                autoAssign = false;
            }
        }

        if (autoAssign) {
            if (useVirtualGamepad) gamepadSlots[0] = profile;
            int index = 0;
            for (byte i = 0; i < GAMEPAD_MAX_COUNT; i++) {
                if (gamepadSlots[i] != null) continue;
                gamepadSlots[i] = index < connectedControllers.size() ? connectedControllers.get(index) : null;
                index++;
            }
        }
        int physicalId = -1;
        for (GamepadSlot slot : gamepadSlots) if (slot instanceof ExternalController) {
            physicalId = ((ExternalController)slot).getDeviceId();
            break;
        }
        winHandler.controllerDiagnostics.setSelectedAndroidDevice(physicalId);
        recordControllerSlotTopology();
    }

    /** Observe the existing assignment; never enumerate Windows devices or open another XInput client. */
    void recordControllerSlotTopology() {
        int slots = 0, physicalSlots = 0, connectedPhysical;
        HashSet<Integer> assignedAndroidDevices = new HashSet<>(GAMEPAD_MAX_COUNT);
        for (GamepadSlot slot : gamepadSlots) {
            if (slot == null) continue;
            slots++;
            if (slot instanceof ExternalController) {
                physicalSlots++;
                assignedAndroidDevices.add(((ExternalController)slot).getDeviceId());
            }
        }
        synchronized (connectedControllers) { connectedPhysical = connectedControllers.size(); }
        winHandler.controllerDiagnostics.setControllerSlotTopology(connectedPhysical, slots, physicalSlots,
                assignedAndroidDevices.size(), slots - physicalSlots);
    }

    private boolean isAnyGamepadConnected() {
        for (GamepadSlot gamepadSlot : gamepadSlots) if (gamepadSlot != null) return true;
        return false;
    }

    public void handleGetGamepadRequest(int port) {
        if (isLegacyClient(port) && winHandler.receiveData.remaining() < 6) {
            winHandler.controllerDiagnostics.recordInvalidLegacyDiscovery();
            return;
        }
        updateGamepadSlots();
        if (isLegacyClient(port)) {
            handleLegacyGetGamepadRequest(port);
            return;
        }

        handleModernGetGamepadRequest(port);
    }

    /** Shared response path after Android device discovery; packets snapshot metadata immediately. */
    void handleModernGetGamepadRequest(int port) {
        if (port == ModernGamepadProtocol.HID_PORT) {
            GamepadSlot selected = null;
            for (GamepadSlot slot : gamepadSlots) if (slot != null) { selected = slot; break; }
            hidGamepad = selected;
            hidSubscribed = selected != null;
            winHandler.controllerDiagnostics.setSelectedDevice(selected != null ? selected.getName() : null);
            final byte[] device = hidDevicePacket(selected);
            final GamepadState state = selected != null ? snapshot(selected) : new GamepadState();
            final long generation = inputGeneration;
            final long topology = hidTopologyGeneration;
            winHandler.addControllerAction(() -> {
                if (stopped) return;
                boolean detached = hidReconnectPhase == 1;
                if (!detached && topology != hidTopologyGeneration) return;
                byte[] advertised = detached ? hidDevicePacket(null) : device;
                winHandler.sendPacket(port, advertised);
                if (advertised[1] == 1) sendNativeStatePacket(port, ModernGamepadProtocol.state(0,
                        DINPUT_MAPPER_TYPE_XINPUT,
                        nativeControlsAllowed(generation) ? state : new GamepadState()));
            });
            return;
        }

        int clientIndex = gamepadClients.indexOf(port);
        if (isAnyGamepadConnected()) {
            if (clientIndex == -1) gamepadClients.add(port);
        }
        else if (clientIndex != -1) gamepadClients.remove(clientIndex);

        boolean[] vibration = new boolean[GAMEPAD_MAX_COUNT];
        for (int slot = 0; slot < GAMEPAD_MAX_COUNT; slot++)
            vibration[slot] = gamepadPlayerConfigs != null && gamepadPlayerConfigs[slot].vibration;
        final byte[] packet = ModernGamepadProtocol.devices(gamepadSlots, dinputMapperType, gamepadModelIds, vibration);
        winHandler.addControllerAction(() -> winHandler.sendPacket(port, packet));
    }

    private boolean isLegacyClient(int port) {
        return LegacyGamepadProtocol.usesLegacyProtocol(winHandler.getWineIdentifier(), port);
    }

    void handleLegacyGetGamepadRequest(int port) {
        ByteBuffer request = winHandler.receiveData;
        if (request.remaining() < 6) {
            winHandler.controllerDiagnostics.recordInvalidLegacyDiscovery();
            return;
        }
        byte xinputFlag = request.get(), notifyFlag = request.get();
        if ((xinputFlag != 0 && xinputFlag != 1) || (notifyFlag != 0 && notifyFlag != 1)) {
            winHandler.controllerDiagnostics.recordInvalidLegacyDiscovery();
            throw new IllegalArgumentException("Invalid legacy controller discovery flags");
        }
        boolean xinput = xinputFlag == 1;
        boolean notify = notifyFlag == 1;
        int processId = request.getInt();
        if (!winHandler.controllerDiagnostics.recordLegacyDiscovery(xinput, notify, processId, port))
            throw new IllegalArgumentException("Invalid legacy controller discovery identity");
        if (xinput) legacyXInputProcesses.add(processId);
        GamepadSlot selected = null;
        // Match upstream AUTO: avoid a duplicate DInput device for an XInput process.
        if (xinput || !legacyXInputProcesses.contains(processId)) {
            for (GamepadSlot slot : gamepadSlots) if (slot != null) { selected = slot; break; }
        }
        if (selected != null) legacyGamepad = selected;
        else if (!isAnyGamepadConnected()) legacyGamepad = null;
        if (selected != null && notify) {
            if (!legacyGamepadClients.contains(port)) legacyGamepadClients.add(port);
        }
        else legacyGamepadClients.remove(Integer.valueOf(port));
        winHandler.controllerDiagnostics.setSelectedDevice(legacyGamepad != null ? legacyGamepad.getName() : null);
        final GamepadSlot device = selected;
        final GamepadState state = device != null ? snapshot(device) : new GamepadState();
        final long generation = inputGeneration;
        final long topology = hidTopologyGeneration;
        final byte mapper = dinputMapperType;
        winHandler.addControllerAction(() -> {
            if (stopped) return;
            boolean detached = hidReconnectPhase == 1;
            if (!detached && topology != hidTopologyGeneration) return;
            int id = device != null && !detached ? LegacyGamepadProtocol.GAMEPAD_ID : 0;
            winHandler.sendPacket(port, LegacyGamepadProtocol.device(id, mapper, id != 0 ? device.getName() : ""));
            if (device != null) sendNativeStatePacket(port, LegacyGamepadProtocol.state(id, !detached,
                    nativeControlsAllowed(generation) ? state : new GamepadState()));
        });
    }

    /** Legacy DLLs normally subscribe to pushes; retain their optional state poll. */
    public void handleGetGamepadStateRequest(int port) {
        if (!isLegacyClient(port) || winHandler.receiveData.remaining() < 4) return;
        int requestedId = winHandler.receiveData.getInt();
        final GamepadSlot device = legacyGamepad;
        final boolean connected = device != null && requestedId == LegacyGamepadProtocol.GAMEPAD_ID;
        final GamepadState state = connected ? snapshot(device) : new GamepadState();
        final long generation = inputGeneration;
        final long topology = hidTopologyGeneration;
        winHandler.addControllerAction(() -> {
            if (stopped || (hidReconnectPhase != 1 && topology != hidTopologyGeneration)) return;
            sendNativeStatePacket(port, LegacyGamepadProtocol.state(requestedId, connected && hidReconnectPhase != 1,
                    nativeControlsAllowed(generation) ? state : new GamepadState()));
        });
    }

    private GamepadState snapshot(GamepadSlot device) {
        GamepadState state = new GamepadState();
        state.copy(device.getGamepadState());
        state.thumbLX = filterStick(state.thumbLX);
        state.thumbLY = filterStick(state.thumbLY);
        state.thumbRX = filterStick(state.thumbRX);
        state.thumbRY = filterStick(state.thumbRY);
        return state;
    }

    private void sendLegacyState(GamepadState state) {
        final GamepadSlot device = legacyGamepad;
        final long generation = inputGeneration;
        for (final int port : legacyGamepadClients) winHandler.addControllerAction(() ->
                sendNativeStatePacket(port, LegacyGamepadProtocol.state(LegacyGamepadProtocol.GAMEPAD_ID,
                        device != null && hidReconnectPhase != 1,
                        nativeControlsAllowed(generation) ? state : new GamepadState())));
    }

    private void sendHidState(GamepadState state) {
        final long generation = inputGeneration;
        winHandler.addControllerAction(() -> sendNativeStatePacket(ModernGamepadProtocol.HID_PORT,
                ModernGamepadProtocol.state(0, DINPUT_MAPPER_TYPE_XINPUT,
                        nativeControlsAllowed(generation) ? state : new GamepadState())));
    }

    public void sendGamepadState(final GamepadSlot gamepadSlot) {
        // Preserve release edges, even if a previous packet is still queued.
        if (!inputEnabled || (gamepadClients.isEmpty() && legacyGamepadClients.isEmpty() && !hidSubscribed)) return;
        final byte slot = (byte)ArrayUtils.indexOf(gamepadSlots, gamepadSlot);
        if (slot == ArrayUtils.INDEX_NOT_FOUND) return;
        final GamepadState state = snapshot(gamepadSlot);
        if (gamepadSlot == legacyGamepad) sendLegacyState(state);
        if (hidSubscribed && gamepadSlot == hidGamepad) sendHidState(state);
        final long generation = inputGeneration;
        final byte mapper = dinputMapperType;

        for (final int port : gamepadClients) {
            winHandler.addControllerAction(() -> {
                winHandler.sendPacket(port, ModernGamepadProtocol.state(slot, mapper,
                        nativeControlsAllowed(generation) ? state : new GamepadState()));
            });
        }
    }

    public void handleReleaseGamepadRequest(int port) {
        if (port == ModernGamepadProtocol.HID_PORT) {
            hidSubscribed = false;
            hidGamepad = null;
            reconnectHandler.post(() -> cancelNativeReconnect(false));
            winHandler.controllerDiagnostics.setSelectedDevice(legacyGamepad != null ? legacyGamepad.getName() : null);
            return;
        }
        if (isLegacyClient(port)) {
            legacyGamepadClients.remove(Integer.valueOf(port));
            if (port == LegacyGamepadProtocol.XINPUT_PORT && hidReconnectPhase != 0)
                reconnectHandler.post(() -> cancelNativeReconnect(true));
            if (legacyGamepadClients.isEmpty()) {
                legacyGamepad = null;
                winHandler.controllerDiagnostics.setSelectedDevice(null);
                legacyXInputProcesses.clear();
            }
            return;
        }
        int index = gamepadClients.indexOf(port);
        if (index != -1) gamepadClients.remove(index);
    }

    public void handleSetGamepadStateRequest(int port) {
        if (port == ModernGamepadProtocol.HID_PORT) return; // The first HID producer has no rumble endpoint.
        final ByteBuffer buffer = winHandler.receiveData;
        byte slot = buffer.get();
        if (slot < 0 || slot >= GAMEPAD_MAX_COUNT) return;
        int leftMotorSpeed = buffer.getInt();
        int rightMotorSpeed = buffer.getInt();
        int durationMs = buffer.getInt();

        GamepadSlot gamepadSlot = gamepadSlots[slot];
        if (gamepadSlot == null) return;

        GamepadVibration vibration = gamepadSlot.getGamepadVibration();
        vibration.vibrate(leftMotorSpeed, rightMotorSpeed, durationMs);
    }

    private ExternalController getConnectedControllerById(int deviceId) {
        synchronized (connectedControllers) {
            for (ExternalController controller : connectedControllers) {
                if (controller.getDeviceId() == deviceId) return controller;
            }

            return null;
        }
    }

    protected boolean onGenericMotionEvent(MotionEvent event) {
        winHandler.controllerDiagnostics.recordMotion(false);
        if (!inputEnabled) return false;
        boolean handled = false;
        ExternalController controller = getConnectedControllerById(event.getDeviceId());
        if (controller != null) {
            handled = controller.updateStateFromMotionEvent(event);
            if (handled) { winHandler.controllerDiagnostics.recordMotion(true); sendGamepadState(controller); }
        }
        return handled;
    }

    protected boolean onKeyEvent(KeyEvent event) {
        winHandler.controllerDiagnostics.recordKey(false);
        if (!inputEnabled) return false;
        boolean handled = false;
        ExternalController controller = getConnectedControllerById(event.getDeviceId());
        if (controller != null && event.getRepeatCount() == 0) {
            int action = event.getAction();

            if (action == KeyEvent.ACTION_DOWN) {
                handled = controller.updateStateFromKeyEvent(event);
            }
            else if (action == KeyEvent.ACTION_UP) {
                handled = controller.updateStateFromKeyEvent(event);
            }

            if (handled) { winHandler.controllerDiagnostics.recordKey(true); sendGamepadState(controller); }
        }
        return handled;
    }

    public byte getDInputMapperType() {
        return dinputMapperType;
    }

    public void setDInputMapperType(byte dinputMapperType) {
        this.dinputMapperType = dinputMapperType;
    }

    public static ArrayList<GamepadModel> loadGamepadModels(Context context) {
        ArrayList<GamepadModel> result = new ArrayList<>();
        result.add(new GamepadModel(context.getString(R.string.default_no_override), (short)0x0001, (short)0x0001));
        try {
            JSONArray jsonArray = new JSONArray(FileUtils.readString(context, "gamepad_models.json"));

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject item = jsonArray.getJSONObject(i);
                short vendorId = (short)Integer.parseInt(item.getString("vid"), 16);
                short productId = (short)Integer.parseInt(item.getString("pid"), 16);
                result.add(new GamepadModel(item.getString("name"), vendorId, productId));
            }
        }
        catch (JSONException e) {}
        return result;
    }
}
