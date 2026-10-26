package com.winlator.pd2;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.winlator.inputcontrols.ExternalController;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;

/** Mouse/keyboard and temporary menu layouts. Native mode uses Wine's gamepad driver. */
public final class Pd2InputRouter {
    public interface MenuInput {
        void move(int dx, int dy);
        void button(Pointer.Button button, boolean down);
        void key(XKeycode key, boolean down);
        /** Retire queued cursor moves when this router stops its owned motion. */
        default void stopMotion() {}
    }

    private final XServer xServer;
    private final MenuInput x11Input;
    private final MenuInput menuInput;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, XKeycode> heldKeys = new HashMap<>();
    private final Map<String, Pointer.Button> heldButtons = new HashMap<>();
    private final Map<String, MenuInput> keyOwners = new HashMap<>();
    private final Map<String, MenuInput> buttonOwners = new HashMap<>();
    private float mouseX, mouseY, remainderX, remainderY, cursorSpeed = 1.0f, deadzone = 0.18f;
    private boolean menuControls;
    private int mouseDeviceId = -1, mouseSource;
    private String mouseDescriptor;
    private long mouseGeneration, inputGeneration, releasedAt = -1;
    private Runnable moveMouse;
    private MenuInput mouseOwner;
    private final Map<String, Long> motionTimes = new HashMap<>();
    private final Map<Integer, Long> deviceReleasedAt = new HashMap<>();

    private void stopMouse() {
        MenuInput owner = mouseOwner;
        boolean active = moveMouse != null;
        mouseGeneration++;
        if (moveMouse != null) handler.removeCallbacks(moveMouse);
        moveMouse = null;
        mouseDeviceId = -1;
        mouseDescriptor = null;
        mouseOwner = null;
        mouseX = mouseY = remainderX = remainderY = 0;
        if (active && owner != null) owner.stopMotion();
    }

    private void pointerMotion(MotionEvent event, float x, float y) {
        final long inputEpoch = inputGeneration;
        // One motion stream owns the cursor until centered or cancelled. An idle
        // second pad or a composite mouse stream must not replace its vector.
        if (mouseDeviceId != -1 && (mouseDeviceId != event.getDeviceId() || mouseSource != event.getSource())) return;
        if (mouseDescriptor != null && !mouseDescriptor.equals(event.getDevice().getDescriptor())) stopMouse();
        if (inputEpoch != inputGeneration) return;
        if (x == 0 && y == 0) {
            if (mouseDeviceId == event.getDeviceId() && mouseSource == event.getSource()) stopMouse();
            return;
        }
        mouseX = x;
        mouseY = y;
        if (moveMouse != null) return;
        mouseDeviceId = event.getDeviceId();
        mouseSource = event.getSource();
        mouseDescriptor = event.getDevice().getDescriptor();
        final long generation = ++mouseGeneration;
        final MenuInput owner = inputOwner();
        mouseOwner = owner;
        moveMouse = new Runnable() {
            @Override public void run() {
                if (moveMouse != this || generation != mouseGeneration) return;
                InputDevice device = InputDevice.getDevice(mouseDeviceId);
                if (device == null || mouseDescriptor != null && !mouseDescriptor.equals(device.getDescriptor())) {
                    releaseDevice(mouseDeviceId);
                    return;
                }
                float dx = remainderX + mouseX * 14 * cursorSpeed;
                float dy = remainderY + mouseY * 14 * cursorSpeed;
                int ix = (int) dx, iy = (int) dy;
                remainderX = dx - ix;
                remainderY = dy - iy;
                if (ix != 0 || iy != 0) owner.move(ix, iy);
                // Input injection can close a route reentrantly. A retired task
                // must not post again or drive a replacement route's vector.
                if (moveMouse == this && generation == mouseGeneration && !handler.postDelayed(this, 16)) stopMouse();
            }
        };
        if (!handler.post(moveMouse)) stopMouse();
    }

    public Pd2InputRouter(XServer xServer) { this(xServer, null); }

    public Pd2InputRouter(XServer xServer, MenuInput menuInput) {
        this.xServer = xServer;
        x11Input = new MenuInput() {
            @Override public void move(int dx, int dy) { xServer.injectPointerMoveDelta(dx, dy); }
            @Override public void button(Pointer.Button button, boolean down) {
                if (down) xServer.injectPointerButtonPress(button);
                else xServer.injectPointerButtonRelease(button);
            }
            @Override public void key(XKeycode key, boolean down) {
                if (down) xServer.injectKeyPress(key);
                else xServer.injectKeyRelease(key);
            }
        };
        this.menuInput = menuInput != null ? menuInput : x11Input;
    }

