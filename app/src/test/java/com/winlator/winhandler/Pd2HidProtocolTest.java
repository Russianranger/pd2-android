package com.winlator.winhandler;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class Pd2HidProtocolTest {
    @Test public void suffixPreservesAllOrdinaryFieldsAndAuthenticatesSessionAndGeneration() {
        byte[] original = new byte[256]; original[0] = 9; original[4] = -1;
        byte[] tagged = Pd2HidProtocol.tag(original, 17, 0x12345678);
        assertArrayEquals(Arrays.copyOf(original, 241), Arrays.copyOf(tagged, 241));
        assertArrayEquals(new byte[]{'P', 'D', 'R', '2', 1, 17, 0, 0, 0, 0x78, 0x56, 0x34, 0x12, 0, 0},
                Arrays.copyOfRange(tagged, 241, 256));
        assertEquals(0, original[241]);
        for (int uid : new int[]{0, Integer.MAX_VALUE}) assertEquals(uid,
                ByteBuffer.wrap(Pd2HidProtocol.tag(original, uid, 1)).order(ByteOrder.LITTLE_ENDIAN).getInt(246));
    }

    @Test public void malformedInputCannotGeneratePacketsWithAnAmbiguousIdentity() {
        byte[] valid = new byte[256]; valid[0] = 8;
        for (int[] identity : new int[][]{{-1, 1}, {0, 0}, {0, -1}}) {
            try { Pd2HidProtocol.tag(valid, identity[0], identity[1]); fail(); }
            catch (IllegalArgumentException expected) { }
        }
        for (byte[] bad : new byte[][]{null, new byte[64], new byte[256]}) {
            try { Pd2HidProtocol.tag(bad, 0, 1); fail(); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void acknowledgementGoldenLayoutCarriesOnlyLifecycleCountsAndIdentity() {
        ByteBuffer fields = validAck();
        fields.putInt(20, -1).putInt(24, 2).putInt(28, 3).putInt(32, 4)
                .putInt(36, 5).putInt(40, 6).putInt(44, 7).putLong(48, 12345678).putLong(56, 87654321);
        Pd2HidProtocol.Ack ack = Pd2HidProtocol.parseAck(fields);
        assertNotNull(ack); assertEquals(3, ack.flags); assertEquals(4, ack.stage);
        assertEquals(42, ack.sessionToken); assertEquals(1, ack.uid); assertEquals(99, ack.pid);
        assertEquals(0xffffffffL, ack.created); assertEquals(2, ack.removed); assertEquals(3, ack.started);
        assertEquals(4, ack.stopped); assertEquals(5, ack.stateReceived); assertEquals(6, ack.reportsQueued);
        assertEquals(7, ack.invalidPackets); assertEquals(12345678, ack.socketInode); assertEquals(87654321, ack.monotonicMillis);
    }

    @Test public void acknowledgementsRequireExactFramingVersionMagicAndBoundedMetadata() {
        for (int offset : new int[]{0, 1, 4}) {
            ByteBuffer packet = validAck(); packet.put(offset, (byte)(packet.get(offset) + 1));
            assertNull(Pd2HidProtocol.parseAck(packet));
        }
        for (int flags : new int[]{0, 3, 4, 7}) {
            ByteBuffer packet = validAck(); packet.put(2, (byte)flags);
            assertEquals(flags, Pd2HidProtocol.parseAck(packet).flags);
        }
        for (int[] field : new int[][]{{2,8},{3,6},{8,0},{12,-1},{16,0}}) {
            ByteBuffer packet = validAck();
            if (field[0] < 4) packet.put(field[0], (byte)field[1]); else packet.putInt(field[0], field[1]);
            assertNull(Pd2HidProtocol.parseAck(packet));
        }
        assertNull(Pd2HidProtocol.parseAck(ByteBuffer.allocate(63)));
        assertNull(Pd2HidProtocol.parseAck(ByteBuffer.allocate(65)));
        ByteBuffer packet = validAck(); packet.putLong(48, -1); assertNull(Pd2HidProtocol.parseAck(packet));
    }

    private static ByteBuffer validAck() {
        ByteBuffer packet = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        packet.put(0, (byte)0x50).put(1, (byte)1).put(2, (byte)3).put(3, (byte)4)
                .putInt(4, Pd2HidProtocol.MAGIC).putInt(8, 42).putInt(12, 1).putInt(16, 99);
        return packet;
    }
}
