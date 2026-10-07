package com.winlator.pd2;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exact raw-value journal for a temporary, narrowly filtered Wine relay diagnostic. */
final class Pd2InputTraceRegistry {
    static final String REVISION = "wine9-controller-relay-1";
    static final String FILTER = "xinput1_4.XInputGetState;xinput1_4.XInputGetStateEx;user32.GetRawInputData"
            + ";user32.SendInput;user32.GetCursorPos;user32.GetForegroundWindow";
    static final int MAX_HIVE_BYTES = 16 * 1024 * 1024;
    static final int MAX_VALUE_BYTES = 8 * 1024;
    static final int MAX_JOURNAL_BYTES = 64 * 1024;
    static final int MAX_SECTION_BYTES = 8 * 1024;
    static final int MAX_LINES = 200000;
    private static final String HEADER = "WINE REGISTRY Version 2\n";
    private static final String[] NAMES = {"RelayInclude", "RelayExclude", "RelayFromInclude", "RelayFromExclude"};
    private static final String[] OWNED = {"\"" + FILTER + "\"", "\"\"", null, null};

    private Pd2InputTraceRegistry() { }

    static final class RestoreResult {
        final boolean hadJournal;
        final int restoredFields, conflictsPreserved;
        RestoreResult(boolean hadJournal, int restoredFields, int conflictsPreserved) {
            this.hadJournal = hadJournal;
            this.restoredFields = restoredFields;
            this.conflictsPreserved = conflictsPreserved;
        }
    }

    /** Caller has completed Wine shutdown and holds the environment lifecycle lock. */
    static void apply(File hive, File journal) throws IOException {
        apply(hive, journal, "00000000-0000-0000-0000-000000000018");
    }

    static void apply(File hive, File journal, String owner) throws IOException {
        requireOwner(owner);
        requirePath(hive, true);
        requirePath(journal, false);
        if (journal.exists()) throw new IOException("A controller trace journal still needs restoration");
        byte[] original = read(hive, MAX_HIVE_BYTES);
        Document document = parse(original);
        if (original.length == 0 || original[original.length - 1] != '\n')
            throw new IOException("The Wine registry has an unsupported missing final newline");
        byte[] replacement = replace(document, OWNED, null, document.keyStart < 0, document.newline);
        byte[] previousSection = section(document), appliedSection = section(parse(replacement));
        requireSectionLimit(previousSection);
        requireSectionLimit(appliedSection);
        JSONObject saved = new JSONObject();
        JSONObject prior = new JSONObject();
        try {
            for (int i = 0; i < NAMES.length; i++) {
                Entry entry = document.entries.get(NAMES[i]);
                prior.put(NAMES[i], entry == null ? JSONObject.NULL : encode(entry.raw));
            }
            saved.put("revision", REVISION).put("hive", hive.getCanonicalPath()).put("owner", owner)
                    .put("prior", prior).put("keyCreated", document.keyStart < 0)
                    .put("separator", document.keyStart < 0 ? encode(bytes(document.newline)) : "")
                    .put("newline", encode(bytes(document.newline)))
                    .put("originalSection", previousSection == null ? JSONObject.NULL : encode(previousSection))
                    .put("appliedSection", encode(appliedSection));
        } catch (JSONException error) { throw new IOException("Cannot prepare controller trace journal", error); }
        // Publish the durable journal before the hive. A crash on either side is recoverable.
        atomicReplace(journal, bytesUtf8(saved.toString()), null, MAX_JOURNAL_BYTES);
        atomicReplace(hive, replacement, original, MAX_HIVE_BYTES);
        Document persisted = parse(read(hive, MAX_HIVE_BYTES));
        for (int i = 0; i < NAMES.length; i++) {
            if (!same(payload(persisted.entries.get(NAMES[i])), OWNED[i]))
                throw new IOException("Cannot verify the controller trace filter");
        }
    }

    /** Restores only values still owned by this diagnostic, leaving unrelated changes intact. */
    static RestoreResult restore(File hive, File journal) throws IOException {
        return restore(hive, journal, null);
    }

