package com.winlator.winhandler;

import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.GamepadSlot;
import com.winlator.inputcontrols.GamepadState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Fixed-width device and input reports consumed by the Wine HID bridge. */
final class ModernGamepadProtocol {
    static final int HID_PORT = 7950;
    static final int PACKET_BYTES = 256;
    private static final int SLOT_COUNT = 4;
    private static final int SLOT_BYTES = 60;

    private ModernGamepadProtocol() { }

    static byte[] hidDevice(GamepadSlot device) {
        return devices(new GamepadSlot[]{device}, GamepadHandler.DINPUT_MAPPER_TYPE_XINPUT,
                new short[]{(short)0x045e, (short)0x02a1}, null);
    }

    static byte[] devices(GamepadSlot[] slots, byte mapperType, short[] modelIds, boolean[] vibration) {
        ByteBuffer packet = packet();
        packet.put(RequestCodes.GET_GAMEPAD);
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            GamepadSlot device = slots != null && slot < slots.length ? slots[slot] : null;
            if (device == null) continue;
            packet.position(slot * SLOT_BYTES + 1);
            boolean xinput = mapperType == GamepadHandler.DINPUT_MAPPER_TYPE_XINPUT;
            packet.put((byte)1).put((byte)(xinput ? 10 : 12))
                    .put(xinput ? GamepadHandler.AXIS_MODE_X_Y_RX_RY_Z_RZ : GamepadHandler.AXIS_MODE_X_Y_Z_RZ)
                    .put((byte)(vibration != null && slot < vibration.length && vibration[slot] ? 1 : 0));
            packet.putShort(modelIds != null && modelIds.length == 2 ? modelIds[0] : device.getVendorId());
            packet.putShort(modelIds != null && modelIds.length == 2 ? modelIds[1] : device.getProductId());
            String name = device.getName();
            byte[] bytes = name != null ? name.getBytes(StandardCharsets.UTF_8) : new byte[0];
            int length = Math.min(bytes.length, 48);
            if (length < bytes.length) while (length > 0 && (bytes[length] & 0xc0) == 0x80) length--;
            packet.put((byte)length).put(bytes, 0, length);
        }
        return packet.array();
    }

    static byte[] state(int slot, byte mapperType, GamepadState state) {
        if (slot < 0 || slot >= SLOT_COUNT) throw new IllegalArgumentException("Invalid gamepad slot");
        ByteBuffer packet = packet();
        GamepadState controls = state != null ? state : new GamepadState();
        short buttons = controls.buttons;
        boolean xinput = mapperType == GamepadHandler.DINPUT_MAPPER_TYPE_XINPUT;
        if (!xinput) {
            if (controls.triggerL > 0) buttons |= (1 << ExternalController.IDX_BUTTON_L2);
            if (controls.triggerR > 0) buttons |= (1 << ExternalController.IDX_BUTTON_R2);
        }
        packet.put(RequestCodes.GET_GAMEPAD_STATE).put((byte)slot).putShort(buttons).put(controls.getPovHat())
                .putShort(axis(controls.thumbLX)).putShort(axis(controls.thumbLY))
                .putShort(axis(controls.thumbRX)).putShort(axis(controls.thumbRY));
        if (xinput) packet.putShort(trigger(controls.triggerL)).putShort(trigger(controls.triggerR));
        return packet.array();
    }

    private static short axis(float value) {
        return Float.isNaN(value) ? 0 : (short)(Math.max(-1, Math.min(1, value)) * Short.MAX_VALUE);
    }

    private static short trigger(float value) {
        return Float.isNaN(value) ? 0 : (short)(Math.max(0, Math.min(1, value)) * Short.MAX_VALUE);
    }

    private static ByteBuffer packet() {
        return ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    }
}
