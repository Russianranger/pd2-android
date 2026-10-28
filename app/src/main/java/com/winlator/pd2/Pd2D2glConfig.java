package com.winlator.pd2;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Reversible changes to an existing config in the captured, app-private PD2 installation. */
public final class Pd2D2glConfig {
    static final int MAX_CONFIG_BYTES = 64 * 1024;
    static final int MAX_JOURNAL_BYTES = 16 * 1024;
    private static final String MARKER = ".pd2-d2gl-installation-id";
    private static final Object LOCK = new Object();
    private Pd2D2glConfig() {}

    /** Caller holds the environment lifecycle lock and captures all arguments before async work. */
    public static JSONObject prepareAfterCleanup(Context context, Pd2WineSession.Result cleanup,
                                                File capturedProjectD2Directory, int fps,
                                                boolean lighterEffects, String launchId) throws IOException {
        return prepareAfterCleanup(context, cleanup, capturedProjectD2Directory, fps, lighterEffects, false, launchId);
    }

    public static JSONObject prepareAfterCleanup(Context context, Pd2WineSession.Result cleanup,
                                                File capturedProjectD2Directory, int fps,
                                                boolean lighterEffects, boolean classicCursorRequested,
                                                String launchId) throws IOException {
        return prepareAfterCleanup(context, cleanup, capturedProjectD2Directory, fps, lighterEffects,
                classicCursorRequested, false, launchId);
    }

    public static JSONObject prepareAfterCleanup(Context context, Pd2WineSession.Result cleanup,
                                                File capturedProjectD2Directory, int fps,
                                                boolean lighterEffects, boolean classicCursorRequested,
                                                boolean simpleScalingRequested, String launchId) throws IOException {
        requireCleanup(cleanup);
        File files = context.getFilesDir();
        try {
            return prepareFiles(files.getCanonicalFile(), normalizeCapturedDirectory(files, capturedProjectD2Directory),
                    fps, lighterEffects, classicCursorRequested, simpleScalingRequested, launchId, null);
        } catch (IOException error) { return failure(status(fps, lighterEffects, classicCursorRequested,
                simpleScalingRequested), error); }
    }

    /** Bounded, read-only snapshot; the hash/values describe the file, not what a running DLL read. */
    public static JSONObject inspect(Context context, File capturedProjectD2Directory) {
        try {
            File files = context.getFilesDir();
            return inspectFiles(files.getCanonicalFile(), normalizeCapturedDirectory(files, capturedProjectD2Directory));
        }
        catch (IOException error) { return failure(status(-1, false), error); }
    }

    static void requireCleanup(Pd2WineSession.Result cleanup) throws IOException {
        if (cleanup == null || !cleanup.passed || !"beforeLaunch".equals(cleanup.phase))
            throw new IOException("D2GL settings require passed Wine cleanup before launch");
    }

    interface TransactionHook { void beforeConfigReplace(File config) throws IOException; }

    static JSONObject prepareFiles(File files, File game, int fps, boolean light, String launchId,
                                   TransactionHook hook) {
        return prepareFiles(files, game, fps, light, false, launchId, hook);
    }

    static JSONObject prepareFiles(File files, File game, int fps, boolean light, boolean classicCursorRequested,
                                   String launchId, TransactionHook hook) {
        return prepareFiles(files, game, fps, light, classicCursorRequested, false, launchId, hook);
    }

