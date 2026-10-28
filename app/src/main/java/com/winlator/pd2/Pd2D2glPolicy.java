package com.winlator.pd2;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only existing, typed settings documented by the official PD2 D2GL JSON reader. */
public final class Pd2D2glPolicy {
    public static final String REVISION = "pd2-d2gl-json-1";
    public static final String SOURCE_COMMIT = "55148de0aec79d487f5bcef1842cfdc3effd2f62";
    /** The fallback selected by the official D2GL Upscaler::loadDefaultPreset. */
    public static final String SIMPLE_SCALING_PRESET = "bilinear.slangp";
    static final String[] FRAME_KEYS = {"screen.window_vsync", "screen.foreground_fps_active", "screen.foreground_fps_value"};
    static final String[] EFFECT_KEYS = {"graphics.bloom", "graphics.fxaa", "graphics.sharpen"};
    static final String[] CURSOR_KEYS = {"features.hd_cursor"};
    static final String[] SCALING_KEYS = {"graphics.shader_preset"};
    static final String[][] OWNED_GROUPS = {FRAME_KEYS, EFFECT_KEYS, CURSOR_KEYS, SCALING_KEYS};
    static final Object MISSING = new Object();
    private Pd2D2glPolicy() {}

    static boolean allowed(String key) {
        for (String[] group : OWNED_GROUPS) for (String item : group) if (item.equals(key)) return true;
        return false;
    }
    static boolean effectKey(String key) {
        for (String item : EFFECT_KEYS) if (item.equals(key)) return true;
        return false;
    }

    static final class Span {
        final int start, end;
        Span(int start, int end) { this.start = start; this.end = end; }
    }

    static final class Document {
        final String text;
        final JSONObject json;
        final Map<String, Span> spans;
        Document(String text, JSONObject json, Map<String, Span> spans) {
            this.text = text; this.json = json; this.spans = spans;
        }
        Object value(String path) {
            int dot = path.indexOf('.');
            JSONObject section = json.optJSONObject(path.substring(0, dot));
            String key = path.substring(dot + 1);
            return section != null && section.has(key) ? section.opt(key) : MISSING;
        }
        boolean lightSchema() {
            for (String key : EFFECT_KEYS) if (!(value(key) instanceof Boolean)) return false;
            return true;
        }
        boolean cursorSchema() { return value(CURSOR_KEYS[0]) instanceof Boolean; }
        boolean scalingSchema() { return safePreset(value(SCALING_KEYS[0])); }
        JSONObject current() throws JSONException {
            JSONObject values = new JSONObject();
            for (String[] group : OWNED_GROUPS) {
                for (String key : group) {
                    Object value = value(key);
                    if (validOwnedValue(key, value)) values.put(key, value);
                }
            }
            // Diagnostics only: these settings never enter the mutation spans or ownership journal.
            for (String key : new String[]{"screen.window_size_width", "screen.window_size_height"}) {
                Object value = value(key);
                if (range(value, key.endsWith("width") ? 800 : 600, 32767)) values.put(key, value);
            }
            Object latency = value("other.frame_latency");
            if (range(latency, 1, 5)) values.put("other.frame_latency", latency);
            return values;
        }
        byte[] replace(Map<String, Object> changes) throws IOException {
            List<String> keys = new ArrayList<>(changes.keySet());
            for (String key : keys) {
                if (!allowed(key) || !spans.containsKey(key) || !validOwnedValue(key, changes.get(key)))
                    throw new IOException("Unsupported D2GL setting change");
            }
            keys.sort((a, b) -> Integer.compare(spans.get(b).start, spans.get(a).start));
            StringBuilder result = new StringBuilder(text);
            for (String key : keys) {
                Span span = spans.get(key);
                Object value = changes.get(key);
                result.replace(span.start, span.end,
                        value instanceof String ? JSONObject.quote((String)value) : String.valueOf(value));
            }
            return result.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    static Document parseJson(byte[] bytes) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) { throw new IOException("D2GL config is not valid UTF-8", error); }
        Scanner scanner = new Scanner(text);
        scanner.document();
        try {
            return new Document(text, new JSONObject(text), scanner.spans);
        } catch (JSONException error) { throw new IOException("D2GL config is not a JSON object", error); }
    }

    static Document parse(byte[] bytes) throws IOException {
        Document document = parseJson(bytes);
        JSONObject screen = document.json.optJSONObject("screen");
        if (screen == null || !(screen.opt("window_vsync") instanceof Boolean)
                || !(screen.opt("foreground_fps_active") instanceof Boolean)
                || !range(screen.opt("foreground_fps_value"), 25, 240)
                || !(screen.opt("background_fps_active") instanceof Boolean)
                || !range(screen.opt("background_fps_value"), 25, 60)
                || !(screen.opt("window_fullscreen") instanceof Boolean)
                || !range(screen.opt("window_size_width"), 800, 32767)
                || !range(screen.opt("window_size_height"), 600, 32767))
            throw new IOException("D2GL JSON screen schema is not recognized; config kept");
        return document;
    }

    static Map<String, Object> desired(Document document, int fps, boolean light) throws IOException {
        return desired(document, fps, light, false);
    }

    static Map<String, Object> desired(Document document, int fps, boolean light, boolean classicCursor)
            throws IOException {
        return desired(document, fps, light, classicCursor, false);
    }

