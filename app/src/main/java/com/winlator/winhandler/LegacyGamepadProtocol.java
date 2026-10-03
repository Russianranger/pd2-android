package com.winlator.winhandler;

import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.GamepadState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Wire layout used by the released Winlator Wine 9.2 dinput/xinput DLLs. */
final class LegacyGamepadProtocol {
    static final int DINPUT_PORT = 7948;
    static final int XINPUT_PORT = 7949;
    static final int PACKET_BYTES = 64;
    static final int GAMEPAD_ID = 1;

    private LegacyGamepadProtocol() {}

    static boolean usesLegacyProtocol(String wineIdentifier, int port) {
        return "wine-9.2-custom".equals(wineIdentifier)
                && (port == DINPUT_PORT || port == XINPUT_PORT);
    }

    static byte[] device(int id, byte mapperType, String name) {
        ByteBuffer packet = packet();
        packet.put(RequestCodes.GET_GAMEPAD);
        packet.putInt(Math.max(0, id));
        packet.put(id > 0 ? mapperType : (byte)0);
        byte[] bytes = id > 0 && name != null ? name.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int length = Math.min(bytes.length, PACKET_BYTES - 10);
        // Do not split a UTF-8 code point at the fixed receiver buffer boundary.
        if (length < bytes.length) while (length > 0 && (bytes[length] & 0xc0) == 0x80) length--;
        packet.putInt(length);
        packet.put(bytes, 0, length);
        return packet.array();
    }

    static byte[] state(int id, boolean connected, GamepadState state) {
        ByteBuffer packet = packet();
        boolean enabled = connected && id > 0;
        packet.put(RequestCodes.GET_GAMEPAD_STATE);
        packet.put((byte)(enabled ? 1 : 0));
        packet.putInt(enabled ? id : 0);
        GamepadState controls = enabled && state != null ? state : new GamepadState();
        short buttons = controls.buttons;
        // This Wine generation has digital trigger bits, no analog trigger fields.
        if (controls.triggerL > 0.5f) buttons |= (1 << ExternalController.IDX_BUTTON_L2);
        if (controls.triggerR > 0.5f) buttons |= (1 << ExternalController.IDX_BUTTON_R2);
        packet.putShort(buttons);
        packet.put(controls.getPovHat());
        packet.putShort(axis(controls.thumbLX));
        packet.putShort(axis(controls.thumbLY));
        packet.putShort(axis(controls.thumbRX));
        packet.putShort(axis(controls.thumbRY));
        return packet.array();
    }

    private static short axis(float value) {
        if (Float.isNaN(value)) return 0;
        return (short)(Math.max(-1, Math.min(1, value)) * Short.MAX_VALUE);
    }

    private static ByteBuffer packet() {
        return ByteBuffer.allocate(PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    }
}
