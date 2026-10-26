package com.winlator.winhandler;

import android.app.Application;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.GamepadSlot;
import com.winlator.inputcontrols.GamepadState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

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

    @Test public void retiringADeviceBalancesOnlyItsNativeThumbsAndQueuesEverySubscribedRoute() throws Exception {
        NativeHandler handler = new NativeHandler();
        ExternalController first = attachNative(handler, 92, 0);
        ExternalController other = attachNative(handler, 93, 1);
        GamepadState state = first.getGamepadState(), otherState = other.getGamepadState();
        state.buttons = (short)((1 << ExternalController.IDX_BUTTON_L3)
                | (1 << ExternalController.IDX_BUTTON_R3) | (1 << ExternalController.IDX_BUTTON_A));
        state.triggerL = 0.75f;
        state.triggerR = 0.25f;
        state.thumbLX = 0.5f;
        state.thumbRY = -0.25f;
        state.dpad[1] = true;
        otherState.buttons = (short)((1 << ExternalController.IDX_BUTTON_L3)
                | (1 << ExternalController.IDX_BUTTON_R3) | (1 << ExternalController.IDX_BUTTON_B));
        otherState.triggerR = 0.8f;

        handler.gamepadHandler.releaseThumbButtons(92);
        assertEquals(1 << ExternalController.IDX_BUTTON_A, state.buttons);
        assertEquals(0.75f, state.triggerL, 0);
        assertEquals(0.25f, state.triggerR, 0);
        assertEquals(0.5f, state.thumbLX, 0);
        assertEquals(-0.25f, state.thumbRY, 0);
        assertTrue(state.dpad[1]);
        assertTrue(otherState.isPressed(ExternalController.IDX_BUTTON_L3));
        assertTrue(otherState.isPressed(ExternalController.IDX_BUTTON_R3));
        assertTrue(otherState.isPressed(ExternalController.IDX_BUTTON_B));
        assertEquals(0.8f, otherState.triggerR, 0);
        handler.setSocketReady(true);
        assertEquals(3, handler.drainPendingActions());
        assertEquals(3, handler.packets.size());
        int thumbMask = (1 << ExternalController.IDX_BUTTON_L3) | (1 << ExternalController.IDX_BUTTON_R3);
        for (Packet packet : handler.packets) {
            assertEquals(RequestCodes.GET_GAMEPAD_STATE, packet.data[0]);
            int buttons = ByteBuffer.wrap(packet.data).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(packet.port == LegacyGamepadProtocol.XINPUT_PORT ? 6 : 2);
            assertEquals("Retired Native thumbs remained in an outgoing state", 0, buttons & thumbMask);
            assertNotEquals("Another held button was neutralized", 0, buttons & (1 << ExternalController.IDX_BUTTON_A));
        }
        handler.gamepadHandler.releaseThumbButtons(92);
        handler.gamepadHandler.releaseThumbButtons(999);
        assertEquals("Already released or unknown devices must not queue extra states", 0, handler.drainPendingActions());
        handler.stop();
    }

    @Test public void quickPhysicalThumbPressAndReleaseRemainSeparateSnapshotsOnEveryRoute() throws Exception {
        NativeHandler handler = new NativeHandler();
        attachNative(handler, 92, 0);
        assertTrue(handler.gamepadHandler.onKeyEvent(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_THUMBL, 0, 0)));
        assertTrue(handler.gamepadHandler.onKeyEvent(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_THUMBL, 0, 0)));
        handler.setSocketReady(true);
        assertEquals(6, handler.drainPendingActions());
        assertEquals(6, handler.packets.size());
        for (int index = 0; index < handler.packets.size(); index++) {
            Packet packet = handler.packets.get(index);
            int buttons = ByteBuffer.wrap(packet.data).order(ByteOrder.LITTLE_ENDIAN)
                    .getShort(packet.port == LegacyGamepadProtocol.XINPUT_PORT ? 6 : 2);
            assertEquals(index < 3 ? 1 << ExternalController.IDX_BUTTON_L3 : 0,
                    buttons & (1 << ExternalController.IDX_BUTTON_L3));
        }
        handler.stop();
    }

    private static KeyEvent event(int action, int key, int repeat, int flags) {
        return new KeyEvent(1, 2, action, key, repeat, 0, 92, 0, flags, InputDevice.SOURCE_GAMEPAD);
    }

    @SuppressWarnings("unchecked")
    private static ExternalController attach(WinHandler handler) throws Exception {
        return attach(handler, 92);
    }

    @SuppressWarnings("unchecked")
    private static ExternalController attach(WinHandler handler, int deviceId) throws Exception {
        ExternalController controller = new ExternalController();
        // Real discovery initializes the descriptor before ArrayUtils compares slots.
        controller.setId("fixture-" + deviceId);
        controller.setName("Controller fixture " + deviceId);
        Field id = ExternalController.class.getDeclaredField("deviceId");
        id.setAccessible(true);
        id.setInt(controller, deviceId);
        Field connected = GamepadHandler.class.getDeclaredField("connectedControllers");
        connected.setAccessible(true);
        ((ArrayList<ExternalController>) connected.get(handler.gamepadHandler)).add(controller);
        return controller;
    }

    @SuppressWarnings("unchecked")
    private static ExternalController attachNative(NativeHandler handler, int deviceId, int slot) throws Exception {
        ExternalController controller = attach(handler, deviceId);
        Field slots = GamepadHandler.class.getDeclaredField("gamepadSlots"); slots.setAccessible(true);
        ((GamepadSlot[])slots.get(handler.gamepadHandler))[slot] = controller;
        if (slot == 0) {
            Field legacy = GamepadHandler.class.getDeclaredField("legacyGamepad"); legacy.setAccessible(true);
            legacy.set(handler.gamepadHandler, controller);
            Field clients = GamepadHandler.class.getDeclaredField("legacyGamepadClients"); clients.setAccessible(true);
            ((List<Integer>)clients.get(handler.gamepadHandler)).add(LegacyGamepadProtocol.XINPUT_PORT);
            Field modern = GamepadHandler.class.getDeclaredField("gamepadClients"); modern.setAccessible(true);
            ((List<Integer>)modern.get(handler.gamepadHandler)).add(32100);
            Field hid = GamepadHandler.class.getDeclaredField("hidGamepad"); hid.setAccessible(true);
            hid.set(handler.gamepadHandler, controller);
            Field subscribed = GamepadHandler.class.getDeclaredField("hidSubscribed"); subscribed.setAccessible(true);
            subscribed.setBoolean(handler.gamepadHandler, true);
        }
        return controller;
    }

    private static final class Packet {
        final int port;
        final byte[] data;
        Packet(int port, byte[] data) { this.port = port; this.data = data; }
    }

    private static final class NativeHandler extends WinHandler {
        final ArrayList<Packet> packets = new ArrayList<>();
        NativeHandler() { super(null); initReceived = true; }
        @Override protected boolean sendPacket(int port, byte[] data) {
            packets.add(new Packet(port, data.clone()));
            return true;
        }
    }
}