    private MenuInput inputOwner() { return menuControls ? menuInput : x11Input; }

    /** The Activity owns routing; this only selects the layout when it forwards an event here. */
    public void setMenuControls(boolean enabled) {
        if (menuControls == enabled) return;
        releaseAll();
        menuControls = enabled;
    }

    public void setCursorSpeed(float value) { cursorSpeed = Math.max(0.25f, Math.min(3.0f, value)); }
    public float getCursorSpeed() { return cursorSpeed; }
    public void setDeadzone(float value) { deadzone = Math.max(0.05f, Math.min(0.4f, value)); }
    public float getDeadzone() { return deadzone; }

    private float axis(MotionEvent event, int id) {
        float value = ExternalController.getCenteredAxis(event, id, -1);
        return Math.abs(value) <= deadzone ? 0 : Math.copySign((Math.abs(value) - deadzone) / (1 - deadzone), value);
    }

    private void key(String source, XKeycode keycode, boolean down) {
        if (down) {
            if (heldKeys.containsKey(source)) return;
            boolean alreadyHeld = heldKeys.containsValue(keycode);
            heldKeys.put(source, keycode);
            keyOwners.put(source, inputOwner());
            if (!alreadyHeld) inputOwner().key(keycode, true);
        } else {
            XKeycode previous = heldKeys.remove(source);
            MenuInput owner = keyOwners.remove(source);
            if (previous != null && !heldKeys.containsValue(previous)) owner.key(previous, false);
        }
    }

    private void button(String source, Pointer.Button button, boolean down) {
        if (down) {
            if (heldButtons.containsKey(source)) return;
            boolean alreadyHeld = heldButtons.containsValue(button);
            heldButtons.put(source, button);
            buttonOwners.put(source, inputOwner());
            if (!alreadyHeld) inputOwner().button(button, true);
        } else {
            Pointer.Button previous = heldButtons.remove(source);
            MenuInput owner = buttonOwners.remove(source);
            if (previous != null && !heldButtons.containsValue(previous)) owner.button(previous, false);
        }
    }

    private boolean directions(String source, float value, XKeycode negative, XKeycode positive) {
        long epoch = inputGeneration;
        key(source + "-", negative, value < 0);
        if (epoch != inputGeneration) return false;
        key(source + "+", positive, value > 0);
        return epoch == inputGeneration;
    }

    /** Includes cancellation; ExternalController.isJoystickDevice accepts MOVE only. */
    public boolean acceptsMotion(MotionEvent event) {
        int action = event.getActionMasked();
        return (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_CANCEL);
    }

