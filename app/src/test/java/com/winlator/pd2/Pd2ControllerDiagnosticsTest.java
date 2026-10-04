package com.winlator.pd2;

import android.app.Activity;
import android.app.Application;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;

import com.winlator.inputcontrols.ExternalController;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, shadows = Pd2ControllerDiagnosticsTest.InputDevices.class)
public final class Pd2ControllerDiagnosticsTest {
    @Before public void clearDevices() { InputDevices.devices.clear(); }

    @Test public void pointerRoutesAttributeEmissionsAndRejectedCaptureToTheCurrentModeWithoutValues() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(null);
        diagnostics.recordPointerRoute("captured", "disabled");
        diagnostics.recordPointerRoute("captured", "zero");
        diagnostics.recordPointerRoute("captured", "move");
        diagnostics.recordPointerRoute("external", "button");
        diagnostics.recordPointerRoute("touch", "scroll");
        diagnostics.recordPointerRoute("private-device-name", "move");
        diagnostics.recordPointerRoute("touch", "private-key-value");
        diagnostics.recordPointerRoute("external", "disabled");
        diagnostics.setMode("menu_cursor", true);
        diagnostics.recordPointerRoute("captured", "move");
        diagnostics.recordPointerRoute("captured", "move");
        JSONObject report = diagnostics.snapshot();
        JSONObject nativeRoute = report.getJSONObject("inputByMode").getJSONObject("native").getJSONObject("pointerRouting");
        JSONObject captured = nativeRoute.getJSONObject("captured");
        for (String field : new String[]{"disabledEvents", "zeroEvents", "moveEvents"}) assertEquals(field, 1, captured.getLong(field));
        assertEquals(1, nativeRoute.getJSONObject("external").getLong("buttonEvents"));
        assertEquals(1, nativeRoute.getJSONObject("touch").getLong("scrollEvents"));
        assertFalse(nativeRoute.getJSONObject("external").has("disabledEvents"));
        assertTrue(nativeRoute.getLong("lastEmissionAt") > 0);
        assertEquals(2, report.getJSONObject("inputByMode").getJSONObject("menu_cursor")
                .getJSONObject("pointerRouting").getJSONObject("captured").getLong("moveEvents"));
        assertEquals(0, report.getJSONObject("inputByMode").getJSONObject("mouse_keyboard")
                .getJSONObject("pointerRouting").getLong("lastEmissionAt"));
        assertEquals(0, report.getJSONObject("pointerOutput").getLong("moveEvents"));
        assertFalse(report.toString().contains("private-device-name"));
        assertFalse(report.toString().contains("private-key-value"));
        captured.put("moveEvents", 999);
        assertEquals(1, diagnostics.snapshot().getJSONObject("inputByMode").getJSONObject("native")
                .getJSONObject("pointerRouting").getJSONObject("captured").getLong("moveEvents"));
        assertTrue(report.toString().getBytes(StandardCharsets.UTF_8).length < Pd2ControllerDiagnostics.MAX_REPORT_BYTES);
    }

    @Test public void reconnectAndStateDeliveryCountersSeparateRequestsFromSuccessfulPacketsAndFilterUnknownDetails() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(null);
        diagnostics.recordNativeReconnect(null); diagnostics.recordNativeReconnect("private-controller-id");
        for (String phase : new String[]{"requested", "detachSent", "attachSent", "completed", "cancelled", "unavailable", "sendFailure", "timedOut"})
            diagnostics.recordNativeReconnect(phase);
        diagnostics.recordNativeStateDelivery(7950, true, false);
        diagnostics.recordNativeStateDelivery(7950, true, true);
        diagnostics.recordNativeStateDelivery(7950, false, true);
        diagnostics.recordNativeStateDelivery(7949, true, true);
        diagnostics.recordNativeStateDelivery(7949, false, true);
        diagnostics.recordNativeStateDelivery(1234, true, false);
        org.json.JSONObject report = diagnostics.snapshot();
        org.json.JSONObject reconnect = report.getJSONObject("nativeReconnect");
        for (String field : new String[]{"requests", "detachSent", "attachSent", "completed", "cancelled", "unavailable", "sendFailures", "timedOut"})
            assertEquals(field, 1, reconnect.getLong(field));
        assertEquals("timedOut", reconnect.getString("lastPhase"));
        assertTrue(reconnect.getLong("lastPhaseAt") > 0);
        assertTrue(reconnect.getString("scope").contains("not acknowledged"));
        org.json.JSONObject states = report.getJSONObject("nativeStateDelivery");
        for (String field : new String[]{"hidNonNeutralSent", "hidNeutralSent", "xinputNonNeutralSent", "xinputNeutralSent", "sendFailures"})
            assertEquals(field, 1, states.getLong(field));
        assertTrue(states.getLong("lastNonNeutralSentAt") > 0);
        assertFalse(report.toString().contains("private-controller-id"));
        reconnect.put("requests", 900); states.put("hidNonNeutralSent", 800);
        assertEquals(1, diagnostics.snapshot().getJSONObject("nativeReconnect").getLong("requests"));
        assertEquals(1, diagnostics.snapshot().getJSONObject("nativeStateDelivery").getLong("hidNonNeutralSent"));
    }

    @Test public void nativeFocusRecoveryReportsOnlyBoundedRequestReasonsAndNeverClaimsWindowsAcceptance() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(null);
        diagnostics.recordNativeFocusRequest(null);
        diagnostics.recordNativeFocusRequest("private-window-title");
        assertEquals(0, diagnostics.snapshot().getJSONObject("nativeFocusRecovery").getLong("requests"));
        diagnostics.recordNativeFocusRequest("route");
        diagnostics.recordNativeFocusRequest("window");
        diagnostics.recordNativeFocusRequest("settle");
        JSONObject report = diagnostics.snapshot();
        JSONObject recovery = report.getJSONObject("nativeFocusRecovery");
        assertEquals(3, recovery.getLong("requests"));
        assertEquals("settle", recovery.getString("lastReason"));
        assertTrue(recovery.getLong("lastRequestAt") > 0);
        assertTrue(recovery.getString("scope").contains("not acknowledged"));
        assertFalse(report.toString().contains("private-window-title"));
        recovery.put("requests", 123);
        assertEquals(3, diagnostics.snapshot().getJSONObject("nativeFocusRecovery").getLong("requests"));
    }

    @Test public void transitionsRetainMenuOwnershipAndLatestGatesWithinTheReportBound() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(null);
        diagnostics.setInputGate(true, false, false, false);
        for (int i = 0; i < 80; i++) diagnostics.setMode(i % 2 == 0 ? "menu_cursor" : "native", true);
        diagnostics.setMode("menu_cursor", false);
        diagnostics.setInputGate(false, true, true, false);
        JSONObject report = diagnostics.snapshot();
        assertEquals("menu_cursor", report.getString("mode"));
        assertEquals(32, report.getJSONArray("recentTransitions").length());
        JSONObject latest = report.getJSONArray("recentTransitions").getJSONObject(31);
        assertEquals("gate", latest.getString("event"));
        assertEquals("menu_cursor", latest.getString("mode"));
        assertFalse(latest.getBoolean("windowFocus"));
        assertTrue(latest.getBoolean("paused"));
        assertTrue(latest.getBoolean("quickMenu"));
        assertFalse(latest.getBoolean("drawer"));
        assertTrue(latest.getLong("at") > 0);
        assertTrue(report.toString().getBytes(StandardCharsets.UTF_8).length < Pd2ControllerDiagnostics.MAX_REPORT_BYTES);
        latest.put("mode", "tampered");
        assertEquals("menu_cursor", diagnostics.snapshot().getJSONArray("recentTransitions").getJSONObject(31).getString("mode"));
    }

    @Test public void eventTimesDistinguishHandledInputAndSuccessfulBridgeReplies() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(null);
        diagnostics.recordMotion(false);
        diagnostics.recordKey(false);
        diagnostics.recordReply((byte)9, 7950, false);
        JSONObject before = diagnostics.snapshot().getJSONObject("lastEvents");
        assertEquals(0, before.getLong("handledMotionAt"));
        assertEquals(0, before.getLong("handledKeyAt"));
        assertEquals(0, before.getLong("hidStateReplyAt"));
        diagnostics.recordMotion(true);
        diagnostics.recordKey(true);
        diagnostics.recordReply((byte)9, 7950, true);
        diagnostics.recordReply((byte)9, 7949, true);
        JSONObject after = diagnostics.snapshot().getJSONObject("lastEvents");
        for (String name : new String[]{"handledMotionAt", "handledKeyAt", "hidStateReplyAt", "xinputStateReplyAt"})
            assertTrue(after.getLong(name) > 0);
        assertFalse(after.toString().contains("keyCode"));
        assertFalse(after.toString().contains("axisValue"));
    }

    @Test public void handledInputCountsAndTimesStayWithTheModeThatOwnedEachEvent() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(null);
        diagnostics.recordMotion(false);
        diagnostics.recordKey(false);
        diagnostics.recordMotion(true);
        diagnostics.recordMotion(true);
        diagnostics.recordKey(true);
        JSONObject nativeBefore = diagnostics.snapshot().getJSONObject("inputByMode").getJSONObject("native");
        diagnostics.setMode("menu_cursor", true);
        for (int index = 0; index < 3; index++) diagnostics.recordMotion(true);
        diagnostics.recordKey(false);
        diagnostics.setMode("mouse_keyboard", true);
        diagnostics.recordMotion(true);
        for (int index = 0; index < 4; index++) diagnostics.recordKey(true);
        JSONObject report = diagnostics.snapshot();
        JSONObject modes = report.getJSONObject("inputByMode");
        assertEquals(3, modes.length());
        assertEquals(6, report.getJSONObject("counts").getLong("handledMotionEvents"));
        assertEquals(5, report.getJSONObject("counts").getLong("handledKeyEvents"));
        assertEquals(1, report.getJSONObject("counts").getLong("motionEvents"));
        assertEquals(2, report.getJSONObject("counts").getLong("keyEvents"));
        JSONObject nativeMode = modes.getJSONObject("native");
        assertEquals(2, nativeMode.getLong("handledMotionEvents"));
        assertEquals(1, nativeMode.getLong("handledKeyEvents"));
        assertEquals(nativeBefore.getLong("lastHandledMotionAt"), nativeMode.getLong("lastHandledMotionAt"));
        assertEquals(nativeBefore.getLong("lastHandledKeyAt"), nativeMode.getLong("lastHandledKeyAt"));
        JSONObject menu = modes.getJSONObject("menu_cursor");
        assertEquals(3, menu.getLong("handledMotionEvents"));
        assertTrue(menu.getLong("lastHandledMotionAt") > 0);
        assertEquals(0, menu.getLong("handledKeyEvents"));
        assertEquals(0, menu.getLong("lastHandledKeyAt"));
        JSONObject keyboard = modes.getJSONObject("mouse_keyboard");
        assertEquals(1, keyboard.getLong("handledMotionEvents"));
        assertEquals(4, keyboard.getLong("handledKeyEvents"));
        assertEquals(report.getJSONObject("lastEvents").getLong("handledKeyAt"), keyboard.getLong("lastHandledKeyAt"));
        diagnostics.setMode("unknown-private-mode", true);
        diagnostics.recordKey(true);
        assertEquals(2, diagnostics.snapshot().getJSONObject("inputByMode").getJSONObject("native").getLong("handledKeyEvents"));
        assertFalse(diagnostics.snapshot().toString().contains("unknown-private-mode"));
    }

    @Test public void pointerOutputCountsCategoriesAndModeWithoutRecordingCodesOrArbitraryKinds() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(null);
        diagnostics.recordPointerOutput("move");
        diagnostics.recordPointerOutput(null);
        diagnostics.recordPointerOutput("button-private-key-code-123");
        diagnostics.setMode("menu_cursor", true);
        diagnostics.recordPointerOutput("move");
        diagnostics.recordPointerOutput("move");
        diagnostics.recordPointerOutput("button");
        diagnostics.recordPointerOutput("feedback");
        diagnostics.setMode("mouse_keyboard", true);
        diagnostics.recordPointerOutput("key");
        JSONObject report = diagnostics.snapshot();
        JSONObject output = report.getJSONObject("pointerOutput");
        assertEquals(3, output.getLong("moveEvents"));
        for (String name : new String[]{"buttonEvents", "keyEvents", "feedbackEvents"}) assertEquals(1, output.getLong(name));
        for (String name : new String[]{"lastMoveAt", "lastButtonAt", "lastKeyAt", "lastFeedbackAt"}) assertTrue(output.getLong(name) > 0);
        JSONObject modes = report.getJSONObject("inputByMode");
        assertEquals(1, modes.getJSONObject("native").getJSONObject("pointerOutput").getLong("moveEvents"));
        JSONObject menu = modes.getJSONObject("menu_cursor").getJSONObject("pointerOutput");
        assertEquals(2, menu.getLong("moveEvents"));
        assertEquals(1, menu.getLong("buttonEvents"));
        assertEquals(1, menu.getLong("feedbackEvents"));
        assertEquals(0, menu.getLong("keyEvents"));
        assertEquals(0, menu.getLong("lastKeyAt"));
        assertEquals(1, modes.getJSONObject("mouse_keyboard").getJSONObject("pointerOutput").getLong("keyEvents"));
        assertEquals(0, report.getJSONObject("counts").getLong("handledKeyEvents"));
        assertFalse(report.toString().contains("button-private-key-code-123"));
        output.put("moveEvents", 900);
        menu.put("moveEvents", 800);
        assertEquals(3, diagnostics.snapshot().getJSONObject("pointerOutput").getLong("moveEvents"));
        assertEquals(2, diagnostics.snapshot().getJSONObject("inputByMode").getJSONObject("menu_cursor")
                .getJSONObject("pointerOutput").getLong("moveEvents"));
    }

    @Test public void pointerContextRetainsLastSixteenObservationsWithCaptureModeAndIndependentCopies() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(null);
        JSONObject window = new JSONObject().put("id", 77).put("x", -3).put("y", 5)
                .put("width", 800).put("height", 600).put("class", "Diablo II");
        JSONObject state = new JSONObject().put("screenWidth", 1280).put("screenHeight", 720)
                .put("focusWindow", window).put("pointWindow", window).put("grabWindow", JSONObject.NULL)
                .put("menuWindow", window).put("relative", false).put("gameCursorVisible", true).put("forceRoot", false);
        for (int index = 0; index < 24; index++) {
            diagnostics.setMode(index % 2 == 0 ? "native" : "menu_cursor", true);
            state.put("pointerX", index).put("pointerY", -index).put("rootCursorVisible", index % 2 == 0);
            diagnostics.recordPointerContext(state);
        }
        window.put("id", 999).put("class", "tampered-input");
        state.put("pointerX", 999);
        JSONObject report = diagnostics.snapshot();
        assertEquals(16, report.getJSONArray("recentPointerContexts").length());
        JSONObject first = report.getJSONArray("recentPointerContexts").getJSONObject(0);
        JSONObject last = report.getJSONArray("recentPointerContexts").getJSONObject(15);
        assertEquals(8, first.getInt("pointerX"));
        assertEquals("native", first.getString("mode"));
        assertTrue(first.getBoolean("rootCursorVisible"));
        assertEquals(23, last.getInt("pointerX"));
        assertEquals(-23, last.getInt("pointerY"));
        assertEquals("menu_cursor", last.getString("mode"));
        assertFalse(last.getBoolean("rootCursorVisible"));
        assertTrue(last.getLong("at") > 0);
        assertEquals(77, last.getJSONObject("focusWindow").getInt("id"));
        assertEquals("Diablo II", last.getJSONObject("menuWindow").getString("class"));
        assertEquals(-3, last.getJSONObject("menuWindow").getInt("x"));
        assertEquals(5, last.getJSONObject("menuWindow").getInt("y"));
        assertTrue(last.isNull("grabWindow"));
        last.put("pointerX", 1234);
        last.getJSONObject("focusWindow").put("id", 1234);
        JSONObject unchanged = diagnostics.snapshot().getJSONArray("recentPointerContexts").getJSONObject(15);
        assertEquals(23, unchanged.getInt("pointerX"));
        assertEquals(77, unchanged.getJSONObject("focusWindow").getInt("id"));
    }

    @Test public void nativePointerContextSurvivesMenuNavigationBeforeSupportExport() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(RuntimeEnvironment.getApplication());
        diagnostics.setMode("native", true);
        diagnostics.recordPointerContext(new JSONObject().put("pointerX", 123).put("cursorOverlayVisible", false)
                .put("title", "private-title"));
        diagnostics.setMode("menu_cursor", true);
        for (int i = 0; i < 20; i++) diagnostics.recordPointerContext(new JSONObject().put("pointerX", i));
        JSONObject report = diagnostics.snapshot();
        for (int i = 0; i < 16; i++) assertEquals("menu_cursor",
                report.getJSONArray("recentPointerContexts").getJSONObject(i).getString("mode"));
        JSONObject latest = report.getJSONObject("inputByMode").getJSONObject("native")
                .getJSONObject("latestPointerContext");
        assertEquals(123, latest.getInt("pointerX"));
        assertFalse(latest.getBoolean("cursorOverlayVisible"));
        assertFalse(latest.has("title"));
        latest.put("pointerX", 999);
        assertEquals(123, diagnostics.snapshot().getJSONObject("inputByMode").getJSONObject("native")
                .getJSONObject("latestPointerContext").getInt("pointerX"));
    }

    @Test public void pointerContextFiltersPrivateFieldsAndInvalidTypesWithoutCopyingOversizedData() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(null);
        StringBuilder privateText = new StringBuilder();
        for (int index = 0; index < 100000; index++) privateText.append('p');
        JSONObject window = new JSONObject().put("id", 0xffffffffL).put("x", -5).put("y", 1.5)
                .put("width", 800).put("height", "600").put("class", privateText.toString())
                .put("title", "character-name-private").put("path", "/private/save/path");
        JSONObject state = new JSONObject().put("screenWidth", 1280).put("screenHeight", -1)
                .put("pointerX", 1000000).put("pointerY", -7).put("focusWindow", window)
                .put("pointWindow", new JSONObject().put("class", "/private/window/path"))
                .put("relative", "false").put("forceRoot", true).put("rootCursorVisible", "false")
                .put("title", "character-name-private").put("keyCode", 123).put("axisValue", 0.5)
                .put("at", 1).put("mode", "private-fake-mode").put("hugeUnknownField", privateText.toString());
        diagnostics.recordPointerContext(state);
        diagnostics.recordPointerContext(null);
        diagnostics.recordPointerContext(new JSONObject().put("title", "private-only"));
        JSONObject report = diagnostics.snapshot();
        assertEquals(1, report.getJSONArray("recentPointerContexts").length());
        JSONObject clean = report.getJSONArray("recentPointerContexts").getJSONObject(0);
        assertEquals("native", clean.getString("mode"));
        assertTrue(clean.getLong("at") > 1);
        assertEquals(1280, clean.getInt("screenWidth"));
        assertEquals(-7, clean.getInt("pointerY"));
        assertTrue(clean.getBoolean("forceRoot"));
        for (String name : new String[]{"screenHeight", "pointerX", "relative", "rootCursorVisible", "pointWindow", "title", "keyCode", "axisValue", "hugeUnknownField"})
            assertFalse(name, clean.has(name));
        JSONObject cleanWindow = clean.getJSONObject("focusWindow");
        assertEquals(0xffffffffL, cleanWindow.getLong("id"));
        assertEquals(-5, cleanWindow.getInt("x"));
        assertEquals(800, cleanWindow.getInt("width"));
        assertEquals(3, cleanWindow.length());
        for (String name : new String[]{"class", "title", "path", "height", "y"}) assertFalse(name, cleanWindow.has(name));
        assertFalse(report.toString().contains("character-name-private"));
        assertFalse(report.toString().contains("/private/"));
        assertFalse(report.toString().contains("private-fake-mode"));
    }

    @Test public void allBoundedHistoriesAndMaximumDeviceInventoryFitTheSixtyFourKiBReport() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(null);
        StringBuilder name = new StringBuilder();
        for (int index = 0; index < 96; index++) name.append('\u0001');
        for (int index = 1; index <= 40; index++) InputDevices.devices.put(index,
                device(index, name.toString(), InputDevice.SOURCE_JOYSTICK, InputDevice.SOURCE_JOYSTICK));
        StringBuilder windowClass = new StringBuilder();
        for (int index = 0; index < 96; index++) windowClass.append('A');
        JSONObject window = new JSONObject().put("id", 0xffffffffL).put("x", -65536).put("y", 65536)
                .put("width", 65536).put("height", 65536).put("class", windowClass.toString());
        JSONObject state = new JSONObject().put("screenWidth", 65536).put("screenHeight", 65536)
                .put("pointerX", -65536).put("pointerY", 65536).put("relative", true)
                .put("gameCursorVisible", true).put("forceRoot", true)
                .put("focusWindow", window).put("pointWindow", window).put("grabWindow", window).put("menuWindow", window);
        diagnostics.setLaunchId(new String(new char[128]).replace('\0', 'L'));
        diagnostics.setSelectedDevice(name.toString());
        for (int index = 0; index < 80; index++) {
            diagnostics.setMode(index % 2 == 0 ? "menu_cursor" : "mouse_keyboard", true);
            diagnostics.recordMotion(true);
            diagnostics.recordKey(true);
            diagnostics.recordPointerOutput("move");
            diagnostics.recordPointerOutput("button");
            diagnostics.recordPointerOutput("key");
            diagnostics.recordPointerOutput("feedback");
            diagnostics.recordPointerContext(state);
        }
        JSONObject report = diagnostics.snapshot();
        assertEquals(32, report.getJSONArray("devices").length());
        assertEquals(32, report.getJSONArray("recentTransitions").length());
        assertEquals(16, report.getJSONArray("recentPointerContexts").length());
        assertTrue(report.toString().getBytes(StandardCharsets.UTF_8).length <= Pd2ControllerDiagnostics.MAX_REPORT_BYTES);
    }

    @Test public void unattachedActivityCanConstructSnapshotAndSaveBeforeBaseContextExists() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(new Activity());
        diagnostics.setLaunchId("not-yet-attached");
        assertEquals("native", diagnostics.snapshot().getString("mode"));
        diagnostics.save();
        diagnostics.setMode("mouse_keyboard", false);
        assertEquals("mouse_keyboard", diagnostics.snapshot().getString("mode"));
    }

    @Test public void uinputGamepadCapabilitiesAreAcceptedWhileFingerprintAndVirtualDevicesRemainExcluded() {
        InputDevice pad = device(1, "uinput-controller", InputDevice.SOURCE_JOYSTICK, InputDevice.SOURCE_JOYSTICK);
        assertTrue(ExternalController.isGameController(pad));
        assertFalse(ExternalController.isGameController(device(2, "uinput-fpc", InputDevice.SOURCE_JOYSTICK, InputDevice.SOURCE_JOYSTICK)));
        assertFalse(ExternalController.isGameController(device(3, "goodix_fp", InputDevice.SOURCE_JOYSTICK, InputDevice.SOURCE_JOYSTICK)));
        assertFalse(ExternalController.isGameController(device(-1, "Virtual controller", InputDevice.SOURCE_JOYSTICK, InputDevice.SOURCE_JOYSTICK)));
        assertFalse(ExternalController.isGameController(device(4, "Composite touchpad", InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_MOUSE)));
        assertTrue(ExternalController.acceptsController("uinput-buttons", false, InputDevice.SOURCE_GAMEPAD, true, false));
        assertFalse(ExternalController.acceptsController("Fake gamepad", false, InputDevice.SOURCE_GAMEPAD, false, false));
        assertFalse(ExternalController.acceptsController("Keyboard", false, InputDevice.SOURCE_KEYBOARD, true, false));
    }

    @Test public void inventoryIsBoundedAndReportsCapabilitiesWithoutDeviceDescriptors() throws Exception {
        StringBuilder hugeName = new StringBuilder();
        for (int index = 0; index < 500; index++) hugeName.append('\uD83D').append('\uDE80');
        for (int index = 1; index <= 40; index++) {
            InputDevice device = device(index, hugeName.toString(), InputDevice.SOURCE_JOYSTICK, InputDevice.SOURCE_JOYSTICK);
            InputDevices.devices.put(index, device);
        }
        JSONObject report = new Pd2ControllerDiagnostics(null).snapshot();
        assertEquals(40, report.getInt("totalDevices"));
        assertTrue(report.getBoolean("devicesTruncated"));
        assertEquals(32, report.getJSONArray("devices").length());
        JSONObject first = report.getJSONArray("devices").getJSONObject(0);
        assertTrue(first.getBoolean("accepted"));
        assertTrue(first.getBoolean("controllerAxisX"));
        assertFalse(first.getBoolean("controllerAxisRX"));
        assertTrue(first.getString("name").length() <= 96);
        String text = report.toString();
        assertFalse(text.contains("descriptor-private-fixture"));
        assertFalse(text.contains("buttonsPressed"));
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= Pd2ControllerDiagnostics.MAX_REPORT_BYTES);
    }

    @Test public void triggerAxisInventoryReportsEachAliasWithoutPressedValues() throws Exception {
        InputDevice device = device(1, "Mixed triggers", InputDevice.SOURCE_JOYSTICK, InputDevice.SOURCE_JOYSTICK);
        Method range = InputDevice.class.getDeclaredMethod("addMotionRange", int.class, int.class,
                float.class, float.class, float.class, float.class, float.class);
        range.setAccessible(true);
        range.invoke(device, MotionEvent.AXIS_LTRIGGER, InputDevice.SOURCE_JOYSTICK, 0f, 1f, 0f, 0f, 0f);
        range.invoke(device, MotionEvent.AXIS_GAS, InputDevice.SOURCE_JOYSTICK, 0f, 1f, 0f, 0f, 0f);
        // An axis owned only by the mouse half of a composite device is excluded.
        range.invoke(device, MotionEvent.AXIS_RTRIGGER, InputDevice.SOURCE_MOUSE, 0f, 1f, 0f, 0f, 0f);
        InputDevices.devices.put(1, device);
        JSONObject first = new Pd2ControllerDiagnostics(null).snapshot().getJSONArray("devices").getJSONObject(0);
        assertTrue(first.getBoolean("controllerAxisLTRIGGER"));
        assertFalse(first.getBoolean("controllerAxisRTRIGGER"));
        assertFalse(first.getBoolean("controllerAxisBRAKE"));
        assertTrue(first.getBoolean("controllerAxisGAS"));
        assertFalse(first.has("triggerL"));
        assertFalse(first.has("triggerR"));
    }

    @Test public void countersDistinguishInputOwnershipTransportAndForwardingWithoutRecordingValues() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(null);
        diagnostics.setMode("mouse_keyboard", false);
        diagnostics.setNativeInputEnabled(false);
        diagnostics.setSocketReady(true);
        diagnostics.recordInit();
        diagnostics.recordRequest((byte)8, 7948);
        diagnostics.recordRequest((byte)8, 7949);
        diagnostics.recordRequest((byte)9, 7949);
        diagnostics.recordLegacyDiscovery(true, true);
        diagnostics.recordReply((byte)8, 7949, true);
        diagnostics.recordReply((byte)9, 7949, false);
        diagnostics.recordMotion(false);
        diagnostics.recordMotion(true);
        diagnostics.recordKey(false);
        diagnostics.recordKey(true);
        JSONObject report = diagnostics.snapshot();
        assertEquals("mouse_keyboard", report.getString("mode"));
        assertFalse(report.getBoolean("inputAvailable"));
        assertFalse(report.getBoolean("nativeInputEnabled"));
        assertTrue(report.getBoolean("socketReady"));
        assertTrue(report.getBoolean("winHandlerInitReceived"));
        JSONObject counts = report.getJSONObject("counts");
        for (String name : new String[]{"initMessages", "dinputRequests7948", "xinputRequests7949", "statePolls",
                "legacyXInputDiscovery", "notifySubscriptions", "deviceReplies", "replyFailures", "motionEvents",
                "handledMotionEvents", "keyEvents", "handledKeyEvents"}) assertEquals(name, 1, counts.getLong(name));
        assertEquals(0, counts.getLong("stateReplies"));
        assertFalse(report.toString().contains("keyCode"));
        assertFalse(report.toString().contains("axisValue"));
    }

    @Test public void hidCountersDistinguishDiscoveryAndDeliveredStatesFromLegacyTraffic() throws Exception {
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(null);
        diagnostics.recordRequest((byte)8, 7950);
        diagnostics.recordRequest((byte)8, 7949);
        diagnostics.recordReply((byte)8, 7950, true);
        diagnostics.recordReply((byte)9, 7950, true);
        diagnostics.recordReply((byte)9, 7949, true);
        diagnostics.recordReply((byte)9, 7950, false);
        JSONObject report = diagnostics.snapshot();
        assertEquals("legacy_xinput_7949_and_hid_7950", report.getString("nativeBridge"));
        assertEquals("java-native-reconnect-v3", report.getString("bridgeRevision"));
        JSONObject counts = report.getJSONObject("counts");
        assertEquals(1, counts.getLong("hidDiscoveryRequests7950"));
        assertEquals(1, counts.getLong("hidDeviceReplies7950"));
        assertEquals(1, counts.getLong("hidStateReplies7950"));
        assertEquals(1, counts.getLong("xinputRequests7949"));
        assertEquals(1, counts.getLong("deviceReplies"));
        assertEquals(2, counts.getLong("stateReplies"));
        assertEquals(1, counts.getLong("replyFailures"));
        assertEquals(0, counts.getLong("otherGamepadRequests"));
    }

    @Test public void persistenceReplacesOnlyItsBoundedReportAndLeavesNoTemporaryFile() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(context);
        File target = Pd2ControllerDiagnostics.getFile(context);
        assertTrue(target.getParentFile().mkdirs() || target.getParentFile().isDirectory());
        File unrelated = new File(target.getParentFile(), "unrelated.txt");
        Files.write(unrelated.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        writeLaunch(target, "current-launch");
        diagnostics.setLaunchId("current-launch");
        diagnostics.recordRequest((byte)8, 7949);
        diagnostics.save();
        assertTrue(target.length() > 0 && target.length() <= Pd2ControllerDiagnostics.MAX_REPORT_BYTES);
        JSONObject first = new JSONObject(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, first.getJSONObject("counts").getLong("xinputRequests7949"));
        diagnostics.recordRequest((byte)8, 7949);
        diagnostics.save();
        JSONObject second = new JSONObject(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
        assertEquals(2, second.getJSONObject("counts").getLong("xinputRequests7949"));
        assertEquals(first.getString("sessionId"), second.getString("sessionId"));
        assertEquals("current-launch", second.getString("launchId"));
        assertEquals("keep", new String(Files.readAllBytes(unrelated.toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(target.getParentFile(), "controller.json.tmp").exists());
    }

    @Test public void lateOldSessionCannotReplaceANewerLaunchReport() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        File target = Pd2ControllerDiagnostics.getFile(context);
        assertTrue(target.getParentFile().mkdirs() || target.getParentFile().isDirectory());
        Pd2ControllerDiagnostics old = new Pd2ControllerDiagnostics(context);
        old.setLaunchId("old-launch");
        writeLaunch(target, "old-launch");
        old.save();
        assertEquals("old-launch", readReport(target).getString("launchId"));
        Pd2ControllerDiagnostics current = new Pd2ControllerDiagnostics(context);
        current.setLaunchId("new-launch");
        writeLaunch(target, "new-launch");
        current.recordRequest((byte)8, 7949);
        current.save();
        byte[] expected = Files.readAllBytes(target.toPath());
        old.recordRequest((byte)8, 7949);
        old.save();
        assertArrayEquals(expected, Files.readAllBytes(target.toPath()));
        assertEquals("new-launch", readReport(target).getString("launchId"));
    }

    @Test public void absentMalformedOrOversizedLaunchCannotPublishAControllerReport() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        File target = Pd2ControllerDiagnostics.getFile(context);
        assertTrue(target.getParentFile().mkdirs() || target.getParentFile().isDirectory());
        File launch = new File(target.getParentFile(), "launch.json");
        Files.deleteIfExists(target.toPath());
        Files.deleteIfExists(launch.toPath());
        Pd2ControllerDiagnostics diagnostics = new Pd2ControllerDiagnostics(context);
        diagnostics.setLaunchId("expected");
        diagnostics.save();
        assertFalse(target.exists());
        Files.write(launch.toPath(), "malformed".getBytes(StandardCharsets.UTF_8));
        diagnostics.save();
        assertFalse(target.exists());
        Files.write(launch.toPath(), new byte[Pd2ControllerDiagnostics.MAX_REPORT_BYTES + 1]);
        diagnostics.save();
        assertFalse(target.exists());
        writeLaunch(target, "expected");
        Pd2ControllerDiagnostics advancedRuntime = new Pd2ControllerDiagnostics(context);
        advancedRuntime.save();
        assertFalse(target.exists());
        diagnostics.save();
        assertTrue(target.isFile());
        assertFalse(new File(target.getParentFile(), "controller.json.tmp").exists());
    }

    private static void writeLaunch(File target, String id) throws Exception {
        Files.write(new File(target.getParentFile(), "launch.json").toPath(),
                new JSONObject().put("launchId", id).toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject readReport(File target) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
    }

    private static InputDevice device(int id, String name, int sources, int axisSource) {
        // Robolectric's current InputDeviceBuilder targets Android 15; exercise
        // the actual Android 13 representation used by Thor instead.
        try {
            Constructor<InputDevice> constructor = InputDevice.class.getDeclaredConstructor(int.class, int.class, int.class,
                    String.class, int.class, int.class, String.class, boolean.class, int.class, int.class,
                    KeyCharacterMap.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
            constructor.setAccessible(true);
            InputDevice device = constructor.newInstance(id, 0, 0, name, 0x045e, 0x028e, "descriptor-private-fixture",
                    false, sources, 0, null, false, false, false, false, false);
            Method range = InputDevice.class.getDeclaredMethod("addMotionRange", int.class, int.class,
                    float.class, float.class, float.class, float.class, float.class);
            range.setAccessible(true);
            range.invoke(device, MotionEvent.AXIS_X, axisSource, -1f, 1f, 0f, 0f, 0f);
            range.invoke(device, MotionEvent.AXIS_Y, axisSource, -1f, 1f, 0f, 0f, 0f);
            return device;
        }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    @Implements(InputDevice.class)
    public static final class InputDevices {
        static final LinkedHashMap<Integer, InputDevice> devices = new LinkedHashMap<>();
        @Implementation protected static int[] getDeviceIds() {
            int[] result = new int[devices.size()]; int index = 0;
            for (int id : devices.keySet()) result[index++] = id;
            return result;
        }
        @Implementation protected static InputDevice getDevice(int id) { return devices.get(id); }
    }
}