    static RestoreResult restore(File hive, File journal, String expectedOwner) throws IOException {
        if (expectedOwner != null) requireOwner(expectedOwner);
        requirePath(journal, false);
        if (!journal.exists()) return new RestoreResult(false, 0, 0);
        requirePath(hive, true);
        Journal saved = journal(read(journal, MAX_JOURNAL_BYTES), hive);
        if (expectedOwner != null && !expectedOwner.equals(saved.owner))
            throw new IOException("The controller trace belongs to another launch; it has been kept");
        byte[] original = read(hive, MAX_HIVE_BYTES);
        Document document = parse(original);
        String[] replacements = new String[NAMES.length];
        boolean[] selected = new boolean[NAMES.length];
        int restored = 0, conflicts = 0;
        for (int i = 0; i < NAMES.length; i++) {
            Entry current = document.entries.get(NAMES[i]);
            String currentPayload = payload(current);
            String previousPayload = payload(saved.entries[i]);
            // Also recognize a previous restoration interrupted before journal removal.
            if (same(currentPayload, previousPayload)) continue;
            if (document.keyStart < 0 || !same(currentPayload, OWNED[i])) { conflicts++; continue; }
            selected[i] = true;
            replacements[i] = saved.entries[i] == null ? null : text(saved.entries[i].raw);
            restored++;
        }
        byte[] replacement;
        if (Arrays.equals(section(document), saved.appliedSection)) {
            int start = document.keyStart;
            if (saved.keyCreated && start >= saved.separator.length()
                    && document.content.substring(start - saved.separator.length(), start).equals(saved.separator))
                start -= saved.separator.length();
            replacement = bytes(document.content.substring(0, start)
                    + (saved.originalSection == null ? "" : text(saved.originalSection))
                    + document.content.substring(document.keyEnd));
        } else {
            replacement = replace(document, replacements, selected, false, document.newline);
            if (saved.keyCreated) replacement = removeCreatedEmptyKey(replacement, saved.separator);
        }
        if (!Arrays.equals(original, replacement)) atomicReplace(hive, replacement, original, MAX_HIVE_BYTES);
        // A concurrently changed hive is never overwritten. A changed owned field is kept and counted.
        requirePath(journal, true);
        if (!Arrays.equals(read(journal, MAX_JOURNAL_BYTES), saved.serialized))
            throw new IOException("The controller trace journal changed during restoration");
        if (!journal.delete()) throw new IOException("Cannot retire the controller trace journal");
        return new RestoreResult(true, restored, conflicts);
    }

    private static final class Journal {
        final Entry[] entries = new Entry[NAMES.length];
        final byte[] serialized;
        boolean keyCreated;
        String separator;
        String owner;
        String newline;
        byte[] originalSection, appliedSection;
        Journal(byte[] serialized) { this.serialized = serialized; }
    }

    private static Journal journal(byte[] raw, File hive) throws IOException {
        try {
            String content = new String(raw, StandardCharsets.UTF_8);
            validateJsonShape(content);
            JSONObject json = new JSONObject(content);
            if (json.length() != 9 || !json.has("originalSection") || !REVISION.equals(json.getString("revision"))
                    || !hive.getCanonicalPath().equals(json.getString("hive")))
                throw new IOException("The controller trace journal does not match the captured prefix");
            JSONObject values = json.getJSONObject("prior");
            if (values.length() != NAMES.length) throw new IOException("Invalid controller trace journal fields");
            Journal saved = new Journal(raw);
            saved.owner = json.getString("owner");
            requireOwner(saved.owner);
            saved.keyCreated = json.getBoolean("keyCreated");
            saved.separator = text(decode(json.getString("separator"), 2));
            saved.newline = text(decode(json.getString("newline"), 2));
            if (!saved.newline.equals("\n") && !saved.newline.equals("\r\n"))
                throw new IOException("Invalid controller trace journal newline");
            if (!(saved.keyCreated ? saved.separator.equals("\n") || saved.separator.equals("\r\n")
                    : saved.separator.isEmpty())) throw new IOException("Invalid controller trace journal separator");
            for (int i = 0; i < NAMES.length; i++) {
                if (!values.has(NAMES[i])) throw new IOException("Missing controller trace journal field");
                if (values.isNull(NAMES[i])) continue;
                byte[] entry = decode(values.getString(NAMES[i]), MAX_VALUE_BYTES);
                Document one = parse(bytes("WINE REGISTRY Version 2\n[Software\\\\Wine\\\\Debug] 0\n" + text(entry)));
                if (one.entries.size() != 1 || one.entries.get(NAMES[i]) == null
                        || !Arrays.equals(entry, one.entries.get(NAMES[i]).raw)
                        || one.keyEnd != one.content.length())
                    throw new IOException("Invalid raw controller trace journal value");
                if (saved.keyCreated) throw new IOException("A newly created trace key cannot contain prior values");
                saved.entries[i] = one.entries.get(NAMES[i]);
            }
            saved.originalSection = json.isNull("originalSection") ? null
                    : decode(json.getString("originalSection"), MAX_SECTION_BYTES);
            saved.appliedSection = decode(json.getString("appliedSection"), MAX_SECTION_BYTES);
            if (saved.keyCreated != (saved.originalSection == null))
                throw new IOException("Invalid controller trace key history");
            Document before = saved.originalSection == null ? parse(bytes(HEADER)) : sectionDocument(saved.originalSection);
            for (int i = 0; i < NAMES.length; i++) {
                Entry expected = before.entries.get(NAMES[i]);
                if (!Arrays.equals(expected == null ? null : expected.raw, saved.entries[i] == null ? null : saved.entries[i].raw))
                    throw new IOException("The raw controller trace history is inconsistent");
            }
            sectionDocument(saved.appliedSection);
            byte[] reconstructed = section(parse(replace(before, OWNED, null, saved.keyCreated,
                    saved.newline)));
            if (!Arrays.equals(reconstructed, saved.appliedSection))
                throw new IOException("The controller trace journal changes unowned registry data");
            return saved;
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException("Invalid controller trace journal", error);
        }
    }

