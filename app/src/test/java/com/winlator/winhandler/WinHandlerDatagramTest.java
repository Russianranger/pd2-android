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

    @Test public void menuMouseAndKeyboardPacketsMatchThePackagedWindowsHandler() throws Exception {
        RecordingSocket socket = new RecordingSocket();
        WinHandler handler = configured(socket);
        handler.initReceived = true;
        handler.mouseEvent(MouseEventFlags.MOVE, -14, 9, 0, () -> true);
        handler.mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0, () -> true);
        handler.mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0, () -> true);
        handler.keyboardEvent((byte)0x1b, 0, () -> true);
        handler.keyboardEvent((byte)0x1b, 2, () -> true);
        assertEquals(5, handler.drainPendingActions());
        assertEquals(5, socket.packets.size());
        java.nio.ByteBuffer move = java.nio.ByteBuffer.wrap(socket.packets.get(0)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(256, move.capacity());
        assertEquals(RequestCodes.MOUSE_EVENT, move.get(0));
        assertEquals(10, move.getInt(1));
        assertEquals(MouseEventFlags.MOVE, move.getInt(5));
        assertEquals(-14, move.getShort(9));
        assertEquals(9, move.getShort(11));
        assertEquals(0, move.getShort(13));
        assertEquals(1, move.get(15));
        assertEquals(MouseEventFlags.LEFTDOWN, java.nio.ByteBuffer.wrap(socket.packets.get(1)).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(5));
        assertEquals(MouseEventFlags.LEFTUP, java.nio.ByteBuffer.wrap(socket.packets.get(2)).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(5));
        for (int i = 3; i < 5; i++) {
            java.nio.ByteBuffer key = java.nio.ByteBuffer.wrap(socket.packets.get(i)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            assertEquals(RequestCodes.KEYBOARD_EVENT, key.get(0));
            assertEquals(0x1b, key.get(1));
            assertEquals(i == 3 ? 0 : 2, key.getInt(2));
        }
    }

    @Test public void expiredMenuActionsCannotLeakAfterSwitchButReleasesCanStillBalanceDeliveredPresses() throws Exception {
        RecordingSocket socket = new RecordingSocket();
        WinHandler handler = configured(socket); handler.initReceived = true;
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        handler.mouseEvent(MouseEventFlags.MOVE, 14, 0, 0, active::get);
        handler.mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0, active::get);
        handler.keyboardEvent((byte)0x0d, 0, active::get);
        active.set(false);
        handler.mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
        handler.keyboardEvent((byte)0x0d, 2);
        assertEquals(5, handler.drainPendingActions());
        assertEquals(2, socket.packets.size());
        assertEquals(MouseEventFlags.LEFTUP, java.nio.ByteBuffer.wrap(socket.packets.get(0)).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(5));
        assertEquals(2, java.nio.ByteBuffer.wrap(socket.packets.get(1)).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(2));
    }

    @Test public void menuReadinessRequiresTheLiveSocketRuntimeAndInitTogether() throws Exception {
        RecordingSocket socket = new RecordingSocket();
        WinHandler handler = configured(socket);
        assertFalse(handler.isInputReady());
        handler.initReceived = true;
        assertFalse(handler.isInputReady());
        Field running = WinHandler.class.getDeclaredField("running"); running.setAccessible(true); running.setBoolean(handler, true);
        assertTrue(handler.isInputReady());
        handler.setSocketReady(false);
        assertFalse(handler.isInputReady());
    }

    @Test public void nativeFocusPacketTargetsTheCurrentGameHandleAndExpiredRequestsCannotStealFocus() throws Exception {
        RecordingSocket socket = new RecordingSocket();
        WinHandler handler = configured(socket); handler.initReceived = true;
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        handler.bringToFront("Game.exe", 0xabc123L, active::get);
        active.set(false);
        handler.bringToFront("Game.exe", 0xdef456L, () -> true);
        assertEquals(2, handler.drainPendingActions());
        assertEquals(1, socket.packets.size());
        java.nio.ByteBuffer packet = java.nio.ByteBuffer.wrap(socket.packets.get(0)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(256, packet.capacity());
        assertEquals(RequestCodes.BRING_TO_FRONT, packet.get());
        assertEquals(8, packet.getInt());
        byte[] process = new byte[8]; packet.get(process);
        assertEquals("Game.exe", new String(process, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0xdef456L, packet.getLong());
        handler.bringToFront("Game.exe"); handler.drainPendingActions();
        java.nio.ByteBuffer legacy = java.nio.ByteBuffer.wrap(socket.packets.get(1)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, legacy.getLong(13));
    }

    @Test public void repeatedSessionsRetireRealUdpWorkersReleaseThePortAndRejectDuplicateOrTerminalStarts() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        for (int iteration = 0; iteration < 3; iteration++) {
            WinHandler handler = new WinHandler(null);
            try {
                handler.start();
                await(() -> handler.controllerDiagnostics.snapshot().optBoolean("socketReady"));
                Field send = WinHandler.class.getDeclaredField("sendThread"); send.setAccessible(true);
                Field receive = WinHandler.class.getDeclaredField("receiveThread"); receive.setAccessible(true);
                Thread originalSend = (Thread)send.get(handler), originalReceive = (Thread)receive.get(handler);
                handler.start();
                assertSame(originalSend, send.get(handler)); assertSame(originalReceive, receive.get(handler));
                assertTrue(originalSend.isDaemon()); assertTrue(originalReceive.isDaemon());
                try (DatagramSocket guest = new DatagramSocket(0, loopback)) {
                    byte[] init = new byte[64]; init[0] = RequestCodes.INIT;
                    guest.send(new DatagramPacket(init, init.length, loopback, 7947));
                    await(handler::isInputReady);
                }
                handler.stop(); await(handler::areControllerWorkersStopped);
                assertFalse(handler.isInputReady());
                handler.start(); // A stopped GamepadHandler must not be reused.
                assertSame(originalSend, send.get(handler)); assertSame(originalReceive, receive.get(handler));
                assertTrue(handler.areControllerWorkersStopped());
                try (DatagramSocket released = new DatagramSocket(null)) {
                    released.setReuseAddress(false); released.bind(new java.net.InetSocketAddress(loopback, 7947));
                }
            } finally { handler.stop(); await(handler::areControllerWorkersStopped); }
        }
    }

    @Test public void realUdpOversizeAcknowledgementsCannotAuthorizeRecoveryAndExactFramesStillWork() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        WinHandler handler = new WinHandler(null);
        try (DatagramSocket backend = new DatagramSocket(7950, loopback)) {
            handler.start(); await(() -> handler.controllerDiagnostics.snapshot().optBoolean("socketReady"));
            Field token = GamepadHandler.class.getDeclaredField("hidSessionToken"); token.setAccessible(true);
            java.nio.ByteBuffer ack = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            ack.put(0, Pd2HidProtocol.ACK_CODE).put(1, (byte)1).put(2, (byte)3).put(3, (byte)4)
                    .putInt(4, Pd2HidProtocol.MAGIC).putInt(8, token.getInt(handler.gamepadHandler))
                    .putInt(16, 77).putInt(20, 1).putInt(28, 1).putLong(48, 555).putLong(56, 999);
            for (int length : new int[]{65, 256}) {
                byte[] oversized = Arrays.copyOf(ack.array(), length);
                backend.send(new DatagramPacket(oversized, oversized.length, loopback, 7947));
                long expected = length == 65 ? 1 : 2;
                await(() -> handler.controllerDiagnostics.snapshot().optJSONObject("counts").optLong("invalidPackets") == expected);
            }
            assertTrue(handler.controllerDiagnostics.snapshot().isNull("hidBackend"));
            assertFalse(handler.gamepadHandler.recoverNativeIdentity(null));
            assertEquals(0, handler.controllerDiagnostics.snapshot().getJSONObject("nativeIdentityRecovery").getLong("deviceStartObserved"));
            // Upstream runtime packets retain their prior 64-byte truncation behavior.
            byte[] upstream = new byte[256]; upstream[0] = RequestCodes.INIT;
            backend.send(new DatagramPacket(upstream, upstream.length, loopback, 7947)); await(handler::isInputReady);
            backend.send(new DatagramPacket(ack.array(), ack.capacity(), loopback, 7947));
            await(() -> handler.controllerDiagnostics.snapshot().optJSONObject("hidBackend") != null);
            assertEquals(77, handler.controllerDiagnostics.snapshot().getJSONObject("hidBackend").getInt("pid"));
            assertEquals(2, handler.controllerDiagnostics.snapshot().getJSONObject("counts").getLong("invalidPackets"));
            assertEquals(0, handler.controllerDiagnostics.snapshot().getJSONObject("nativeIdentityRecovery").getLong("deviceStartObserved"));
        } finally { handler.stop(); await(handler::areControllerWorkersStopped); }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue("Controller lifecycle did not settle within three seconds", condition.getAsBoolean());
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
