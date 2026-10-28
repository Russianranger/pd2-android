package com.winlator.pd2;

import android.app.Application;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public final class Pd2D2glPolicyTest {
    static String config() {
        return "{\n  \"screen\": {\"window_vsync\": true, \"foreground_fps_active\": false, \"foreground_fps_value\": 144,"
                + " \"background_fps_active\": true, \"background_fps_value\": 25, \"window_fullscreen\": true,"
                + " \"window_size_width\": 1280, \"window_size_height\": 720, \"userScreen\": [1,2,3]},\n"
                + "  \"graphics\": {\"bloom\": true, \"fxaa\": true, \"sharpen\": true, \"shader_preset\": \"user\\\\preset.slangp\"},\n"
                + "  \"unknown\": {\"precise\": 0.12345678901234567890, \"huge\": 9223372036854775808, \"label\": \"Keep 😀\"},"
                + " \"features\": {\"hd_cursor\": true}, \"other\": {\"frame_latency\": 3}\n}\n";
    }
    static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @Test public void frameAndEffectsChangesPreserveAllUnownedBytesAndOptions() throws Exception {
        String original = config();
        Pd2D2glPolicy.Document document = Pd2D2glPolicy.parse(bytes(original));
        byte[] after = document.replace(Pd2D2glPolicy.desired(document, 60, true));
        String expected = original.replace("\"window_vsync\": true", "\"window_vsync\": false")
                .replace("\"foreground_fps_active\": false", "\"foreground_fps_active\": true")
                .replace("\"foreground_fps_value\": 144", "\"foreground_fps_value\": 60")
                .replace("\"bloom\": true", "\"bloom\": false").replace("\"fxaa\": true", "\"fxaa\": false")
                .replace("\"sharpen\": true", "\"sharpen\": false");
        assertArrayEquals(bytes(expected), after);
        JSONObject json = new JSONObject(new String(after, StandardCharsets.UTF_8));
        assertEquals(25, json.getJSONObject("screen").getInt("background_fps_value"));
        assertEquals(1280, json.getJSONObject("screen").getInt("window_size_width"));
        assertEquals(720, json.getJSONObject("screen").getInt("window_size_height"));
        assertTrue(json.getJSONObject("features").getBoolean("hd_cursor"));
        assertEquals(3, json.getJSONObject("other").getInt("frame_latency"));
    }

    @Test public void supportedLimitsUseTheRealLimiterAndNeverWriteAnInvalidZeroFps() throws Exception {
        Pd2D2glPolicy.Document document = Pd2D2glPolicy.parse(bytes(config()));
        for (int fps : new int[]{30, 60}) {
            Map<String, Object> wanted = Pd2D2glPolicy.desired(document, fps, false);
            assertEquals(false, wanted.get("screen.window_vsync"));
            assertEquals(true, wanted.get("screen.foreground_fps_active"));
            assertEquals(fps, wanted.get("screen.foreground_fps_value"));
        }
        Map<String, Object> unlimited = Pd2D2glPolicy.desired(document, 0, false);
        assertEquals(false, unlimited.get("screen.foreground_fps_active"));
        assertEquals(144, ((Number)unlimited.get("screen.foreground_fps_value")).intValue());
        assertTrue(Pd2D2glPolicy.desired(document, -1, false).isEmpty());
        for (int invalid : new int[]{-2, 25, 40, 120, Integer.MAX_VALUE}) {
            try { Pd2D2glPolicy.desired(document, invalid, false); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("frame limit")); }
        }
    }

    @Test public void absentOrUntypedGraphicsCannotBeInventedByTheLightProfile() throws Exception {
        String missing = config().replace("\"fxaa\": true, ", "");
        Pd2D2glPolicy.Document document = Pd2D2glPolicy.parse(bytes(missing));
        assertFalse(document.lightSchema());
        Map<String, Object> desired = Pd2D2glPolicy.desired(document, 60, true);
        assertEquals(3, desired.size());
        assertFalse(desired.containsKey("graphics.bloom"));
    }

    @Test public void unknownScreenTypesAndLimitsAreRejectedBeforeMutation() throws Exception {
        for (String text : new String[]{config().replace("\"foreground_fps_value\": 144", "\"foreground_fps_value\": 144.5"),
                config().replace("\"window_vsync\": true", "\"window_vsync\": \"true\""),
                config().replace("\"background_fps_value\": 25", "\"background_fps_value\": 120"),
                config().replace("\"window_size_height\": 720", "\"window_size_height\": 540"),
                "{}"}) {
            try { Pd2D2glPolicy.parse(bytes(text)); fail("Unknown schema must stay unchanged"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("schema")); }
        }
    }

    @Test public void strictJsonRejectsDuplicatesLenientSyntaxBadUnicodeAndTrailingData() throws Exception {
        for (String text : new String[]{config().replace("\"window_vsync\": true", "\"window_vsync\": true, \"window_vsync\": false"),
                config().replace("\"window_vsync\": true", "\"window_vsync\": true, \"window_\\u0076sync\": false"),
                "{'screen': {}}", "{/* comment */}", "{\"x\":01}", "{\"x\":true,}",
                "{\"x\":\"\\uD800\"}", "{} trailing"}) {
            try { Pd2D2glPolicy.parse(bytes(text)); fail("Unsupported JSON must be rejected"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("JSON")); }
        }
        try { Pd2D2glPolicy.parse(new byte[]{'{', '"', (byte)0xff, '"', ':', '0', '}'}); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("UTF-8")); }
        StringBuilder deep = new StringBuilder("{\"x\":");
        for (int i = 0; i < 40; i++) deep.append('[');
        deep.append('0'); for (int i = 0; i < 40; i++) deep.append(']'); deep.append('}');
        try { Pd2D2glPolicy.parse(bytes(deep.toString())); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("JSON")); }
    }
}