    static JSONObject prepareFiles(File files, File game, int fps, boolean light, boolean classicCursorRequested,
                                   boolean simpleScalingRequested, String launchId, TransactionHook hook) {
        synchronized (LOCK) {
            JSONObject report = status(fps, light, classicCursorRequested, simpleScalingRequested);
            try {
                if (fps != -1 && fps != 0 && fps != 30 && fps != 60)
                    throw new IOException("Unsupported D2GL frame limit; config kept");
                if (launchId == null || !launchId.matches("[A-Za-z0-9_-]{1,80}"))
                    throw new IOException("Invalid captured D2GL launch identity; config kept");
                File config = findConfig(files, game);
                if (config == null) return skipped(report, "Existing ProjectD2/d2gl.json was not found; config kept");
                byte[] before = read(config, MAX_CONFIG_BYTES);
                Pd2D2glPolicy.Document document;
                try { document = Pd2D2glPolicy.parse(before); }
                catch (IOException unsupported) {
                    report.put("configFound", true).put("configBytes", before.length).put("configSha256", hash(before));
                    return skipped(report, unsupported.getMessage());
                }
                describe(report, document, before);
                Map<String, Object> desired = Pd2D2glPolicy.desired(document, fps, light, classicCursorRequested,
                        simpleScalingRequested);
                if (light && !document.lightSchema())
                    report.put("effectsReason", "Existing graphics booleans are not recognized; effects kept");
                if (classicCursorRequested && !document.cursorSchema())
                    report.put("cursorReason", "Existing features.hd_cursor Boolean is missing or unrecognized; cursor setting kept");
                if (simpleScalingRequested && !document.scalingSchema())
                    report.put("scalingReason", "Existing graphics.shader_preset safe string is missing or unrecognized; shader setting kept");

                File marker = new File(game, MARKER);
                safe(files, marker);
                String id = marker.exists() ? installationId(read(marker, 80)) : null;
                if (id == null && desired.isEmpty()) return unchanged(report, "No launcher-owned settings to restore");
                if (id == null) {
                    boolean changes = false;
                    for (Map.Entry<String, Object> item : desired.entrySet())
                        changes |= !same(document.value(item.getKey()), item.getValue());
                    if (!changes) {
                        applied(report, document, fps, light, classicCursorRequested, simpleScalingRequested);
                        return unchanged(report, "Existing D2GL settings already match the request");
                    }
                    id = UUID.randomUUID().toString();
                    atomic(files, marker, (id + "\n").getBytes(StandardCharsets.UTF_8), null, 80);
                }
                // Reimport replaces the installation directory and removes this marker. A new
                // identity gets new fixed state paths, so an old journal cannot select its target.
                File stateDir = new File(files, "pd2/runtime/d2gl/" + id);
                safe(files, stateDir);
                if (!stateDir.isDirectory() && !stateDir.mkdirs()) throw new IOException("Cannot create private D2GL backup directory");
                safe(files, stateDir);
                File backup = new File(stateDir, "original.json"), journal = new File(stateDir, "journal.json");
                safe(files, backup); safe(files, journal);
                byte[] oldJournal = journal.exists() ? read(journal, MAX_JOURNAL_BYTES) : null;
                State state = oldJournal == null ? null : parseState(oldJournal, files, game, config, id);
                String baselineHash;
                Map<String, Entry> previous = new LinkedHashMap<>();
                if (state != null) {
                    baselineHash = state.originalHash;
                    if (!backup.exists() || !baselineHash.equals(hash(read(backup, MAX_CONFIG_BYTES))))
                        throw new IOException("Original D2GL backup cannot be verified; config kept");
                    report.put("backupAvailable", true);
                    previous = state.entries;
                    if (state.pending) {
                        String currentHash = hash(before);
                        if (currentHash.equals(state.beforeHash)) previous = state.previous;
                        else if (!currentHash.equals(state.afterHash))
                            return skipped(report, "D2GL config changed during an unfinished transaction; backup kept");
                    }
                } else {
                    baselineHash = hash(before);
                    if (backup.exists()) {
                        if (!baselineHash.equals(hash(read(backup, MAX_CONFIG_BYTES))))
                            throw new IOException("Unowned D2GL backup differs from this config; config kept");
                    } else atomic(files, backup, before, null, MAX_CONFIG_BYTES);
                }
                if (!baselineHash.equals(hash(read(backup, MAX_CONFIG_BYTES))))
                    throw new IOException("Original D2GL backup cannot be verified; config kept");
                report.put("backupAvailable", true);
                Map<String, Entry> next = new LinkedHashMap<>();
                Map<String, Object> changes = new LinkedHashMap<>();
                int restored = 0, conflicts = 0, rebased = 0;
                for (String[] group : Pd2D2glPolicy.OWNED_GROUPS) {
                    for (String key : group) {
                        Entry owned = previous.get(key);
                        Object current = document.value(key);
                        if (light && !document.lightSchema() && Pd2D2glPolicy.effectKey(key)) {
                            if (owned != null) next.put(key, owned);
                            continue;
                        }
                        if (classicCursorRequested && !document.cursorSchema()
                                && Pd2D2glPolicy.CURSOR_KEYS[0].equals(key)) {
                            if (owned != null) next.put(key, owned);
                            continue;
                        }
                        if (simpleScalingRequested && !document.scalingSchema()
                                && Pd2D2glPolicy.SCALING_KEYS[0].equals(key)) {
                            if (owned != null) next.put(key, owned);
                            continue;
                        }
                        if (desired.containsKey(key)) {
                            Object wanted = desired.get(key), original = owned == null ? current : owned.original;
                            // A later game/user choice becomes the value to restore when the
                            // explicitly captured launcher setting is applied again.
                            if (owned != null && !same(current, owned.applied)) { original = current; rebased++; }
                            if (!same(current, wanted)) changes.put(key, wanted);
                            if (!same(original, wanted)) next.put(key, new Entry(original, wanted));
                        } else if (owned != null) {
                            if (same(current, owned.applied)) {
                                if (!same(current, owned.original)) { changes.put(key, owned.original); restored++; }
                            } else if (!same(current, owned.original)) conflicts++;
                        }
                    }
                }
                byte[] after = changes.isEmpty() ? before : document.replace(changes);
                JSONArray fieldChanges = new JSONArray();
                for (Map.Entry<String, Object> change : changes.entrySet()) fieldChanges.put(new JSONObject()
                        .put("field", change.getKey()).put("before", document.value(change.getKey()))
                        .put("after", change.getValue()));
                if (after.length > MAX_CONFIG_BYTES) throw new IOException("D2GL replacement exceeds its size limit; config kept");
                State transaction = new State(id, relative(files, game), config.getName(), baselineHash,
                        hash(before), hash(after), launchId, true, next, previous);
                byte[] pending = transaction.bytes();
                atomic(files, journal, pending, oldJournal, MAX_JOURNAL_BYTES);
                if (!changes.isEmpty()) {
                    if (hook != null) hook.beforeConfigReplace(config);
                    if (!baselineHash.equals(hash(read(backup, MAX_CONFIG_BYTES))))
                        throw new IOException("Original D2GL backup changed before config replacement; config kept");
                    atomic(files, config, after, before, MAX_CONFIG_BYTES);
                }
                byte[] actual = read(config, MAX_CONFIG_BYTES);
                if (!Arrays.equals(after, actual)) throw new IOException("D2GL config changed before verification; transaction retained");
                transaction.pending = false;
                atomic(files, journal, transaction.bytes(), pending, MAX_JOURNAL_BYTES);
                document = Pd2D2glPolicy.parse(actual);
                describe(report, document, actual);
                report.put("changedFields", changes.size()).put("restoredFields", restored)
                        .put("conflictsPreserved", conflicts).put("userValuesRebased", rebased)
                        .put("fieldChanges", fieldChanges);
                applied(report, document, fps, light, classicCursorRequested, simpleScalingRequested);
                report.put("status", changes.isEmpty() && !report.optBoolean("capApplied")
                                && !report.optBoolean("effectsApplied") && !report.optBoolean("cursorApplied")
                                && !report.optBoolean("scalingApplied") ? "unchanged" : "applied")
                        .put("reason", conflicts > 0 ? "Launcher-owned settings restored; later user changes kept"
                                : changes.isEmpty() ? "Existing D2GL settings match the captured request"
                                : "Validated D2GL settings written atomically before launch");
                return report;
            } catch (IOException | JSONException error) { return failure(report, error); }
        }
    }

