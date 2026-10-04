package com.winlator.winhandler;

import android.app.Application;
import android.os.Looper;

import com.winlator.inputcontrols.GamepadSlot;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.inputcontrols.GamepadVibration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LegacyGamepadProtocolTest {
    @Test public void devicePacketMatchesReleasedWine9OffsetsAndFixedReceiverSize() {
        byte[] packet = LegacyGamepadProtocol.device(1, (byte)1, "Thor");
        assertEquals(64, packet.length);
        assertArrayEquals(new byte[]{8, 1, 0, 0, 0, 1, 4, 0, 0, 0, 'T', 'h', 'o', 'r'}, Arrays.copyOf(packet, 14));
        for (int index = 14; index < packet.length; index++) assertEquals(0, packet[index]);
        byte[] absent = LegacyGamepadProtocol.device(0, (byte)1, "Must not leak stale name");
        assertEquals(8, absent[0]);
        for (int index = 1; index < absent.length; index++) assertEquals(0, absent[index]);
    }

    @Test public void longUtf8NameFitsReceiverWithoutSplittingACharacter() {
        String name = repeat("\uD83D\uDE80", 25);
        byte[] packet = LegacyGamepadProtocol.device(1, (byte)0, name);
        int count = littleEndian(packet).getInt(6);
        assertEquals(52, count);
        assertEquals(repeat("\uD83D\uDE80", 13), new String(packet, 10, count, StandardCharsets.UTF_8));
        assertEquals(0, packet[62]);
        assertEquals(0, packet[63]);
    }

    @Test public void statePacketUsesGoldenButtonHatAndAxisLayoutWithDigitalTriggers() {
        GamepadState state = new GamepadState();
        state.buttons = (short)((1 << 0) | (1 << 9));
        state.triggerL = 1;
        state.triggerR = 0.75f;
        state.dpad[0] = true;
        state.dpad[1] = true;
        state.thumbLX = 1;
        state.thumbLY = -1;
        state.thumbRX = 0.5f;
        state.thumbRY = -0.5f;
        byte[] packet = LegacyGamepadProtocol.state(1, true, state);
        assertEquals(64, packet.length);
        assertArrayEquals(new byte[]{9, 1, 1, 0, 0, 0, 1, 14, 1,
                (byte)255, 127, 1, (byte)128, (byte)255, 63, 1, (byte)192}, Arrays.copyOf(packet, 17));
        for (int index = 17; index < packet.length; index++) assertEquals(0, packet[index]);
        state.triggerL = 0.5f;
        state.triggerR = 0;
        assertEquals(state.buttons, littleEndian(LegacyGamepadProtocol.state(1, true, state)).getShort(6));
    }

    @Test public void neutralAndDisconnectedPacketsClearControlsAndClampInvalidAxes() {
        byte[] neutral = LegacyGamepadProtocol.state(1, true, new GamepadState());
        assertEquals(1, neutral[1]);
        assertEquals(1, littleEndian(neutral).getInt(2));
        assertEquals(0, littleEndian(neutral).getShort(6));
        assertEquals(-1, neutral[8]);
        GamepadState pressed = new GamepadState();
        pressed.buttons = (short)0xffff;
        byte[] disconnected = LegacyGamepadProtocol.state(1, false, pressed);
        assertEquals(0, disconnected[1]);
        assertEquals(0, littleEndian(disconnected).getInt(2));
        assertEquals(0, littleEndian(disconnected).getShort(6));
        pressed.thumbLX = Float.POSITIVE_INFINITY;
        pressed.thumbLY = -2;
        pressed.thumbRX = Float.NaN;
        ByteBuffer axes = littleEndian(LegacyGamepadProtocol.state(1, true, pressed));
        assertEquals(32767, axes.getShort(9));
        assertEquals(-32767, axes.getShort(11));
        assertEquals(0, axes.getShort(13));
    }

    @Test public void legacySelectionRequiresTheBundledWineVersionAndDriverPorts() {
        assertTrue(LegacyGamepadProtocol.usesLegacyProtocol("wine-9.2-custom", 7948));
        assertTrue(LegacyGamepadProtocol.usesLegacyProtocol("wine-9.2-custom", 7949));
        assertFalse(LegacyGamepadProtocol.usesLegacyProtocol("wine-10.10-custom", 7949));
        assertFalse(LegacyGamepadProtocol.usesLegacyProtocol("wine-9.2-custom", 32100));
        assertFalse(LegacyGamepadProtocol.usesLegacyProtocol(null, 7949));
    }

    @Test public void queuedPressAndReleaseAreIndependentSnapshotsForLegacyAndModernClients() throws Exception {
        FixtureHandler handler = new FixtureHandler();
        FixtureSlot slot = connect(handler, true);
        slot.state.buttons = 1;
        handler.gamepadHandler.sendGamepadState(slot);
        slot.state.buttons = 0;
        handler.gamepadHandler.sendGamepadState(slot);
        handler.drain();

        assertEquals(4, handler.packets.size());
        assertEquals(1, littleEndian(handler.packets.get(0).bytes).getShort(6));
        assertEquals(1, littleEndian(handler.packets.get(1).bytes).getShort(2));
        assertEquals(0, littleEndian(handler.packets.get(2).bytes).getShort(6));
        assertEquals(0, littleEndian(handler.packets.get(3).bytes).getShort(2));
        assertEquals(7949, handler.packets.get(0).port);
        assertEquals(32100, handler.packets.get(1).port);
        assertEquals(0, handler.packets.get(1).bytes[1]); // Modern slot index, not connected flag.
    }

    @Test public void modalOwnershipInvalidatesQueuedGameplayWithoutDisconnectingLegacyDevice() throws Exception {
        FixtureHandler handler = new FixtureHandler();
        FixtureSlot slot = connect(handler, false);
        slot.state.buttons = 1;
        slot.state.thumbLX = 1;
        handler.gamepadHandler.sendGamepadState(slot);
        handler.gamepadHandler.setInputEnabled(false);
        int queued = handler.actions.size();
        handler.gamepadHandler.sendGamepadState(slot);
        assertEquals(queued, handler.actions.size());
        handler.gamepadHandler.setInputEnabled(true);
        handler.drain();

        assertEquals(2, handler.packets.size());
        for (Packet packet : handler.packets) {
            assertEquals(1, packet.bytes[1]);
            assertEquals(1, littleEndian(packet.bytes).getInt(2));
            assertEquals(0, littleEndian(packet.bytes).getShort(6));
            assertEquals(0, littleEndian(packet.bytes).getShort(9));
        }
        assertEquals(0, slot.state.buttons);
    }

    @Test public void truncatedLegacyRequestsNeverReadStaleBufferBytesOrChangeSubscribers() throws Exception {
        FixtureHandler handler = new FixtureHandler();
        for (byte code : new byte[]{RequestCodes.GET_GAMEPAD, RequestCodes.GET_GAMEPAD_STATE}) {
            int completeBytes = code == RequestCodes.GET_GAMEPAD ? 7 : 5;
            for (int length = 1; length < completeBytes; length++) {
                // Preload valid-looking old bytes beyond this datagram's limit.
                handler.receiveData.clear();
                handler.receiveData.put(new byte[]{code, 1, 1, 0, 0, 0, 0});
                assertTrue(handler.dispatchPacket(length, 7949));
                assertTrue(handler.actions.isEmpty());
            }
        }
        Field clients = GamepadHandler.class.getDeclaredField("legacyGamepadClients"); clients.setAccessible(true);
        assertTrue(((List<?>)clients.get(handler.gamepadHandler)).isEmpty());
        assertFalse(handler.dispatchPacket(0, 7949));
        assertFalse(handler.dispatchPacket(65, 7949));
    }

    @Test public void incompleteRuntimeRequestsAreDroppedAndTheNextDatagramStillRuns() throws Exception {
        FixtureHandler handler = new FixtureHandler();
        handler.setOnGetProcessInfoListener((index, count, info) -> fail("Truncated process response cannot be delivered"));
        for (byte code : new byte[]{RequestCodes.SET_GAMEPAD_STATE, RequestCodes.GET_PROCESS,
                RequestCodes.CURSOR_POS_FEEDBACK}) {
            handler.receiveData.clear();
            handler.receiveData.put(code);
            assertFalse(handler.dispatchPacket(1, 7949));
        }
        handler.initReceived = false;
        handler.receiveData.clear();
        handler.receiveData.put(RequestCodes.INIT);
        assertTrue(handler.dispatchPacket(1, 7946));
        assertTrue(handler.initReceived);
    }

    @Test public void controllerRepliesAndPressReleaseRunBeforeInitWhileRuntimeActionsWait() throws Exception {
        FixtureHandler handler = new FixtureHandler(false);
        handler.initReceived = false;
        final int[] runtimeActions = {0};
        handler.addAction(() -> runtimeActions[0]++);
        handler.addControllerAction(() -> handler.sendPacket(7949, LegacyGamepadProtocol.device(1, (byte)1, "Thor")));

        assertEquals(0, handler.drainPendingActions()); // No bound socket yet.
        assertTrue(handler.packets.isEmpty());
        handler.setSocketReady(true);
        assertEquals(1, handler.drainPendingActions());
        assertEquals(8, handler.packets.get(0).bytes[0]);
        assertEquals(0, runtimeActions[0]);

        FixtureSlot slot = connect(handler, false);
        slot.state.buttons = 1;
        handler.gamepadHandler.sendGamepadState(slot);
        slot.state.buttons = 0;
        handler.gamepadHandler.sendGamepadState(slot);
        assertEquals(2, handler.drainPendingActions());
        assertEquals(1, littleEndian(handler.packets.get(1).bytes).getShort(6));
        assertEquals(0, littleEndian(handler.packets.get(2).bytes).getShort(6));
        assertEquals(0, runtimeActions[0]);

        handler.receiveData.clear();
        handler.receiveData.put(RequestCodes.INIT);
        handler.dispatchPacket(1, 7946);
        assertEquals(1, handler.drainPendingActions());
        assertEquals(1, runtimeActions[0]);
        assertEquals(0, handler.drainPendingActions());
    }

    @Test public void hidDiscoveryAndPressReleaseRunBeforeInitAlongsideTheLegacyBridge() throws Exception {
        FixtureHandler handler = new FixtureHandler(false);
        handler.initReceived = false;
        handler.setSocketReady(true);
        FixtureSlot slot = connect(handler, false);
        handler.gamepadHandler.setDInputMapperType(GamepadHandler.DINPUT_MAPPER_TYPE_STANDARD);
        Field models = GamepadHandler.class.getDeclaredField("gamepadModelIds"); models.setAccessible(true);
        models.set(handler.gamepadHandler, new short[]{(short)0x9999, (short)0x8888});
        handler.gamepadHandler.handleModernGetGamepadRequest(7950);
        assertEquals(1, handler.drainPendingActions());
        assertEquals(2, handler.packets.size());
        Packet discovery = handler.packets.get(0), initial = handler.packets.get(1);
        assertEquals(7950, discovery.port);
        assertEquals(256, discovery.bytes.length);
        assertEquals(1, discovery.bytes[1]);
        assertEquals(10, discovery.bytes[2]);
        assertEquals(1, discovery.bytes[3]);
        assertEquals(0x045e, littleEndian(discovery.bytes).getShort(5) & 0xffff);
        assertEquals(0x02a1, littleEndian(discovery.bytes).getShort(7) & 0xffff);
        for (int index = 61; index < 256; index++) assertEquals(0, discovery.bytes[index]);
        assertEquals(256, initial.bytes.length);
        assertEquals(9, initial.bytes[0]);
        assertEquals(0, initial.bytes[1]);
        assertEquals(-1, initial.bytes[4]);

        slot.state.buttons = 1;
        slot.state.triggerL = 1;
        handler.gamepadHandler.sendGamepadState(slot);
        slot.state.buttons = 0;
        slot.state.triggerL = 0;
        handler.gamepadHandler.sendGamepadState(slot);
        assertEquals(4, handler.drainPendingActions());
        assertEquals(64, handler.packets.get(2).bytes.length);
        assertEquals(7949, handler.packets.get(2).port);
        assertEquals(1 | 1 << 10, littleEndian(handler.packets.get(2).bytes).getShort(6));
        assertEquals(256, handler.packets.get(3).bytes.length);
        assertEquals(7950, handler.packets.get(3).port);
        assertEquals(1, littleEndian(handler.packets.get(3).bytes).getShort(2));
        assertEquals(32767, littleEndian(handler.packets.get(3).bytes).getShort(13));
        assertEquals(0, littleEndian(handler.packets.get(4).bytes).getShort(6));
        assertEquals(0, littleEndian(handler.packets.get(5).bytes).getShort(2));
        assertEquals(0, littleEndian(handler.packets.get(5).bytes).getShort(13));
        assertFalse(handler.initReceived);
    }

    @Test public void hidSlotZeroTracksFirstSelectedPadEvenWhenItsConfiguredSlotIsThree() throws Exception {
        FixtureHandler handler = new FixtureHandler();
        FixtureSlot slot = connect(handler, false);
        Field slots = GamepadHandler.class.getDeclaredField("gamepadSlots"); slots.setAccessible(true);
        GamepadSlot[] configured = (GamepadSlot[])slots.get(handler.gamepadHandler);
        configured[0] = null;
        configured[3] = slot;
        handler.gamepadHandler.handleModernGetGamepadRequest(7950);
        handler.drain();
        slot.state.thumbRX = 1;
        handler.gamepadHandler.sendGamepadState(slot);
        handler.drain();
        Packet hid = handler.packets.get(handler.packets.size() - 1);
        assertEquals(7950, hid.port);
        assertEquals(0, hid.bytes[1]);
        assertEquals(32767, littleEndian(hid.bytes).getShort(9));
    }

    @Test public void modalNeutralizationInvalidatesHidPressAndNeverLeaksEmptySlotPayloads() throws Exception {
        FixtureHandler handler = new FixtureHandler(false);
        handler.initReceived = false;
        handler.setSocketReady(true);
        FixtureSlot slot = connect(handler, true);
        handler.gamepadHandler.handleModernGetGamepadRequest(7950);
        handler.drainPendingActions();
        handler.packets.clear();
        Arrays.fill(handler.sendData.array(), (byte)0x7f);
        slot.state.buttons = 1;
        slot.state.thumbLX = 1;
        handler.gamepadHandler.sendGamepadState(slot);
        handler.gamepadHandler.setInputEnabled(false);
        handler.gamepadHandler.setInputEnabled(true);
        handler.drainPendingActions();
        int hidPackets = 0, modernPackets = 0;
        for (Packet packet : handler.packets) {
            if (packet.port == 7949) {
                assertEquals(64, packet.bytes.length);
                assertEquals(1, packet.bytes[1]);
                assertEquals(0, littleEndian(packet.bytes).getShort(6));
            }
            else {
                assertEquals(256, packet.bytes.length);
                assertEquals(0, littleEndian(packet.bytes).getShort(2));
                assertEquals(-1, packet.bytes[4]);
                for (int index = 5; index < 256; index++) assertEquals(0, packet.bytes[index]);
                if (packet.port == 7950) { hidPackets++; assertEquals(0, packet.bytes[1]); }
                else modernPackets++;
            }
        }
        assertEquals(2, hidPackets);
        assertEquals(5, modernPackets); // Queued slot0 plus neutral reports for all four slots.
    }

    @Test public void emptyHidDiscoveryClearsAllRecordsAndReleaseStopsHidPushes() throws Exception {
        FixtureHandler handler = new FixtureHandler();
        FixtureSlot slot = connect(handler, false);
        handler.gamepadHandler.handleModernGetGamepadRequest(7950);
        handler.drain();
        handler.gamepadHandler.handleReleaseGamepadRequest(7950);
        handler.packets.clear();
        slot.state.buttons = 1;
        handler.gamepadHandler.sendGamepadState(slot);
        handler.drain();
        assertEquals(1, handler.packets.size());
        assertEquals(7949, handler.packets.get(0).port);
        Field slots = GamepadHandler.class.getDeclaredField("gamepadSlots"); slots.setAccessible(true);
        Arrays.fill((GamepadSlot[])slots.get(handler.gamepadHandler), null);
        handler.packets.clear();
        handler.gamepadHandler.handleModernGetGamepadRequest(7950);
        handler.drain();
        assertEquals(1, handler.packets.size());
        byte[] absent = handler.packets.get(0).bytes;
        assertEquals(256, absent.length);
        assertEquals(8, absent[0]);
        for (int index = 1; index < absent.length; index++) assertEquals(0, absent[index]);
    }

    @Test public void explicitReconnectExpiresQueuedControlsAndHeartbeatCannotReconnectBeforeSentRemovalGap() throws Exception {
        FixtureHandler handler = new FixtureHandler(); FixtureSlot slot = connectHid(handler);
        slot.state.buttons = 1; slot.state.thumbLX = 1;
        handler.gamepadHandler.sendGamepadState(slot);
        handler.gamepadHandler.handleModernGetGamepadRequest(7950); // Old positive metadata waiting in queue.
        queueLegacyDiscovery(handler); // Old positive XInput metadata must also stay absent.
        assertTrue(handler.gamepadHandler.reconnectNativeDevice());
        // The interval starts at the actual removal send, not at request or queue time.
        advanceReconnect(1000); assertTrue(handler.packets.isEmpty());
        handler.drain();
        assertTrue(handler.packets.stream().anyMatch(packet -> packet.port == 7950 && packet.bytes[0] == 8));
        assertBlackoutPackets(handler.packets);
        handler.packets.clear();
        slot.state.buttons = 1; slot.state.thumbLX = 1;
        handler.gamepadHandler.sendGamepadState(slot);
        handler.gamepadHandler.handleModernGetGamepadRequest(7950);
        queueLegacyDiscovery(handler);
        queueLegacyPoll(handler);
        handler.drain(); assertBlackoutPackets(handler.packets);
        handler.packets.clear();
        advanceReconnect(599); handler.drain(); assertTrue(handler.packets.isEmpty());
        // Queue another heartbeat in the detached phase, then transition to attach before draining it.
        handler.gamepadHandler.handleModernGetGamepadRequest(7950);
        queueLegacyDiscovery(handler);
        queueLegacyPoll(handler);
        advanceReconnect(1); handler.drain();
        assertAttachPackets(handler.packets);
        // Nothing from the blackout may replay after the attach-completion callback.
        handler.gamepadHandler.sendGamepadState(slot); advanceReconnect(0); handler.drain();
        assertNeutral(handler.packets.get(4)); assertNeutral(handler.packets.get(5));
        handler.packets.clear();
        slot.state.buttons = 1; handler.gamepadHandler.sendGamepadState(slot); handler.drain();
        assertEquals(1, littleEndian(handler.packets.get(0).bytes).getShort(6));
        assertEquals(1, littleEndian(handler.packets.get(1).bytes).getShort(2));
        org.json.JSONObject reconnect = handler.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect");
        assertEquals(1, reconnect.getLong("detachSent")); assertEquals(1, reconnect.getLong("attachSent"));
        assertEquals(1, reconnect.getLong("completed")); assertEquals(0, reconnect.getLong("sendFailures"));
        advanceReconnect(3000); assertEquals(0, handler.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect").getLong("timedOut"));
    }

    @Test public void modalCancellationReAdvertisesOnlyNeutralStateAndExpiresAttachTimer() throws Exception {
        FixtureHandler handler = new FixtureHandler(); FixtureSlot slot = connectHid(handler);
        assertTrue(handler.gamepadHandler.reconnectNativeDevice()); handler.drain(); handler.packets.clear();
        slot.state.buttons = 1; handler.gamepadHandler.sendGamepadState(slot);
        handler.gamepadHandler.setInputEnabled(false); handler.drain();
        assertTrue(handler.packets.stream().anyMatch(packet -> packet.port == 7950 && packet.bytes[0] == 8 && packet.bytes[1] == 1));
        for (Packet packet : handler.packets) if (packet.bytes[0] == 9) assertNeutral(packet);
        int sent = handler.packets.size(); advanceReconnect(3000); handler.drain();
        assertEquals(sent, handler.packets.size());
        assertEquals(1, handler.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect").getLong("cancelled"));
        assertEquals(0, handler.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect").getLong("attachSent"));
        handler.gamepadHandler.setInputEnabled(true); handler.packets.clear();
        slot.state.buttons = 1; handler.gamepadHandler.sendGamepadState(slot); handler.drain();
        assertEquals(1, littleEndian(handler.packets.get(1).bytes).getShort(2));
    }

    @Test public void stopAndUnavailableHidCannotSendLateReconnectPacketsOrFakeControls() throws Exception {
        FixtureHandler absent = new FixtureHandler(); connect(absent, false);
        assertFalse(absent.gamepadHandler.reconnectNativeDevice()); absent.drain(); assertTrue(absent.packets.isEmpty());
        assertEquals(1, absent.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect").getLong("unavailable"));
        FixtureHandler handler = new FixtureHandler(); FixtureSlot slot = connectHid(handler);
        assertTrue(handler.gamepadHandler.reconnectNativeDevice()); handler.drain(); handler.packets.clear();
        handler.gamepadHandler.stop();
        slot.state.buttons = 1; handler.gamepadHandler.sendGamepadState(slot);
        advanceReconnect(3000); handler.drain(); assertTrue(handler.packets.isEmpty());
        assertEquals(0, handler.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect").getLong("attachSent"));
    }

    @Test public void delayedQueueAndFailedRemovalAreBoundedWithoutLeavingNativeInputBlackened() throws Exception {
        FixtureHandler queued = new FixtureHandler(); FixtureSlot queuedSlot = connectHid(queued);
        assertTrue(queued.gamepadHandler.reconnectNativeDevice());
        advanceReconnect(2500); queued.drain();
        assertEquals(1, queued.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect").getLong("timedOut"));
        for (Packet packet : queued.packets) if (packet.port == 7950 && packet.bytes[0] == 8) assertEquals(1, packet.bytes[1]);
        queued.packets.clear(); queuedSlot.state.buttons = 1; queued.gamepadHandler.sendGamepadState(queuedSlot); queued.drain();
        assertEquals(1, littleEndian(queued.packets.get(1).bytes).getShort(2));
        FixtureHandler failed = new FixtureHandler(); FixtureSlot failedSlot = connectHid(failed);
        assertTrue(failed.gamepadHandler.reconnectNativeDevice());
        failed.failNextDiscovery = true; failed.drain(); advanceReconnect(0); failed.drain();
        assertEquals(1, failed.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect").getLong("sendFailures"));
        assertEquals(0, failed.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect").getLong("detachSent"));
        failed.packets.clear(); failedSlot.state.buttons = 1; failed.gamepadHandler.sendGamepadState(failedSlot); failed.drain();
        assertEquals(1, littleEndian(failed.packets.get(1).bytes).getShort(2));
    }

    @Test public void supersededReconnectCannotReattachBeforeTheLatestSentRemovalInterval() throws Exception {
        FixtureHandler handler = new FixtureHandler(); connectHid(handler);
        assertTrue(handler.gamepadHandler.reconnectNativeDevice()); handler.drain();
        advanceReconnect(300); assertTrue(handler.gamepadHandler.reconnectNativeDevice()); handler.drain();
        handler.packets.clear(); advanceReconnect(599); handler.drain(); assertTrue(handler.packets.isEmpty());
        advanceReconnect(1); handler.drain(); advanceReconnect(0);
        assertAttachPackets(handler.packets);
        org.json.JSONObject reconnect = handler.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect");
        assertEquals(2, reconnect.getLong("requests")); assertEquals(2, reconnect.getLong("detachSent"));
        assertEquals(1, reconnect.getLong("attachSent")); assertEquals(1, reconnect.getLong("completed"));
    }

    @Test public void failedNeutralAttachReportDoesNotClaimCompletedAndRestoresAUsableNeutralDevice() throws Exception {
        FixtureHandler handler = new FixtureHandler(); FixtureSlot slot = connectHid(handler);
        assertTrue(handler.gamepadHandler.reconnectNativeDevice()); handler.drain();
        advanceReconnect(600); handler.failNextHidState = true;
        handler.drain(); advanceReconnect(0); handler.drain();
        org.json.JSONObject report = handler.controllerDiagnostics.snapshot();
        assertEquals(1, report.getJSONObject("nativeReconnect").getLong("attachSent"));
        assertEquals(0, report.getJSONObject("nativeReconnect").getLong("completed"));
        assertEquals(1, report.getJSONObject("nativeReconnect").getLong("sendFailures"));
        assertEquals(1, report.getJSONObject("nativeReconnect").getLong("cancelled"));
        assertEquals(1, report.getJSONObject("nativeStateDelivery").getLong("sendFailures"));
        handler.packets.clear(); slot.state.buttons = 1;
        handler.gamepadHandler.sendGamepadState(slot); handler.drain();
        assertEquals(1, littleEndian(handler.packets.get(1).bytes).getShort(2));
    }

    private static void advanceReconnect(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    @Test public void legacyAbsentAdvertisementPrecedesHidRemovalAndSubscriptionsSurviveTheGap() throws Exception {
        FixtureHandler handler = new FixtureHandler(); FixtureSlot slot = connectHid(handler);
        assertTrue(handler.gamepadHandler.reconnectNativeDevice()); handler.drain();
        int legacyRemoval = -1, hidRemoval = -1;
        for (int index = 0; index < handler.packets.size(); index++) {
            Packet packet = handler.packets.get(index);
            if (packet.bytes[0] != 8) continue;
            if (packet.port == 7949) { legacyRemoval = index; assertEquals(0, littleEndian(packet.bytes).getInt(1)); }
            if (packet.port == 7950) { hidRemoval = index; assertEquals(0, packet.bytes[1]); }
        }
        assertTrue(legacyRemoval >= 0 && hidRemoval > legacyRemoval);
        handler.packets.clear();
        queueLegacyDiscovery(handler); queueLegacyPoll(handler);
        slot.state.buttons = 1; handler.gamepadHandler.sendGamepadState(slot); handler.drain();
        assertBlackoutPackets(handler.packets);
        assertTrue(handler.packets.stream().anyMatch(packet -> packet.port == 7949 && packet.bytes[0] == 8));
        assertEquals(1, handler.controllerDiagnostics.snapshot().getJSONObject("counts").getLong("notifySubscriptions"));
        handler.packets.clear(); advanceReconnect(600); handler.drain(); advanceReconnect(0);
        assertAttachPackets(handler.packets);
        handler.packets.clear(); slot.state.buttons = 1; handler.gamepadHandler.sendGamepadState(slot); handler.drain();
        assertEquals(1, littleEndian(handler.packets.get(0).bytes).getShort(6));
        assertEquals(1, littleEndian(handler.packets.get(1).bytes).getShort(2));
    }

    @Test public void failedLegacyRemovalStillAttemptsHidRemovalAndRestoresBothNeutralDevices() throws Exception {
        FixtureHandler handler = new FixtureHandler(); FixtureSlot slot = connectHid(handler);
        assertTrue(handler.gamepadHandler.reconnectNativeDevice()); handler.failNextLegacyDiscovery = true;
        handler.drain(); advanceReconnect(0); handler.drain();
        assertTrue(handler.packets.stream().anyMatch(packet -> packet.port == 7950 && packet.bytes[0] == 8 && packet.bytes[1] == 0));
        assertTrue(handler.packets.stream().anyMatch(packet -> packet.port == 7949 && packet.bytes[0] == 8 && littleEndian(packet.bytes).getInt(1) == 1));
        for (Packet packet : handler.packets) if (packet.bytes[0] == 9) assertNeutralControls(packet);
        org.json.JSONObject reconnect = handler.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect");
        assertEquals(0, reconnect.getLong("detachSent")); assertEquals(1, reconnect.getLong("sendFailures"));
        assertEquals(1, reconnect.getLong("cancelled"));
        handler.packets.clear(); advanceReconnect(3000); handler.drain(); assertTrue(handler.packets.isEmpty());
        slot.state.buttons = 1; handler.gamepadHandler.sendGamepadState(slot); handler.drain();
        assertEquals(1, littleEndian(handler.packets.get(0).bytes).getShort(6));
    }

    @Test public void failedLegacyNeutralAttachDoesNotClaimCompletionAndRestoresNeutralInput() throws Exception {
        FixtureHandler handler = new FixtureHandler(); FixtureSlot slot = connectHid(handler);
        assertTrue(handler.gamepadHandler.reconnectNativeDevice()); handler.drain(); handler.packets.clear();
        advanceReconnect(600); handler.failNextLegacyState = true; handler.drain(); advanceReconnect(0); handler.drain();
        org.json.JSONObject reconnect = handler.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect");
        assertEquals(0, reconnect.getLong("attachSent")); assertEquals(0, reconnect.getLong("completed"));
        assertEquals(1, reconnect.getLong("sendFailures")); assertEquals(1, reconnect.getLong("cancelled"));
        assertTrue(handler.packets.stream().anyMatch(packet -> packet.port == 7949 && packet.bytes[0] == 8 && littleEndian(packet.bytes).getInt(1) == 1));
        assertTrue(handler.packets.stream().anyMatch(packet -> packet.port == 7950 && packet.bytes[0] == 8 && packet.bytes[1] == 1));
        for (Packet packet : handler.packets) if (packet.bytes[0] == 9) assertNeutral(packet);
        handler.packets.clear(); slot.state.buttons = 1; handler.gamepadHandler.sendGamepadState(slot); handler.drain();
        assertEquals(1, littleEndian(handler.packets.get(0).bytes).getShort(6));
    }

    @Test public void modalGateAndMissingLegacySubscriptionCannotStartThePairedReconnect() throws Exception {
        FixtureHandler gated = new FixtureHandler(); connectHid(gated);
        gated.gamepadHandler.setInputEnabled(false); gated.drain(); gated.packets.clear();
        assertFalse(gated.gamepadHandler.reconnectNativeDevice()); gated.drain(); assertTrue(gated.packets.isEmpty());
        FixtureHandler noLegacy = new FixtureHandler(); connectHid(noLegacy);
        Field clients = GamepadHandler.class.getDeclaredField("legacyGamepadClients"); clients.setAccessible(true);
        ((List<?>)clients.get(noLegacy.gamepadHandler)).clear();
        assertFalse(noLegacy.gamepadHandler.reconnectNativeDevice()); noLegacy.drain(); assertTrue(noLegacy.packets.isEmpty());
    }

    @Test public void failedLegacyCancellationRestoreIsReportedAndPeriodicDiscoveryCanRecoverIt() throws Exception {
        FixtureHandler handler = new FixtureHandler(); connectHid(handler);
        assertTrue(handler.gamepadHandler.reconnectNativeDevice()); handler.drain(); handler.packets.clear();
        handler.failNextLegacyDiscovery = true; handler.gamepadHandler.setInputEnabled(false); handler.drain();
        org.json.JSONObject reconnect = handler.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect");
        assertEquals(1, reconnect.getLong("sendFailures")); assertEquals(1, reconnect.getLong("cancelled"));
        assertEquals(0, reconnect.getLong("completed"));
        for (Packet packet : handler.packets) if (packet.bytes[0] == 9) assertNeutral(packet);
        handler.packets.clear(); queueLegacyDiscovery(handler); handler.drain();
        assertEquals(1, littleEndian(handler.packets.get(0).bytes).getInt(1)); assertNeutral(handler.packets.get(1));
        int sent = handler.packets.size(); advanceReconnect(3000); handler.drain(); assertEquals(sent, handler.packets.size());
    }

    @Test public void legacyUnsubscribeDuringBlackoutCancelsAttachWithoutInventingASubscription() throws Exception {
        FixtureHandler handler = new FixtureHandler(); connectHid(handler);
        assertTrue(handler.gamepadHandler.reconnectNativeDevice()); handler.drain(); handler.packets.clear();
        handler.gamepadHandler.handleReleaseGamepadRequest(7949); advanceReconnect(0); handler.drain();
        assertFalse(handler.packets.stream().anyMatch(packet -> packet.port == 7949));
        int sent = handler.packets.size(); advanceReconnect(3000); handler.drain(); assertEquals(sent, handler.packets.size());
        assertEquals(1, handler.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect").getLong("cancelled"));
        assertEquals(0, handler.controllerDiagnostics.snapshot().getJSONObject("nativeReconnect").getLong("attachSent"));
    }

    private static void queueLegacyDiscovery(FixtureHandler handler) {
        handler.receiveData.clear(); handler.receiveData.put((byte)1).put((byte)1).putInt(123).flip();
        handler.gamepadHandler.handleLegacyGetGamepadRequest(7949);
    }

    private static void queueLegacyPoll(FixtureHandler handler) {
        handler.receiveData.clear(); handler.receiveData.putInt(1).flip();
        handler.gamepadHandler.handleGetGamepadStateRequest(7949);
    }

    private static void assertAttachPackets(List<Packet> packets) {
        assertEquals(4, packets.size());
        assertEquals(7949, packets.get(0).port); assertEquals(8, packets.get(0).bytes[0]);
        assertEquals(1, littleEndian(packets.get(0).bytes).getInt(1));
        assertEquals(7949, packets.get(1).port); assertNeutral(packets.get(1));
        assertEquals(7950, packets.get(2).port); assertEquals(8, packets.get(2).bytes[0]);
        assertEquals(1, packets.get(2).bytes[1]);
        assertEquals(7950, packets.get(3).port); assertNeutral(packets.get(3));
    }

    private static FixtureSlot connectHid(FixtureHandler handler) throws Exception {
        FixtureSlot slot = connect(handler, false);
        handler.gamepadHandler.handleModernGetGamepadRequest(7950); handler.drain(); handler.packets.clear();
        return slot;
    }

    private static void assertBlackoutPackets(List<Packet> packets) {
        for (Packet packet : packets) {
            if (packet.bytes[0] == 9) {
                assertNeutralControls(packet);
                if (packet.port == 7949) { assertEquals(0, packet.bytes[1]); assertEquals(0, littleEndian(packet.bytes).getInt(2)); }
            }
            else if (packet.bytes[0] == 8) {
                if (packet.port == 7950) assertEquals(0, packet.bytes[1]);
                if (packet.port == 7949) assertEquals(0, littleEndian(packet.bytes).getInt(1));
            }
        }
    }

    private static void assertNeutral(Packet packet) {
        assertNeutralControls(packet);
        if (packet.port == 7949) {
            assertEquals(1, packet.bytes[1]); assertEquals(1, littleEndian(packet.bytes).getInt(2));
        }
    }

    private static void assertNeutralControls(Packet packet) {
        assertEquals(9, packet.bytes[0]);
        int start = packet.port == 7950 ? 2 : 6;
        int hat = packet.port == 7950 ? 4 : 8;
        for (int index = start; index < 17; index++) assertEquals("offset " + index,
                index == hat ? (byte)-1 : 0, packet.bytes[index]);
    }

    private static String repeat(String value, int count) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < count; index++) output.append(value);
        return output.toString();
    }

    private static ByteBuffer littleEndian(byte[] packet) {
        return ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
    }

    @SuppressWarnings("unchecked")
    private static FixtureSlot connect(FixtureHandler handler, boolean modernClient) throws Exception {
        GamepadHandler gamepads = handler.gamepadHandler;
        FixtureSlot slot = new FixtureSlot();
        Field slots = GamepadHandler.class.getDeclaredField("gamepadSlots"); slots.setAccessible(true);
        ((GamepadSlot[])slots.get(gamepads))[0] = slot;
        Field legacy = GamepadHandler.class.getDeclaredField("legacyGamepad"); legacy.setAccessible(true);
        legacy.set(gamepads, slot);
        Field clients = GamepadHandler.class.getDeclaredField("legacyGamepadClients"); clients.setAccessible(true);
        ((List<Integer>)clients.get(gamepads)).add(7949);
        if (modernClient) {
            Field modern = GamepadHandler.class.getDeclaredField("gamepadClients"); modern.setAccessible(true);
            ((List<Integer>)modern.get(gamepads)).add(32100);
        }
        return slot;
    }

    private static final class FixtureSlot implements GamepadSlot {
        final GamepadState state = new GamepadState();
        @Override public String getName() { return "Thor fixture"; }
        @Override public short getVendorId() { return 0x045e; }
        @Override public short getProductId() { return 0x028e; }
        @Override public GamepadState getGamepadState() { return state; }
        @Override public GamepadVibration getGamepadVibration() { return null; }
    }

    private static final class Packet {
        final int port;
        final byte[] bytes;
        Packet(int port, byte[] bytes) { this.port = port; this.bytes = bytes; }
    }

    private static final class FixtureHandler extends WinHandler {
        final ArrayDeque<Runnable> actions = new ArrayDeque<>();
        final ArrayList<Packet> packets = new ArrayList<>();
        final boolean interceptActions;
        boolean failNextDiscovery;
        boolean failNextHidState;
        boolean failNextLegacyDiscovery;
        boolean failNextLegacyState;
        FixtureHandler() { this(true); }
        FixtureHandler(boolean interceptActions) { super(null); this.interceptActions = interceptActions; initReceived = true; }
        @Override String getWineIdentifier() { return "wine-9.2-custom"; }
        @Override public boolean isInputReady() { return true; }
        @Override protected void addAction(Runnable action) {
            if (interceptActions) actions.add(action); else super.addAction(action);
        }
        @Override protected void addControllerAction(Runnable action) {
            if (interceptActions) actions.add(action); else super.addControllerAction(action);
        }
        @Override protected boolean sendPacket(int port, byte[] packet) {
            if (failNextDiscovery && port == 7950 && packet[0] == 8) { failNextDiscovery = false; return false; }
            if (failNextHidState && port == 7950 && packet[0] == 9) { failNextHidState = false; return false; }
            if (failNextLegacyDiscovery && port == 7949 && packet[0] == 8) { failNextLegacyDiscovery = false; return false; }
            if (failNextLegacyState && port == 7949 && packet[0] == 9) { failNextLegacyState = false; return false; }
            packets.add(new Packet(port, packet.clone())); return true;
        }
        @Override protected boolean sendPacket(int port) {
            packets.add(new Packet(port, Arrays.copyOf(sendData.array(), sendData.position()))); return true;
        }
        void drain() { while (!actions.isEmpty()) actions.remove().run(); }
    }
}
