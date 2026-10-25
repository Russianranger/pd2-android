package com.winlator.winhandler;

import android.app.Application;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.winlator.inputcontrols.ExternalController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;

import static org.junit.Assert.*;

/** Real Android KeyEvents at the native producer boundary; no Windows consumer is simulated. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class GamepadHandlerInputTest {
    @Test public void fallbackAndMultipleCannotCancelPhysicalShoulderHold() throws Exception {
        WinHandler handler = new WinHandler(null);
        ExternalController controller = attach(handler);
        assertTrue(handler.gamepadHandler.onKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1, 0, 0)));
        assertTrue(handler.gamepadHandler.onKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L1, 0, KeyEvent.FLAG_FALLBACK)));
        assertTrue(handler.gamepadHandler.onKeyEvent(event(KeyEvent.ACTION_MULTIPLE, KeyEvent.KEYCODE_BUTTON_L1, 0, 0)));
        assertTrue(controller.getGamepadState().isPressed(ExternalController.IDX_BUTTON_L1));
        assertTrue(handler.gamepadHandler.onKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L1, 0, 0)));
        assertFalse(controller.getGamepadState().isPressed(ExternalController.IDX_BUTTON_L1));
        handler.stop();
    }

    @Test public void repeatsAreConsumedButRealUpAlwaysBalancesThePress() throws Exception {
        WinHandler handler = new WinHandler(null);
        ExternalController controller = attach(handler);
        assertTrue(handler.gamepadHandler.onKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1, 2, 0)));
        assertFalse(controller.getGamepadState().isPressed(ExternalController.IDX_BUTTON_R1));
        assertTrue(handler.gamepadHandler.onKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1, 0, 0)));
        assertTrue(handler.gamepadHandler.onKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1, 1, 0)));
        assertFalse(controller.getGamepadState().isPressed(ExternalController.IDX_BUTTON_R1));
        handler.stop();
    }

    private static KeyEvent event(int action, int key, int repeat, int flags) {
        return new KeyEvent(1, 2, action, key, repeat, 0, 92, 0, flags, InputDevice.SOURCE_GAMEPAD);
    }

    @SuppressWarnings("unchecked")
    private static ExternalController attach(WinHandler handler) throws Exception {
        ExternalController controller = new ExternalController();
        Field id = ExternalController.class.getDeclaredField("deviceId");
        id.setAccessible(true);
        id.setInt(controller, 92);
        Field connected = GamepadHandler.class.getDeclaredField("connectedControllers");
        connected.setAccessible(true);
        ((ArrayList<ExternalController>) connected.get(handler.gamepadHandler)).add(controller);
        return controller;
    }
}