    /** The generated schema has two object levels and no arrays; refuse recursive malformed data. */
    private static void validateJsonShape(String value) throws IOException {
        boolean quoted = false, escaped = false;
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
                continue;
            }
            if (c == '"') quoted = true;
            else if (c == '{') { if (++depth > 2) throw new IOException("Controller trace journal is nested beyond its schema"); }
            else if (c == '}') { if (--depth < 0) throw new IOException("Malformed controller trace journal"); }
            else if (c == '[' || c == ']') throw new IOException("Controller trace journal cannot contain arrays");
        }
        if (depth != 0 || quoted) throw new IOException("Truncated controller trace journal");
    }

    private static Document sectionDocument(byte[] section) throws IOException {
        Document document = parse(bytes(HEADER + text(section)));
        if (document.keyStart != HEADER.length() || document.keyEnd != document.content.length())
            throw new IOException("Invalid controller trace registry section");
        return document;
    }

    private static byte[] section(Document document) {
        return document.keyStart < 0 ? null : bytes(document.content.substring(document.keyStart, document.keyEnd));
    }

    private static void requireSectionLimit(byte[] section) throws IOException {
        if (section != null && section.length > MAX_SECTION_BYTES)
            throw new IOException("The Wine Debug registry section exceeds the controller trace limit");
    }

    private static final class Entry {
        final int start, end;
        final String payload;
        final byte[] raw;
        Entry(int start, int end, String payload, String content) {
            this.start = start; this.end = end; this.payload = payload;
            this.raw = bytes(content.substring(start, end));
        }
    }

    private static final class Document {
        final String content;
        final String newline;
        int keyStart = -1, bodyStart = -1, keyEnd;
        final Map<String, Entry> entries = new LinkedHashMap<>();
        Document(String content) {
            this.content = content; keyEnd = content.length();
            int first = content.indexOf('\n');
            newline = first > 0 && content.charAt(first - 1) == '\r' ? "\r\n" : "\n";
        }
    }

    private static final class Line {
        final int start, end;
        final String content;
        Line(int start, int end, String content) { this.start = start; this.end = end; this.content = content; }
    }

    private static Document parse(byte[] raw) throws IOException {
        Document document = new Document(text(raw));
        if (!document.content.startsWith("WINE REGISTRY Version 2\n")
                && !document.content.startsWith("WINE REGISTRY Version 2\r\n"))
            throw new IOException("Unsupported Wine registry header");
        if (document.content.indexOf('\0') >= 0) throw new IOException("Unsupported binary Wine registry content");
        List<Line> lines = lines(document.content);
        boolean inside = false;
        for (int n = 1; n < lines.size(); n++) {
            Line line = lines.get(n);
            if (line.content.startsWith("[")) {
                if (inside) { document.keyEnd = line.start; inside = false; }
                int closing = line.content.indexOf(']');
                if (closing < 0) throw new IOException("Malformed Wine registry key");
                String key = line.content.substring(1, closing);
                if (!"Software\\\\Wine\\\\Debug".equalsIgnoreCase(key)) continue;
                if (document.keyStart >= 0) throw new IOException("Duplicate Wine Debug registry keys");
                document.keyStart = line.start; document.bodyStart = line.end; inside = true;
                continue;
            }
            if (!inside) continue;
            if (line.content.isEmpty() || line.content.startsWith("#") || line.content.startsWith(";")) continue;
            if (!line.content.startsWith("\"")) throw new IOException("Unsupported Wine Debug registry entry");
            int quote = closingQuote(line.content, 0);
            if (quote < 0 || quote + 1 >= line.content.length() || line.content.charAt(quote + 1) != '=')
                throw new IOException("Malformed Wine Debug registry value");
            String name = unescape(line.content.substring(1, quote));
            String selected = selectedName(name);
            int last = n;
            while (lines.get(last).content.endsWith("\\")) {
                if (++last >= lines.size() || !lines.get(last).content.startsWith("  "))
                    throw new IOException("Malformed Wine registry continuation");
            }
            if (selected != null) {
                if (document.entries.containsKey(selected)) throw new IOException("Duplicate controller trace registry value");
                int end = lines.get(last).end;
                if (end - line.start > MAX_VALUE_BYTES) throw new IOException("Controller trace registry value exceeds its limit");
                String payload = document.content.substring(line.start + quote + 2, lines.get(last).start
                        + lines.get(last).content.length());
                validatePayload(payload);
                document.entries.put(selected, new Entry(line.start, end, payload, document.content));
            }
            n = last;
        }
        return document;
    }

    private static void validatePayload(String payload) throws IOException {
        if (payload.startsWith("\"")) {
            if (closingQuote(payload, 0) == payload.length() - 1) return;
        } else if (payload.matches("dword:[a-fA-F0-9]{8}")) return;
        else if (payload.matches("(?:hex(?:\\([a-fA-F0-9]+\\))?):[a-fA-F0-9,\\\\\\r\\n ]*")) {
            String hex = payload.substring(payload.indexOf(':') + 1).replace("\\\r\n  ", "").replace("\\\n  ", "");
            if (hex.isEmpty() || hex.matches("[a-fA-F0-9]{2}(?:,[a-fA-F0-9]{2})*,?")) return;
        } else if (payload.startsWith("str(")) {
            int colon = payload.indexOf(':');
            if (colon > 0 && payload.substring(0, colon).matches("str\\([a-fA-F0-9]+\\)")) {
                String string = payload.substring(colon + 1);
                if (string.startsWith("\"") && closingQuote(string, 0) == string.length() - 1) return;
            }
        }
        throw new IOException("Unsupported controller trace registry value type");
    }

    private static byte[] replace(Document document, String[] replacements, boolean[] selected,
                                  boolean createKey, String newline) throws IOException {
        List<Entry> ordered = new ArrayList<>(document.entries.values());
        ordered.sort((a, b) -> Integer.compare(a.start, b.start));
        StringBuilder out = new StringBuilder(document.content.length() + 1024);
        int position = 0;
        for (Entry entry : ordered) {
            String name = null;
            for (Map.Entry<String, Entry> item : document.entries.entrySet()) if (item.getValue() == entry) name = item.getKey();
            int i = index(name);
            if (selected != null && !selected[i]) continue;
            out.append(document.content, position, entry.start);
            if (replacements[i] != null) out.append(selected == null ? "\"" + NAMES[i] + "\"="
                    + replacements[i] + newline : replacements[i]);
            position = entry.end;
        }
        int insertion = document.keyStart < 0 ? document.content.length() : document.keyEnd;
        out.append(document.content, position, insertion);
        if (createKey) out.append(newline).append("[Software\\\\Wine\\\\Debug] 0").append(newline);
        for (int i = 0; i < NAMES.length; i++) {
            if (document.entries.containsKey(NAMES[i]) || replacements[i] == null || selected != null && !selected[i]) continue;
            out.append(selected == null ? "\"" + NAMES[i] + "\"=" + replacements[i] + newline : replacements[i]);
        }
        out.append(document.content, insertion, document.content.length());
        byte[] result = bytes(out.toString());
        if (result.length > MAX_HIVE_BYTES) throw new IOException("Controller trace would exceed the registry limit");
        return result;
    }

    private static byte[] removeCreatedEmptyKey(byte[] raw, String separator) throws IOException {
        Document document = parse(raw);
        if (document.keyStart < 0) return raw;
        for (Line line : lines(document.content.substring(document.bodyStart, document.keyEnd))) {
            if (!line.content.isEmpty() && !line.content.startsWith("#time=")) return raw;
        }
        int start = document.keyStart;
        if (start >= separator.length() && document.content.substring(start - separator.length(), start).equals(separator))
            start -= separator.length();
        return bytes(document.content.substring(0, start) + document.content.substring(document.keyEnd));
    }

    private static List<Line> lines(String content) throws IOException {
        List<Line> lines = new ArrayList<>();
        for (int start = 0; start < content.length();) {
            int newline = content.indexOf('\n', start);
            int end = newline < 0 ? content.length() : newline + 1;
            int textEnd = newline < 0 ? end : newline;
            if (textEnd > start && content.charAt(textEnd - 1) == '\r') textEnd--;
            lines.add(new Line(start, end, content.substring(start, textEnd)));
            if (lines.size() > MAX_LINES) throw new IOException("Wine registry line count exceeds the controller trace limit");
            start = end;
        }
        return lines;
    }

    private static int closingQuote(String value, int start) {
        boolean escaped = false;
        for (int i = start + 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') escaped = true;
            else if (c == '"') return i;
        }
        return -1;
    }

    private static String unescape(String value) throws IOException {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                if (++i == value.length()) throw new IOException("Truncated registry name escape");
                c = value.charAt(i);
                if (c != '\\' && c != '"') throw new IOException("Unsupported registry name escape");
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int index(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        throw new IllegalArgumentException("Unknown controller trace value");
    }

    private static String selectedName(String name) {
        for (String selected : NAMES) if (selected.equalsIgnoreCase(name)) return selected;
        return null;
    }

    private static String payload(Entry entry) { return entry == null ? null : entry.payload; }
    static void requireOwner(String owner) throws IOException {
        try {
            if (owner == null || owner.length() != 36 || !UUID.fromString(owner).toString().equals(owner))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw new IOException("Invalid controller trace launch identity", error); }
    }
    private static boolean same(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.ISO_8859_1); }
    private static byte[] bytesUtf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value, StandardCharsets.ISO_8859_1); }
    private static String encode(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static byte[] decode(String value, int limit) throws IOException {
        if (value.length() > 4 * ((limit + 2) / 3)) throw new IOException("Controller trace journal value exceeds its limit");
        byte[] decoded = Base64.getDecoder().decode(value);
        if (decoded.length > limit) throw new IOException("Controller trace journal value exceeds its limit");
        return decoded;
    }

    static void requirePath(File file, boolean exists) throws IOException {
        File absolute = file.getAbsoluteFile();
        if (Files.isSymbolicLink(absolute.toPath()) || !absolute.equals(absolute.getCanonicalFile())
                || file.exists() && !file.isFile() || exists && !file.isFile())
            throw new IOException("Controller trace path is missing, not regular, or contains a symbolic link");
    }

    private static byte[] read(File file, int limit) throws IOException {
        requirePath(file, true);
        if (file.length() > limit) throw new IOException("Controller trace file exceeds its size limit");
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("Controller trace file exceeds its size limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void atomicReplace(File target, byte[] replacement, byte[] previous, int limit) throws IOException {
        requirePath(target, previous != null);
        if (replacement.length > limit) throw new IOException("Controller trace file exceeds its size limit");
        File parent = target.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create controller trace journal directory");
        if (!parent.equals(parent.getCanonicalFile()) || Files.isSymbolicLink(parent.toPath()))
            throw new IOException("Controller trace directory contains a symbolic link");
        File staged = File.createTempFile(".pd2-input-trace-", ".tmp", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(staged)) { output.write(replacement); output.getFD().sync(); }
            if (!staged.setReadable(true, true) || !staged.setWritable(true, true))
                throw new IOException("Cannot set private controller trace file permissions");
            if (!Arrays.equals(read(staged, limit), replacement)) throw new IOException("Controller trace staging verification failed");
            requirePath(target, previous != null);
            if (previous == null ? target.exists() : !Arrays.equals(read(target, limit), previous))
                throw new IOException("Controller trace file changed during preparation; it has been kept");
            if (!staged.renameTo(target)) throw new IOException("Cannot publish controller trace file");
            if (!Arrays.equals(read(target, limit), replacement)) throw new IOException("Controller trace file verification failed");
        } finally { staged.delete(); }
    }
}
