package com.winlator.pd2;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.winlator.inputcontrols.ExternalController;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

import java.util.HashMap;
import java.util.Map;

/** The fallback layout only. Native mode continues through Wine's gamepad driver. */
public final class Pd2InputRouter {
    private final XServer xServer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, XKeycode> heldKeys = new HashMap<>();
    private final Map<String, Pointer.Button> heldButtons = new HashMap<>();
    private float mouseX, mouseY, remainderX, remainderY, cursorSpeed = 1.0f, deadzone = 0.18f;
    private boolean mouseScheduled;
    private final Runnable moveMouse = new Runnable() {
        @Override public void run() {
            mouseScheduled = false;
            if (mouseX == 0 && mouseY == 0) return;
            float dx = remainderX + mouseX * 14 * cursorSpeed;
            float dy = remainderY + mouseY * 14 * cursorSpeed;
            int ix = (int) dx, iy = (int) dy;
            remainderX = dx - ix;
            remainderY = dy - iy;
            xServer.injectPointerMoveDelta(ix, iy);
            mouseScheduled = true;
            handler.postDelayed(this, 16);
        }
    };

    public Pd2InputRouter(XServer xServer) { this.xServer = xServer; }

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
            if (!alreadyHeld) xServer.injectKeyPress(keycode);
        } else {
            XKeycode previous = heldKeys.remove(source);
            if (previous != null && !heldKeys.containsValue(previous)) xServer.injectKeyRelease(previous);
        }
    }

    private void button(String source, Pointer.Button button, boolean down) {
        if (down) {
            if (heldButtons.containsKey(source)) return;
            boolean alreadyHeld = heldButtons.containsValue(button);
            heldButtons.put(source, button);
            if (!alreadyHeld) xServer.injectPointerButtonPress(button);
        } else {
            Pointer.Button previous = heldButtons.remove(source);
            if (previous != null && !heldButtons.containsValue(previous)) xServer.injectPointerButtonRelease(previous);
        }
    }

    private void directions(String source, float value, XKeycode negative, XKeycode positive) {
        key(source + "-", negative, value < 0);
        key(source + "+", positive, value > 0);
    }

    public boolean motion(MotionEvent event) {
        if (!ExternalController.isJoystickDevice(event)) return false;
        String device = event.getDeviceId() + ":";
        directions(device + "lx", axis(event, MotionEvent.AXIS_X), XKeycode.KEY_A, XKeycode.KEY_D);
        directions(device + "ly", axis(event, MotionEvent.AXIS_Y), XKeycode.KEY_W, XKeycode.KEY_S);
        int rightX = event.getDevice().getMotionRange(MotionEvent.AXIS_Z, event.getSource()) != null ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX;
        int rightY = event.getDevice().getMotionRange(MotionEvent.AXIS_RZ, event.getSource()) != null ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY;
        mouseX = axis(event, rightX);
        mouseY = axis(event, rightY);
        if (!mouseScheduled && (mouseX != 0 || mouseY != 0)) {
            mouseScheduled = true;
            handler.post(moveMouse);
        }
        float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS));
        key(device + "lt", XKeycode.KEY_1, lt > 0.35f);
        key(device + "rt", XKeycode.KEY_2, rt > 0.35f);
        directions(device + "hatx", event.getAxisValue(MotionEvent.AXIS_HAT_X), XKeycode.KEY_F6, XKeycode.KEY_F4);
        directions(device + "haty", event.getAxisValue(MotionEvent.AXIS_HAT_Y), XKeycode.KEY_F3, XKeycode.KEY_F5);
        return true;
    }

    public boolean keyEvent(KeyEvent event) {
        if (!ExternalController.isGameController(event.getDevice())) return false;
        if (event.getRepeatCount() != 0) return true;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        String source = event.getDeviceId() + ":key" + event.getKeyCode();
        XKeycode keycode;
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

    public void releaseAll() {
        for (XKeycode keycode : heldKeys.values()) xServer.injectKeyRelease(keycode);
        for (Pointer.Button button : heldButtons.values()) xServer.injectPointerButtonRelease(button);
        heldKeys.clear();
        heldButtons.clear();
        mouseX = mouseY = remainderX = remainderY = 0;
        handler.removeCallbacks(moveMouse);
        mouseScheduled = false;
    }

    public static final String LAYOUT_HELP = "Left stick: WASD (enable PD2 WASD movement)\nRight stick: mouse cursor\nA / B: left / right click\nX / Y: Shift / Alt\nLB / RB: F1 / F2\nLT / RT: 1 / 2\nD-pad up / right / down / left: F3 / F4 / F5 / F6\nSelect: Esc · Start: I\nL3: Tab · R3: Enter (tap on release)\nL3 + R3: quick menu\n\nNative controller mode uses PD2's own gamepad bindings. This fallback layout is fixed for the preview; advanced runtime profiles do not replace it.";
}
