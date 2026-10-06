package com.winlator.winhandler;

import android.app.Application;

import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.GamepadSlot;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.Assert.*;

/** Observe existing producer traffic; these tests never load a Windows XInput library. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class GamepadExposureTest {
    @Test public void decodedLegacyRequestsRetainPidRolesAndStopDoesNotEraseEvidence() throws Exception {
        WinHandler handler = new WinHandler(null);
        discovery(handler, 1, 1, 240);
        handler.gamepadHandler.handleLegacyGetGamepadRequest(7949);
        discovery(handler, 0, 0, 240);
        handler.gamepadHandler.handleLegacyGetGamepadRequest(7948);
        discovery(handler, 1, 1, 244);
        handler.gamepadHandler.handleLegacyGetGamepadRequest(7949);
        handler.stop();
        JSONObject exposure = handler.controllerDiagnostics.snapshot().getJSONObject("controllerExposure");
        assertEquals(2, exposure.getInt("trackedLegacyClientCount"));
        assertEquals(2, exposure.getInt("trackedXinputClientCount"));
        assertEquals(1, exposure.getInt("trackedDinputClientCount"));
        assertEquals(240, exposure.getJSONArray("legacyClients").getJSONObject(0).getInt("windowsProcessId"));
        assertFalse(exposure.toString().contains("sessionToken"));
    }

    @Test public void malformedDiscoveryCannotPopulateLegacyProcessOwnershipOrQueueAReply() throws Exception {
        WinHandler handler = new WinHandler(null);
        for (int[] invalid : new int[][]{{2, 1, 240}, {1, 2, 240}, {1, 1, 0}, {1, 1, -1},
                {1, 1, Integer.MIN_VALUE}}) {
            discovery(handler, invalid[0], invalid[1], invalid[2]);
            try {
                handler.gamepadHandler.handleLegacyGetGamepadRequest(7949);
                fail("Malformed discovery was accepted");
            } catch (IllegalArgumentException expected) { }
        }
        handler.receiveData.clear(); handler.receiveData.limit(5);
        // Preserve the recognized-request dispatch contract: consume an incomplete request as a no-op.
        handler.gamepadHandler.handleLegacyGetGamepadRequest(7949);
        Field ownership = GamepadHandler.class.getDeclaredField("legacyXInputProcesses");
        ownership.setAccessible(true);
        assertTrue(((HashSet<?>)ownership.get(handler.gamepadHandler)).isEmpty());
        assertEquals(0, handler.drainPendingActions());
        JSONObject exposure = handler.controllerDiagnostics.snapshot().getJSONObject("controllerExposure");
        assertEquals(0, exposure.getInt("trackedLegacyClientCount"));
        assertEquals(6, exposure.getLong("invalidLegacyDiscoveries"));
        handler.stop();
    }

    @Test public void actualAssignmentCountsRepeatedPhysicalSlotsAsOneAndroidDevice() throws Exception {
        WinHandler handler = new WinHandler(null);
        ExternalController controller = new ExternalController();
        Field deviceId = ExternalController.class.getDeclaredField("deviceId");
        deviceId.setAccessible(true); deviceId.setInt(controller, 92);
        Field slotsField = GamepadHandler.class.getDeclaredField("gamepadSlots"); slotsField.setAccessible(true);
        GamepadSlot[] slots = (GamepadSlot[])slotsField.get(handler.gamepadHandler);
        slots[0] = controller; slots[2] = controller;
        Field connectedField = GamepadHandler.class.getDeclaredField("connectedControllers"); connectedField.setAccessible(true);
        @SuppressWarnings("unchecked") ArrayList<ExternalController> connected =
                (ArrayList<ExternalController>)connectedField.get(handler.gamepadHandler);
        connected.add(controller);
        handler.gamepadHandler.recordControllerSlotTopology();
        JSONObject exposure = handler.controllerDiagnostics.snapshot().getJSONObject("controllerExposure");
        assertEquals(1, exposure.getInt("connectedPhysicalControllers"));
        assertEquals(2, exposure.getInt("assignedSlots"));
        assertEquals(2, exposure.getInt("assignedPhysicalSlots"));
        assertEquals(1, exposure.getInt("distinctAssignedAndroidDevices"));
        assertEquals(0, exposure.getInt("assignedVirtualSlots"));
        handler.stop();
    }

    private static void discovery(WinHandler handler, int xinput, int notify, int pid) {
        handler.receiveData.clear();
        handler.receiveData.put((byte)xinput).put((byte)notify).putInt(pid);
        handler.receiveData.flip();
    }
}
