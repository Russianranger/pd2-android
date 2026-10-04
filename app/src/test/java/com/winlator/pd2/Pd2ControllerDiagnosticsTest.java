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
        assertEquals("java-hid-7950-v1", report.getString("bridgeRevision"));
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