    static Map<String, Object> desired(Document document, int fps, boolean light, boolean classicCursor,
                                       boolean simpleScaling) throws IOException {
        if (fps != -1 && fps != 0 && fps != 30 && fps != 60) throw new IOException("Unsupported D2GL frame limit");
        Map<String, Object> desired = new LinkedHashMap<>();
        if (fps >= 0) {
            desired.put(FRAME_KEYS[0], false);
            desired.put(FRAME_KEYS[1], fps > 0);
            desired.put(FRAME_KEYS[2], fps > 0 ? fps : document.value(FRAME_KEYS[2]));
        }
        if (light && document.lightSchema()) for (String key : EFFECT_KEYS) desired.put(key, false);
        if (classicCursor && document.cursorSchema()) desired.put(CURSOR_KEYS[0], false);
        if (simpleScaling && document.scalingSchema()) desired.put(SCALING_KEYS[0], SIMPLE_SCALING_PRESET);
        return desired;
    }

    static boolean validOwnedValue(String key, Object value) {
        return allowed(key) && (SCALING_KEYS[0].equals(key) ? safePreset(value)
                : FRAME_KEYS[2].equals(key) ? range(value, 25, 240) : value instanceof Boolean);
    }
    static boolean integral(Object value) { return value instanceof Integer || value instanceof Long; }
    static boolean safePreset(Object value) {
        if (!(value instanceof String)) return false;
        String path = (String)value;
        if (path.isEmpty() || path.length() > 96 || !path.matches("[A-Za-z0-9_ .\\\\/-]+")) return false;
        for (String part : path.replace('\\', '/').split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        }
        return true;
    }
    private static boolean range(Object value, long min, long max) {
        return integral(value) && ((Number)value).longValue() >= min && ((Number)value).longValue() <= max;
    }

    /** Strict JSON syntax/duplicate checks; Android's JSONObject parser is deliberately lenient. */
    private static final class Scanner {
        final String text;
        final Map<String, Span> spans = new LinkedHashMap<>();
        int at, values;
        Scanner(String text) { this.text = text; }
        void document() throws IOException {
            space(); object(0, null); space();
            if (at != text.length()) bad();
        }
        void value(int depth, String section) throws IOException {
            if (depth > 32 || ++values > 8192) bad();
            space();
            if (at >= text.length()) bad();
            char c = text.charAt(at);
            if (c == '{') object(depth, section);
            else if (c == '[') {
                at++; space();
                if (take(']')) return;
                do { value(depth + 1, null); space(); if (take(']')) return; } while (take(','));
                bad();
            } else if (c == '"') string();
            else if (c == 't') literal("true");
            else if (c == 'f') literal("false");
            else if (c == 'n') literal("null");
            else number();
        }
        void object(int depth, String section) throws IOException {
            if (depth > 32 || !take('{')) bad();
            Set<String> keys = new HashSet<>();
            space(); if (take('}')) return;
            do {
                space(); String key = string();
                if (!keys.add(key)) throw new IOException("D2GL config has duplicate JSON keys; config kept");
                space(); if (!take(':')) bad(); space();
                int start = at;
                value(depth + 1, depth == 0 ? key : null);
                if (depth == 1 && section != null && allowed(section + "." + key))
                    spans.put(section + "." + key, new Span(start, at));
                space(); if (take('}')) return;
            } while (take(','));
            bad();
        }
        String string() throws IOException {
            if (!take('"')) { bad(); return ""; }
            StringBuilder result = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    for (int i = 0; i < result.length(); i++) {
                        char u = result.charAt(i);
                        if (Character.isHighSurrogate(u)) {
                            if (++i >= result.length() || !Character.isLowSurrogate(result.charAt(i))) bad();
                        } else if (Character.isLowSurrogate(u)) bad();
                    }
                    return result.toString();
                }
                if (c < 0x20) bad();
                if (c == '\\') {
                    if (at == text.length()) bad();
                    c = text.charAt(at++);
                    switch (c) {
                        case '"': case '\\': case '/': break;
                        case 'b': c = '\b'; break; case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break;
                        case 'u':
                            int code = 0;
                            for (int i = 0; i < 4; i++) {
                                if (at == text.length()) bad();
                                char hex = text.charAt(at++);
                                int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                        : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                        : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                                if (digit < 0) bad();
                                code = code * 16 + digit;
                            }
                            c = (char)code; break;
                        default: bad();
                    }
                }
                result.append(c);
            }
            bad(); return "";
        }
        void number() throws IOException {
            int start = at;
            take('-');
            if (!take('0')) {
                if (at >= text.length() || text.charAt(at) < '1' || text.charAt(at) > '9') bad();
                digits();
            }
            if (take('.')) { int before = at; digits(); if (at == before) bad(); }
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                int before = at; digits(); if (at == before) bad();
            }
            if (at == start) bad();
        }
        void digits() { while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++; }
        void literal(String token) throws IOException {
            if (!text.startsWith(token, at)) bad();
            at += token.length();
        }
        void space() { while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++; }
        boolean take(char c) { if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        void bad() throws IOException { throw new IOException("D2GL config has unsupported JSON syntax; config kept"); }
    }
}
