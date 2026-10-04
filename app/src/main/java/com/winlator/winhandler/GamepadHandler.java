package com.winlator.winhandler;

import android.content.Context;
import android.content.SharedPreferences;
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
    private float stickDeadzone;

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
        if (!enabled) neutralizeAll();
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
    }

    private boolean isAnyGamepadConnected() {
        for (GamepadSlot gamepadSlot : gamepadSlots) if (gamepadSlot != null) return true;
        return false;
    }

    public void handleGetGamepadRequest(int port) {
        if (isLegacyClient(port) && winHandler.receiveData.remaining() < 6) return;
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
            final byte[] device = ModernGamepadProtocol.hidDevice(selected);
            final GamepadState state = selected != null ? snapshot(selected) : new GamepadState();
            final long generation = inputGeneration;
            winHandler.addControllerAction(() -> {
                winHandler.sendPacket(port, device);
                if (device[1] == 1) winHandler.sendPacket(port, ModernGamepadProtocol.state(0,
                        DINPUT_MAPPER_TYPE_XINPUT,
                        inputEnabled && generation == inputGeneration ? state : new GamepadState()));
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

    private void handleLegacyGetGamepadRequest(int port) {
        ByteBuffer request = winHandler.receiveData;
        if (request.remaining() < 6) return;
        boolean xinput = request.get() == 1;
        boolean notify = request.get() == 1;
        int processId = request.getInt();
        winHandler.controllerDiagnostics.recordLegacyDiscovery(xinput, notify);
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
        final byte mapper = dinputMapperType;
        winHandler.addControllerAction(() -> {
            int id = device != null ? LegacyGamepadProtocol.GAMEPAD_ID : 0;
            winHandler.sendPacket(port, LegacyGamepadProtocol.device(id, mapper, device != null ? device.getName() : ""));
            if (device != null) winHandler.sendPacket(port, LegacyGamepadProtocol.state(id, true,
                    inputEnabled && generation == inputGeneration ? state : new GamepadState()));
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
        winHandler.addControllerAction(() -> winHandler.sendPacket(port, LegacyGamepadProtocol.state(requestedId, connected,
                inputEnabled && generation == inputGeneration ? state : new GamepadState())));
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
                winHandler.sendPacket(port, LegacyGamepadProtocol.state(LegacyGamepadProtocol.GAMEPAD_ID, device != null,
                        inputEnabled && generation == inputGeneration ? state : new GamepadState())));
    }

    private void sendHidState(GamepadState state) {
        final long generation = inputGeneration;
        winHandler.addControllerAction(() -> winHandler.sendPacket(ModernGamepadProtocol.HID_PORT,
                ModernGamepadProtocol.state(0, DINPUT_MAPPER_TYPE_XINPUT,
                        inputEnabled && generation == inputGeneration ? state : new GamepadState())));
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
                        inputEnabled && generation == inputGeneration ? state : new GamepadState()));
            });
        }
    }

    public void handleReleaseGamepadRequest(int port) {
        if (port == ModernGamepadProtocol.HID_PORT) {
            hidSubscribed = false;
            hidGamepad = null;
            winHandler.controllerDiagnostics.setSelectedDevice(legacyGamepad != null ? legacyGamepad.getName() : null);
            return;
        }
        if (isLegacyClient(port)) {
            legacyGamepadClients.remove(Integer.valueOf(port));
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
