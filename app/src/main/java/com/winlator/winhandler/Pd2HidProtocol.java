package com.winlator.winhandler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Session and device-generation suffix used only by the dedicated PD2 Wine HID channel. */
final class Pd2HidProtocol {
    static final byte ACK_CODE = 0x50;
    static final int MAGIC = 0x32524450; // PDR2, little endian.
    static final int VERSION = 1;
    static final int SUFFIX_OFFSET = 241;
    static final int ACK_BYTES = 64;
    static final int CONNECTIVITY_MASK = 3;
    static final int LAST_REMOVAL_STOPPED = 4;

    private Pd2HidProtocol() { }

    static byte[] tag(byte[] packet, int uid, int sessionToken) {
        if (packet == null || packet.length != ModernGamepadProtocol.PACKET_BYTES
                || (packet[0] != RequestCodes.GET_GAMEPAD && packet[0] != RequestCodes.GET_GAMEPAD_STATE)
                || uid < 0 || sessionToken <= 0) throw new IllegalArgumentException("Invalid PD2 HID identity");
        byte[] tagged = packet.clone();
        ByteBuffer fields = ByteBuffer.wrap(tagged).order(ByteOrder.LITTLE_ENDIAN);
        fields.position(SUFFIX_OFFSET);
        fields.putInt(MAGIC).put((byte)VERSION).putInt(uid).putInt(sessionToken).putShort((short)0);
        return tagged;
    }

    /** Parse only a complete, recognized acknowledgement, before checking its expected session. */
    static Ack parseAck(ByteBuffer packet) {
        if (packet == null || packet.limit() != ACK_BYTES) return null;
        ByteBuffer fields = packet.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (fields.get(0) != ACK_CODE || fields.get(1) != VERSION
                || (fields.get(2) & 0xff) > 7 || (fields.get(3) & 0xff) > 5
                || fields.getInt(4) != MAGIC || fields.getInt(8) <= 0
                || fields.getInt(12) < 0 || fields.getInt(16) <= 0
                || fields.getLong(48) < 0 || fields.getLong(56) < 0) return null;
        return new Ack(fields);
    }

    static final class Ack {
        final int flags, stage, sessionToken, uid, pid;
        final long created, removed, started, stopped, stateReceived, reportsQueued, invalidPackets;
        final long socketInode, monotonicMillis;

        private Ack(ByteBuffer fields) {
            flags = fields.get(2) & 0xff;
            stage = fields.get(3) & 0xff;
            sessionToken = fields.getInt(8);
            uid = fields.getInt(12);
            pid = fields.getInt(16);
            created = Integer.toUnsignedLong(fields.getInt(20));
            removed = Integer.toUnsignedLong(fields.getInt(24));
            started = Integer.toUnsignedLong(fields.getInt(28));
            stopped = Integer.toUnsignedLong(fields.getInt(32));
            stateReceived = Integer.toUnsignedLong(fields.getInt(36));
            reportsQueued = Integer.toUnsignedLong(fields.getInt(40));
            invalidPackets = Integer.toUnsignedLong(fields.getInt(44));
            socketInode = fields.getLong(48);
            monotonicMillis = fields.getLong(56);
        }
    }
}
