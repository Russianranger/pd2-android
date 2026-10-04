package com.winlator.winhandler;

import android.app.Application;

import com.winlator.inputcontrols.GamepadSlot;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.inputcontrols.GamepadVibration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class ModernGamepadProtocolTest {
    @Test public void fourDeviceRecordsMatchGoldenLayoutAndLeaveEmptySlotsZeroed() {
        FixtureSlot first = new FixtureSlot("Thor", 0x1234, 0x5678, false);
        FixtureSlot third = new FixtureSlot("Pad", 0xabcd, 0x9876, false);
        byte[] actual = ModernGamepadProtocol.devices(new GamepadSlot[]{first, null, third, null},
                (byte)1, null, new boolean[]{true, true, false, true});

        byte[] expected = new byte[256];
        expected[0] = 8;
        System.arraycopy(new byte[]{1, 10, 1, 1, 0x34, 0x12, 0x78, 0x56, 4, 'T', 'h', 'o', 'r'},
                0, expected, 1, 13);
        System.arraycopy(new byte[]{1, 10, 1, 0, (byte)0xcd, (byte)0xab, 0x76, (byte)0x98, 3, 'P', 'a', 'd'},
                0, expected, 121, 12);
        assertEquals(7950, ModernGamepadProtocol.HID_PORT);
        assertEquals(256, ModernGamepadProtocol.PACKET_BYTES);
        assertArrayEquals(expected, actual);
    }

    @Test public void standardDiscoveryUsesTwelveButtonsAndGlobalModelOverride() {
        FixtureSlot slot = new FixtureSlot("Pad", 0x1234, 0x5678, false);
        byte[] actual = ModernGamepadProtocol.devices(new GamepadSlot[]{slot, slot, null, null},
                (byte)0, new short[]{0x045e, 0x02a1}, null);
        byte[] expected = new byte[256];
        expected[0] = 8;
        byte[] record = {1, 12, 0, 0, 0x5e, 4, (byte)0xa1, 2, 3, 'P', 'a', 'd'};
        System.arraycopy(record, 0, expected, 1, record.length);
        System.arraycopy(record, 0, expected, 61, record.length);
        assertArrayEquals(expected, actual);
    }

    @Test public void namesOver128Utf8BytesTruncateWithoutSplittingCodePointsOrSignedLengths() {
        String name = "T" + repeat("\uD83D\uDE80", 40);
        assertTrue(name.getBytes(StandardCharsets.UTF_8).length > 128);
        byte[] packet = ModernGamepadProtocol.devices(new GamepadSlot[]{
                new FixtureSlot(name, 0x1234, 0x5678, false), null, null, null}, (byte)1, null, null);
        assertEquals(256, packet.length);
        assertEquals(45, packet[9] & 0xff);
        assertEquals("T" + repeat("\uD83D\uDE80", 11),
                new String(packet, 10, packet[9] & 0xff, StandardCharsets.UTF_8));
        assertZeroRange(packet, 55, packet.length);

        packet = ModernGamepadProtocol.devices(new GamepadSlot[]{
                new FixtureSlot(repeat("x", 200), 0x1234, 0x5678, false), null, null, null},
                (byte)1, null, null);
        assertEquals(48, packet[9] & 0xff);
        assertEquals(repeat("x", 48), new String(packet, 10, 48, StandardCharsets.UTF_8));
        assertZeroRange(packet, 58, packet.length);
    }

    @Test public void disconnectingADeviceCannotLeakItsPreviousRecordOrName() {
        GamepadSlot[] slots = {new FixtureSlot(repeat("Old", 20), 0xbeef, 0xcafe, false), null, null, null};
        byte[] connected = ModernGamepadProtocol.devices(slots, (byte)1, null, new boolean[]{true});
        assertEquals(1, connected[1]);
        slots[0] = null;
        byte[] absent = ModernGamepadProtocol.devices(slots, (byte)1, null, new boolean[]{true});
        assertEquals(256, absent.length);
        assertEquals(8, absent[0]);
        assertZeroRange(absent, 1, absent.length);
        assertEquals(1, connected[1]); // A subsequent response cannot mutate an earlier packet.
    }

    @Test public void hidIdentityIsFixedXboxSlotZeroDespiteSourceIdsAndVibration() {
        FixtureSlot slot = new FixtureSlot("Other controller", 0xbeef, 0xcafe, true);
        assertNotNull(slot.getGamepadVibration());
        byte[] packet = ModernGamepadProtocol.hidDevice(slot);
        assertEquals(256, packet.length);
        assertArrayEquals(new byte[]{8, 1, 10, 1, 0, 0x5e, 4, (byte)0xa1, 2}, Arrays.copyOf(packet, 9));
        assertTrue((packet[9] & 0xff) <= 48);
        assertZeroRange(packet, 61, packet.length);
    }

    @Test public void absentHidDeviceClearsAllFourRecords() {
        byte[] packet = ModernGamepadProtocol.hidDevice(null);
        assertEquals(256, packet.length);
        assertEquals(8, packet[0]);
        assertZeroRange(packet, 1, packet.length);
    }

    @Test public void xinputStateMatchesGoldenAnalogTriggerAndAxisOffsets() {
        GamepadState state = goldenState();
        state.triggerL = 1;
        state.triggerR = 0.25f;
        byte[] packet = ModernGamepadProtocol.state(3, (byte)1, state);
        byte[] expected = new byte[256];
        byte[] prefix = {9, 3, 1, 2, 1, (byte)0xff, 0x7f, 1, (byte)0x80,
                (byte)0xff, 0x3f, 1, (byte)0xc0, (byte)0xff, 0x7f, (byte)0xff, 0x1f};
        System.arraycopy(prefix, 0, expected, 0, prefix.length);
        assertArrayEquals(expected, packet);
    }

    @Test public void standardStateUsesDigitalTriggerBitsWithoutAnalogFields() {
        GamepadState state = goldenState();
        state.triggerL = 1;
        state.triggerR = 1;
        byte[] expected = new byte[256];
        byte[] prefix = {9, 2, 1, 14, 1, (byte)0xff, 0x7f, 1, (byte)0x80,
                (byte)0xff, 0x3f, 1, (byte)0xc0};
        System.arraycopy(prefix, 0, expected, 0, prefix.length);
        assertArrayEquals(expected, ModernGamepadProtocol.state(2, (byte)0, state));
        state.triggerL = state.triggerR = 0;
        assertEquals((short)0x0201, littleEndian(ModernGamepadProtocol.state(2, (byte)0, state)).getShort(2));
    }

    @Test public void neutralStateAndInvalidFloatInputsUseBoundedZeroPaddedValues() {
        byte[] neutral = ModernGamepadProtocol.state(0, (byte)1, new GamepadState());
        byte[] expected = new byte[256];
        expected[0] = 9;
        expected[4] = -1;
        assertArrayEquals(expected, neutral);

        GamepadState state = new GamepadState();
        state.thumbLX = Float.POSITIVE_INFINITY;
        state.thumbLY = Float.NEGATIVE_INFINITY;
        state.thumbRX = Float.NaN;
        state.thumbRY = -2;
        state.triggerL = Float.POSITIVE_INFINITY;
        state.triggerR = Float.NaN;
        byte[] packet = ModernGamepadProtocol.state(0, (byte)1, state);
        ByteBuffer fields = littleEndian(packet);
        assertEquals(32767, fields.getShort(5));
        assertEquals(-32767, fields.getShort(7));
        assertEquals(0, fields.getShort(9));
        assertEquals(-32767, fields.getShort(11));
        assertEquals(32767, fields.getShort(13));
        assertEquals(0, fields.getShort(15));
        assertZeroRange(packet, 17, packet.length);
        state.triggerL = -0.25f;
        state.triggerR = 2;
        fields = littleEndian(ModernGamepadProtocol.state(0, (byte)1, state));
        assertEquals(0, fields.getShort(13));
        assertEquals(32767, fields.getShort(15));
    }

    @Test public void invalidStateSlotsAreRejectedRatherThanAliasingAnotherController() {
        for (int slot : new int[]{-1, 4, 256}) {
            try {
                ModernGamepadProtocol.state(slot, (byte)1, new GamepadState());
                fail("Invalid controller slot must be rejected: " + slot);
            }
            catch (IllegalArgumentException expected) { /* Invalid wire identity cannot be emitted. */ }
        }
    }

    private static GamepadState goldenState() {
        GamepadState state = new GamepadState();
        state.buttons = (short)0x0201;
        state.dpad[0] = true;
        state.dpad[1] = true;
        state.thumbLX = 1;
        state.thumbLY = -1;
        state.thumbRX = 0.5f;
        state.thumbRY = -0.5f;
        return state;
    }

    private static ByteBuffer littleEndian(byte[] packet) {
        return ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void assertZeroRange(byte[] packet, int start, int end) {
        for (int index = start; index < end; index++) assertEquals("offset " + index, 0, packet[index]);
    }

    private static String repeat(String text, int count) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < count; index++) output.append(text);
        return output.toString();
    }

    private static final class FixtureVibration extends GamepadVibration {
        FixtureVibration() { super("missing-modern-codec-fixture"); }
    }

    private static final class FixtureSlot implements GamepadSlot {
        private final String name;
        private final short vendor, product;
        private final GamepadState state = new GamepadState();
        private final GamepadVibration vibration;
        FixtureSlot(String name, int vendor, int product, boolean vibration) {
            this.name = name;
            this.vendor = (short)vendor;
            this.product = (short)product;
            this.vibration = vibration ? new FixtureVibration() : null;
        }
        @Override public String getName() { return name; }
        @Override public short getVendorId() { return vendor; }
        @Override public short getProductId() { return product; }
        @Override public GamepadState getGamepadState() { return state; }
        @Override public GamepadVibration getGamepadVibration() { return vibration; }
    }
}