    public boolean motion(MotionEvent event) {
        if (!acceptsMotion(event)) return false;
        String device = event.getDeviceId() + ":" + event.getSource() + ":motion:";
        Long lastTime = motionTimes.get(device), deviceRelease = deviceReleasedAt.get(event.getDeviceId());
        if (event.getEventTime() <= releasedAt || lastTime != null && event.getEventTime() < lastTime
                || deviceRelease != null && event.getEventTime() <= deviceRelease) return true;
        final long inputEpoch = inputGeneration;
        motionTimes.put(device, event.getEventTime());
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            releaseMotion(device);
            if (inputEpoch != inputGeneration) return true;
            if (mouseDeviceId == event.getDeviceId() && mouseSource == event.getSource()) stopMouse();
            return true;
        }
        if (event.getDevice() == null) {
            releaseDevice(event.getDeviceId());
            return false;
        }
        float leftX = axis(event, MotionEvent.AXIS_X);
        float leftY = axis(event, MotionEvent.AXIS_Y);
        if (!menuControls) {
            if (!directions(device + "lx", leftX, XKeycode.KEY_A, XKeycode.KEY_D)) return true;
            if (!directions(device + "ly", leftY, XKeycode.KEY_W, XKeycode.KEY_S)) return true;
        }
        int rightX = ExternalController.rightStickAxis(event, true);
        int rightY = ExternalController.rightStickAxis(event, false);
        float pointerX = axis(event, rightX), pointerY = axis(event, rightY);
        if (menuControls && pointerX == 0 && pointerY == 0) {
            pointerX = leftX;
            pointerY = leftY;
        }
        pointerMotion(event, pointerX, pointerY);
        if (inputEpoch != inputGeneration) return true;
        if (menuControls) {
            if (!directions(device + "hatx", event.getAxisValue(MotionEvent.AXIS_HAT_X), XKeycode.KEY_LEFT, XKeycode.KEY_RIGHT)) return true;
            directions(device + "haty", event.getAxisValue(MotionEvent.AXIS_HAT_Y), XKeycode.KEY_UP, XKeycode.KEY_DOWN);
            return true;
        }
        float lt = ExternalController.getTriggerAxis(event, true);
        float rt = ExternalController.getTriggerAxis(event, false);
        // Keep the existing press threshold, with a lower release threshold so noise
        // around 0.35 cannot repeatedly drink potions. Digital and analog sources
        // retain independent ownership until both have released.
        if (ExternalController.hasTriggerAxis(event, true))
            key(device + "lt", XKeycode.KEY_1, triggerHeld(device + "lt", lt));
        if (inputEpoch != inputGeneration) return true;
        if (ExternalController.hasTriggerAxis(event, false))
            key(device + "rt", XKeycode.KEY_2, triggerHeld(device + "rt", rt));
        if (inputEpoch != inputGeneration) return true;
        if (!directions(device + "hatx", event.getAxisValue(MotionEvent.AXIS_HAT_X), XKeycode.KEY_F6, XKeycode.KEY_F4)) return true;
        directions(device + "haty", event.getAxisValue(MotionEvent.AXIS_HAT_Y), XKeycode.KEY_F3, XKeycode.KEY_F5);
        return true;
    }

    private boolean triggerHeld(String source, float value) {
        return value > (heldKeys.containsKey(source) ? 0.25f : 0.35f);
    }

    public boolean keyEvent(KeyEvent event) {
        if (!ExternalController.isGameController(event.getDevice())) return false;
        if ((event.getFlags() & KeyEvent.FLAG_FALLBACK) != 0) return true;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return true;
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) return true;
        boolean down = action == KeyEvent.ACTION_DOWN;
        Long deviceRelease = deviceReleasedAt.get(event.getDeviceId());
        // Cleanup already balanced the retired interval. Neither edge from it
        // may change a newer press after route replacement or device-ID reuse.
        if (event.getEventTime() <= releasedAt
                || deviceRelease != null && event.getEventTime() <= deviceRelease) return true;
        String source = event.getDeviceId() + ":key" + event.getKeyCode();
        XKeycode keycode;
        if (menuControls) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BUTTON_A: button(source, Pointer.Button.BUTTON_LEFT, down); return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                case KeyEvent.KEYCODE_BUTTON_SELECT: keycode = XKeycode.KEY_ESC; break;
                case KeyEvent.KEYCODE_BUTTON_START:
                case KeyEvent.KEYCODE_BUTTON_THUMBR: keycode = XKeycode.KEY_ENTER; break;
                case KeyEvent.KEYCODE_BUTTON_THUMBL: keycode = XKeycode.KEY_TAB; break;
                case KeyEvent.KEYCODE_DPAD_UP: keycode = XKeycode.KEY_UP; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: keycode = XKeycode.KEY_RIGHT; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: keycode = XKeycode.KEY_DOWN; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: keycode = XKeycode.KEY_LEFT; break;
                default: return true;
            }
            key(source, keycode, down);
            return true;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A: button(source, Pointer.Button.BUTTON_LEFT, down); return true;
            case KeyEvent.KEYCODE_BUTTON_B: button(source, Pointer.Button.BUTTON_RIGHT, down); return true;
            case KeyEvent.KEYCODE_BUTTON_X: keycode = XKeycode.KEY_SHIFT_L; break;
            case KeyEvent.KEYCODE_BUTTON_Y: keycode = XKeycode.KEY_ALT_L; break;
            case KeyEvent.KEYCODE_BUTTON_L1: keycode = XKeycode.KEY_F1; break;
            case KeyEvent.KEYCODE_BUTTON_R1: keycode = XKeycode.KEY_F2; break;
            case KeyEvent.KEYCODE_BUTTON_L2: keycode = XKeycode.KEY_1; break;
            case KeyEvent.KEYCODE_BUTTON_R2: keycode = XKeycode.KEY_2; break;
            case KeyEvent.KEYCODE_DPAD_UP: keycode = XKeycode.KEY_F3; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: keycode = XKeycode.KEY_F4; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: keycode = XKeycode.KEY_F5; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: keycode = XKeycode.KEY_F6; break;
            case KeyEvent.KEYCODE_BUTTON_SELECT: keycode = XKeycode.KEY_ESC; break;
            case KeyEvent.KEYCODE_BUTTON_START: keycode = XKeycode.KEY_I; break;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: keycode = XKeycode.KEY_TAB; break;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: keycode = XKeycode.KEY_ENTER; break;
            default: return true;
        }
        key(source, keycode, down);
        return true;
    }

    private void releaseMotion(String prefix) {
        long epoch = inputGeneration;
        for (String source : new HashSet<>(heldKeys.keySet())) {
            if (source.startsWith(prefix)) key(source, heldKeys.get(source), false);
            if (epoch != inputGeneration) return;
        }
    }

    /** Retire the removed device's input without releasing another controller. */
    public void releaseDevice(int deviceId) {
        final long epoch = ++inputGeneration;
        deviceReleasedAt.put(deviceId, SystemClock.uptimeMillis());
        String prefix = deviceId + ":";
        releaseMotion(prefix);
        if (epoch != inputGeneration) return;
        for (String source : new HashSet<>(heldButtons.keySet())) {
            if (source.startsWith(prefix)) button(source, heldButtons.get(source), false);
            if (epoch != inputGeneration) return;
        }
        if (mouseDeviceId == deviceId) stopMouse();
    }

    private boolean heldBy(MenuInput owner, XKeycode key) {
        for (Map.Entry<String, XKeycode> held : heldKeys.entrySet())
            if (held.getValue() == key && keyOwners.get(held.getKey()) == owner) return true;
        return false;
    }

    private boolean heldBy(MenuInput owner, Pointer.Button button) {
        for (Map.Entry<String, Pointer.Button> held : heldButtons.entrySet())
            if (held.getValue() == button && buttonOwners.get(held.getKey()) == owner) return true;
        return false;
    }

    public void releaseAll() {
        inputGeneration++;
        Map<MenuInput, HashSet<XKeycode>> releasedKeys = new IdentityHashMap<>();
        for (Map.Entry<String, XKeycode> held : heldKeys.entrySet())
            releasedKeys.computeIfAbsent(keyOwners.get(held.getKey()), ignored -> new HashSet<>()).add(held.getValue());
        Map<MenuInput, HashSet<Pointer.Button>> releasedButtons = new IdentityHashMap<>();
        for (Map.Entry<String, Pointer.Button> held : heldButtons.entrySet())
            releasedButtons.computeIfAbsent(buttonOwners.get(held.getKey()), ignored -> new HashSet<>()).add(held.getValue());

        // Retire local state before invoking a backend: a release callback may
        // reenter cleanup or acquire fresh input on a replacement route.
        heldKeys.clear();
        heldButtons.clear();
        keyOwners.clear();
        buttonOwners.clear();
        releasedAt = SystemClock.uptimeMillis();
        motionTimes.clear();
        deviceReleasedAt.clear();
        stopMouse();
        for (Map.Entry<MenuInput, HashSet<XKeycode>> entry : releasedKeys.entrySet())
            for (XKeycode key : entry.getValue())
                if (!heldBy(entry.getKey(), key)) entry.getKey().key(key, false);
        for (Map.Entry<MenuInput, HashSet<Pointer.Button>> entry : releasedButtons.entrySet())
            for (Pointer.Button button : entry.getValue())
                if (!heldBy(entry.getKey(), button)) entry.getKey().button(button, false);
    }

    public static final String MENU_HELP = "Either stick: mouse cursor (right stick takes priority)\nA: left click\nB / Select: Esc (back)\nStart / R3: Enter (confirm)\nL3: Tab\nD-pad: arrow keys\nL3 + R3: quick menu";

    public static final String LAYOUT_HELP = "Left stick: WASD (enable PD2 WASD movement)\nRight stick: mouse cursor\nA / B: left / right click\nX / Y: Shift / Alt\nLB / RB: F1 / F2\nLT / RT: 1 / 2\nD-pad up / right / down / left: F3 / F4 / F5 / F6\nSelect: Esc · Start: I\nL3: Tab · R3: Enter (tap on release)\nL3 + R3: quick menu\n\nNative controller mode uses PD2's own gamepad bindings. This fallback layout is fixed for the preview; advanced runtime profiles do not replace it.";
}