    static JSONObject inspectFiles(File files, File game) {
        JSONObject report = status(-1, false);
        try {
            report.put("readOnly", true);
            File config = findConfig(files, game);
            if (config == null) return skipped(report, "Existing ProjectD2/d2gl.json was not found");
            byte[] data = read(config, MAX_CONFIG_BYTES);
            report.put("configFound", true).put("configBytes", data.length).put("configSha256", hash(data));
            try { describe(report, Pd2D2glPolicy.parse(data), data); }
            catch (IOException error) { return skipped(report, error.getMessage()); }
            report.put("status", "observed").put("reason", "Read-only config snapshot; running DLL values are not measured");
            return report;
        } catch (IOException | JSONException error) { return failure(report, error); }
    }

    private static final class Entry {
        final Object original, applied;
        Entry(Object original, Object applied) { this.original = original; this.applied = applied; }
    }
    private static final class State {
        final String id, game, name, originalHash, beforeHash, afterHash, launch;
        boolean pending;
        final Map<String, Entry> entries, previous;
        State(String id, String game, String name, String originalHash, String beforeHash, String afterHash,
              String launch, boolean pending, Map<String, Entry> entries, Map<String, Entry> previous) {
            this.id = id; this.game = game; this.name = name; this.originalHash = originalHash;
            this.beforeHash = beforeHash; this.afterHash = afterHash; this.launch = launch;
            this.pending = pending; this.entries = entries; this.previous = previous;
        }
        byte[] bytes() throws JSONException {
            return new JSONObject().put("revision", Pd2D2glPolicy.REVISION).put("installationId", id)
                    .put("gameRelativePath", game).put("configName", name).put("originalSha256", originalHash)
                    .put("beforeSha256", beforeHash).put("afterSha256", afterHash).put("launchId", launch)
                    .put("pending", pending).put("entries", entries(entries)).put("previousEntries", entries(previous))
                    .toString().getBytes(StandardCharsets.UTF_8);
        }
    }
    private static JSONObject entries(Map<String, Entry> entries) throws JSONException {
        JSONObject result = new JSONObject();
        for (Map.Entry<String, Entry> item : entries.entrySet()) result.put(item.getKey(),
                new JSONObject().put("original", item.getValue().original).put("applied", item.getValue().applied));
        return result;
    }
    private static Map<String, Entry> entries(JSONObject object) throws IOException, JSONException {
        if (object == null || object.length() > 8) throw new IOException("Invalid D2GL ownership journal; config kept");
        Map<String, Entry> result = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next(); JSONObject value = object.getJSONObject(key);
            Object original = value.get("original"), applied = value.get("applied");
            if (!Pd2D2glPolicy.validOwnedValue(key, original) || !Pd2D2glPolicy.validOwnedValue(key, applied))
                throw new IOException("Unsupported D2GL journal field; config kept");
            result.put(key, new Entry(original, applied));
        }
        return result;
    }
    private static State parseState(byte[] data, File files, File game, File config, String id) throws IOException {
        try {
            JSONObject object = Pd2D2glPolicy.parseJson(data).json;
            for (String key : new String[]{"revision", "installationId", "gameRelativePath", "configName",
                    "originalSha256", "beforeSha256", "afterSha256", "launchId"})
                if (!(object.opt(key) instanceof String)) throw new IOException("Invalid D2GL journal metadata; config kept");
            if (!(object.opt("pending") instanceof Boolean)) throw new IOException("Invalid D2GL journal state; config kept");
            if (!Pd2D2glPolicy.REVISION.equals(object.getString("revision"))
                    || !id.equals(object.getString("installationId"))
                    || !relative(files, game).equals(object.getString("gameRelativePath"))
                    || !config.getName().equals(object.getString("configName")))
                throw new IOException("D2GL journal does not own this captured config; config kept");
            for (String key : new String[]{"originalSha256", "beforeSha256", "afterSha256"})
                if (!object.getString(key).matches("[0-9a-f]{64}")) throw new IOException("Invalid D2GL journal digest; config kept");
            return new State(id, relative(files, game), config.getName(), object.getString("originalSha256"),
                    object.getString("beforeSha256"), object.getString("afterSha256"), object.getString("launchId"),
                    object.getBoolean("pending"), entries(object.getJSONObject("entries")), entries(object.getJSONObject("previousEntries")));
        } catch (JSONException error) { throw new IOException("Invalid D2GL ownership journal; config kept", error); }
    }

    private static File findConfig(File files, File game) throws IOException {
        if (files == null || game == null) throw new IOException("No captured private ProjectD2 directory is available");
        File boundary = new File(files, "pd2/install");
        safe(files, boundary); safe(files, game);
        if (!game.getPath().startsWith(boundary.getPath() + File.separator)
                || !game.getName().equalsIgnoreCase("ProjectD2") || !game.isDirectory())
            throw new IOException("Captured D2GL directory is outside the private imported ProjectD2 installation");
        File[] children = game.listFiles();
        if (children == null || children.length > 4096) throw new IOException("Cannot safely inspect the captured D2GL directory");
        File found = null;
        for (File child : children) if (child.getName().equalsIgnoreCase("d2gl.json")) {
            if (found != null) throw new IOException("Ambiguous D2GL config names; files kept");
            safe(files, child);
            if (!child.isFile()) throw new IOException("D2GL config is not a regular file; config kept");
            found = child;
        }
        return found;
    }

    /** Only the trusted Context files-root alias may be canonicalized, never an imported child link. */
    static File normalizeCapturedDirectory(File filesRoot, File captured) throws IOException {
        if (captured == null) throw new IOException("No captured private ProjectD2 directory is available");
        File rawRoot = filesRoot.getAbsoluteFile(), canonicalRoot = filesRoot.getCanonicalFile();
        File absolute = captured.getAbsoluteFile();
        String rawPrefix = rawRoot.getPath() + File.separator;
        File normalized;
        if (absolute.getPath().startsWith(rawPrefix))
            normalized = new File(canonicalRoot, absolute.getPath().substring(rawPrefix.length()));
        else if (absolute.getPath().startsWith(canonicalRoot.getPath() + File.separator)) normalized = absolute;
        else throw new IOException("Captured D2GL directory is outside the private app files root");
        if (!normalized.equals(captured.getCanonicalFile()))
            throw new IOException("Captured D2GL directory contains an imported symbolic link; config kept");
        return normalized;
    }
    private static String installationId(byte[] bytes) throws IOException {
        String id = new String(bytes, StandardCharsets.UTF_8).trim();
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException error) { throw new IOException("Invalid private D2GL installation identity; config kept", error); }
    }
    private static String relative(File files, File game) throws IOException {
        safe(files, game);
        return game.getPath().substring(files.getPath().length() + 1);
    }
    private static void safe(File files, File path) throws IOException {
        File absolute = path.getAbsoluteFile();
        File canonical = path.getCanonicalFile();
        if (!absolute.equals(canonical) || !canonical.getPath().startsWith(files.getPath() + File.separator)
                || Files.isSymbolicLink(path.toPath()))
            throw new IOException("D2GL path is outside private storage or contains a symbolic link; files kept");
    }
    private static byte[] read(File file, int max) throws IOException {
        if (!file.isFile() || file.length() > max) throw new IOException("D2GL config/state file is missing or oversized; files kept");
        long modified = file.lastModified(), length = file.length();
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() > max - count) throw new IOException("D2GL config/state file is oversized; files kept");
                output.write(buffer, 0, count);
            }
            if (file.lastModified() != modified || file.length() != length || output.size() != length)
                throw new IOException("D2GL config changed during inspection; retry after cleanup");
            return output.toByteArray();
        }
    }
    private static void atomic(File files, File target, byte[] data, byte[] expected, int max) throws IOException {
        safe(files, target);
        if (data.length > max) throw new IOException("D2GL transaction exceeds its size limit; files kept");
        File temporary = File.createTempFile(".pd2-d2gl-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(data); output.getFD().sync(); }
            safe(files, target);
            if (expected == null ? Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
                    : !Arrays.equals(expected, read(target, max)))
                throw new IOException("D2GL file changed before atomic replacement; file kept");
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
    }
    static String hash(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder text = new StringBuilder(64);
            for (byte value : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return ((Number)a).longValue() == ((Number)b).longValue();
        return a == b || a != null && a.equals(b);
    }
    private static JSONObject status(int fps, boolean light) {
        return status(fps, light, false);
    }
    private static JSONObject status(int fps, boolean light, boolean classicCursorRequested) {
        return status(fps, light, classicCursorRequested, false);
    }
    private static JSONObject status(int fps, boolean light, boolean classicCursorRequested, boolean simpleScalingRequested) {
        JSONObject object = new JSONObject();
        try { object.put("revision", Pd2D2glPolicy.REVISION).put("sourceCommit", Pd2D2glPolicy.SOURCE_COMMIT)
                .put("scope", "Existing JSON settings only; DLL identity and achieved game FPS are not measured")
                .put("requestedFps", fps).put("lighterEffectsRequested", light)
                .put("classicCursorRequested", classicCursorRequested).put("cursorApplied", false).put("status", "skipped")
                .put("simpleScalingRequested", simpleScalingRequested).put("scalingApplied", false)
                .put("scalingReason", simpleScalingRequested ? "Shader selection has not been prepared"
                        : "Game shader retention or restoration has not been verified")
                .put("schemaRecognized", false).put("configFound", false).put("capApplied", false)
                .put("effectsApplied", false).put("changedFields", 0).put("restoredFields", 0)
                .put("conflictsPreserved", 0).put("userValuesRebased", 0).put("backupAvailable", false)
                .put("fieldChanges", new JSONArray()); }
        catch (JSONException ignored) { }
        return object;
    }
    private static void describe(JSONObject report, Pd2D2glPolicy.Document document, byte[] bytes) throws JSONException {
        report.put("configFound", true).put("schemaRecognized", true).put("configBytes", bytes.length)
                .put("configSha256", hash(bytes)).put("current", document.current());
    }
    private static void applied(JSONObject report, Pd2D2glPolicy.Document document, int fps, boolean light,
                                boolean classicCursorRequested, boolean simpleScalingRequested) throws JSONException {
        report.put("capApplied", fps >= 0 && Boolean.FALSE.equals(document.value(Pd2D2glPolicy.FRAME_KEYS[0]))
                && Boolean.valueOf(fps > 0).equals(document.value(Pd2D2glPolicy.FRAME_KEYS[1]))
                && (fps == 0 || same(document.value(Pd2D2glPolicy.FRAME_KEYS[2]), fps)));
        boolean effects = light && document.lightSchema();
        for (String key : Pd2D2glPolicy.EFFECT_KEYS) effects &= Boolean.FALSE.equals(document.value(key));
        report.put("effectsApplied", effects);
        report.put("cursorApplied", classicCursorRequested && Boolean.FALSE.equals(document.value(Pd2D2glPolicy.CURSOR_KEYS[0])));
        boolean scaling = simpleScalingRequested && Pd2D2glPolicy.SIMPLE_SCALING_PRESET.equals(document.value(Pd2D2glPolicy.SCALING_KEYS[0]));
        report.put("scalingApplied", scaling);
        if (scaling) report.put("scalingReason", "Configured D2GL bilinear preset; archive availability and runtime execution are not measured");
        else if (!simpleScalingRequested) report.put("scalingReason", "Game shader setting retained or restored");
    }
    private static JSONObject unchanged(JSONObject report, String reason) throws JSONException {
        return report.put("status", report.optBoolean("capApplied") || report.optBoolean("effectsApplied")
                || report.optBoolean("cursorApplied") || report.optBoolean("scalingApplied")
                ? "applied" : "unchanged").put("reason", reason);
    }
    private static JSONObject skipped(JSONObject report, String reason) throws JSONException { return report.put("status", "skipped").put("reason", reason); }
    private static JSONObject failure(JSONObject report, Exception error) {
        try {
            String message = error.getMessage() == null ? "D2GL settings could not be safely prepared" : error.getMessage();
            report.put("status", "failed").put("reason", message.substring(0, Math.min(240, message.length())));
        }
        catch (JSONException ignored) { }
        return report;
    }
}
