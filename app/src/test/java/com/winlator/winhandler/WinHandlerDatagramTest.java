package com.winlator.winhandler;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.DatagramSocketImpl;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class WinHandlerDatagramTest {
    @Test public void alternatingLegacyHidAndRuntimePacketsRetainTheirExactDatagramLengths() throws Exception {
        RecordingSocket socket = new RecordingSocket();
        WinHandler handler = configured(socket);
        byte[] legacy = LegacyGamepadProtocol.device(1, (byte)1, "Thor");
        byte[] hid = ModernGamepadProtocol.hidDevice(null);
        assertTrue(handler.sendPacket(7949, legacy));
        assertTrue(handler.sendPacket(7950, hid));
        handler.sendData.rewind();
        handler.sendData.put(RequestCodes.KEYBOARD_EVENT).put((byte)0x20).putInt(0);
        assertTrue(handler.sendPacket(7946));
        assertTrue(handler.sendPacket(7949, legacy));
        assertArrayEquals(legacy, socket.packets.get(0));
        assertArrayEquals(hid, socket.packets.get(1));
        assertEquals(256, socket.packets.get(2).length);
        assertEquals(RequestCodes.KEYBOARD_EVENT, socket.packets.get(2)[0]);
        assertArrayEquals(legacy, socket.packets.get(3));
        assertEquals(1, handler.controllerDiagnostics.snapshot().getJSONObject("counts").getLong("hidDeviceReplies7950"));
    }

    @Test public void failedShortDatagramRestoresTheWidePacketAndSocketReadinessStillGatesSends() throws Exception {
        RecordingSocket socket = new RecordingSocket();
        WinHandler handler = configured(socket);
        socket.failNext = true;
        assertFalse(handler.sendPacket(7949, LegacyGamepadProtocol.state(1, true, null)));
        handler.sendData.rewind();
        handler.sendData.put(RequestCodes.INIT);
        assertTrue(handler.sendPacket(7946));
        assertEquals(256, socket.packets.get(0).length);
        assertEquals(RequestCodes.INIT, socket.packets.get(0)[0]);
        handler.setSocketReady(false);
        assertFalse(handler.sendPacket(7950, ModernGamepadProtocol.hidDevice(null)));
        assertEquals(1, socket.packets.size());
        assertEquals(1, handler.controllerDiagnostics.snapshot().getJSONObject("counts").getLong("replyFailures"));
    }

    private static WinHandler configured(RecordingSocket socket) throws Exception {
        WinHandler handler = new WinHandler(null);
        Field socketField = WinHandler.class.getDeclaredField("socket"); socketField.setAccessible(true);
        socketField.set(handler, socket);
        Field localhost = WinHandler.class.getDeclaredField("localhost"); localhost.setAccessible(true);
        localhost.set(handler, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        handler.setSocketReady(true);
        return handler;
    }

    private static final class RecordingSocket extends DatagramSocket {
        final ArrayList<byte[]> packets = new ArrayList<>();
        boolean failNext;
        RecordingSocket() throws SocketException { super(new NoNetworkSocketImpl()); }
        @Override public void send(DatagramPacket packet) throws IOException {
            if (failNext) { failNext = false; throw new IOException("Synthetic send failure"); }
            packets.add(Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength()));
        }
    }

    /** Exercise WinHandler's real packet reuse without opening a host socket. */
    private static final class NoNetworkSocketImpl extends DatagramSocketImpl {
        @Override protected void create() { }
        @Override protected void bind(int port, InetAddress address) { }
        @Override protected void send(DatagramPacket packet) { }
        @Override protected int peek(InetAddress address) { return 0; }
        @Override protected int peekData(DatagramPacket packet) { return 0; }
        @Override protected void receive(DatagramPacket packet) { }
        @Override protected void setTTL(byte ttl) { }
        @Override protected byte getTTL() { return 0; }
        @Override protected void setTimeToLive(int ttl) { }
        @Override protected int getTimeToLive() { return 0; }
        @Override protected void join(InetAddress group) { }
        @Override protected void leave(InetAddress group) { }
        @Override protected void joinGroup(SocketAddress group, NetworkInterface network) { }
        @Override protected void leaveGroup(SocketAddress group, NetworkInterface network) { }
        @Override protected void close() { }
        @Override public void setOption(int option, Object value) { }
        @Override public Object getOption(int option) { return null; }
    }
}
