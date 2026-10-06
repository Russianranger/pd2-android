package com.winlator.pd2;

import android.content.Context;

import androidx.preference.PreferenceManager;

import com.winlator.xenvironment.RootFS;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Opt-in, exact-image HID read cancellation backport; never modifies a live Wine session. */
public final class Pd2HidReadRuntime {
    public static final String ENABLED_PREFERENCE = "pd2_hid_read_recovery";
    public static final String REVISION = "wine9-hid-cancel-1";
    private static final String ASSET = "pd2/hid-read/hidclass.sys";
    private static final String MANIFEST = "pd2/hid-read/manifest.json";
    private static final String MODULE_PATH = "opt/wine/lib/wine/x86_64-windows/hidclass.sys";
    private static final String BASE_SHA256 = "a335d3560f14d5b1e31f90fd765ee6261f43a4d70a1f456fbec805ccf18132bc";
    private static final String PATCH_SHA256 = "def30d1b2b6ada06c0ce04d96a8055c67b2f07f2d10f622960ad259f75a64dcb";
    private static final long MODULE_BYTES = 65536;
    static final int MAX_MODULE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;
    private static final Object INSTALL_LOCK = new Object();

    private Pd2HidReadRuntime() { }

    public static boolean enabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ENABLED_PREFERENCE, false);
    }

    /** The caller holds the existing environment startup lock and has not started guest processes. */
    public static boolean prepareAfterCleanup(Context context, Pd2WineSession.Result cleanup) throws IOException {
        requireCleanup(cleanup);
        synchronized (INSTALL_LOCK) {
            boolean requested = enabled(context);
            JSONObject manifest;
            try (InputStream input = context.getAssets().open(MANIFEST)) { manifest = manifest(input); }
            File target = new File(RootFS.find(context).getRootDir().getCanonicalFile(), MODULE_PATH);
            File backup = new File(context.getFilesDir().getCanonicalFile(), "pd2/runtime/hidclass-original.sys");
            try (InputStream source = context.getAssets().open(ASSET)) {
                install(target, backup, source, manifest.optLong("bytes"), manifest.optString("sha256"),
                        BASE_SHA256, requested);
            }
            return requested;
        }
    }

    static void requireCleanup(Pd2WineSession.Result cleanup) throws IOException {
        if (cleanup == null || !cleanup.passed || !"beforeLaunch".equals(cleanup.phase))
            throw new IOException("HID read recovery requires completed Wine cleanup before guest startup");
    }

    public static JSONObject status(Context context) {
        JSONObject status = new JSONObject();
        try {
            boolean requested = enabled(context);
            File target = new File(RootFS.find(context).getRootDir().getCanonicalFile(), MODULE_PATH);
            File backup = new File(context.getFilesDir().getCanonicalFile(), "pd2/runtime/hidclass-original.sys");
            status.put("revision", REVISION).put("enabled", requested).put("enabledRequested", requested)
                    .put("loadPath", MODULE_PATH).put("expectedSha256", PATCH_SHA256).put("baseSha256", BASE_SHA256)
                    .put("scope", "Runtime AMD64 hidclass.sys; no imported game or prefix files are changed");
            String installed = target.exists() ? verifiedHash(target, MODULE_BYTES) : "missing";
            status.put("installedSha256", installed).put("installed", PATCH_SHA256.equals(installed))
                    .put("baselineInstalled", BASE_SHA256.equals(installed));
            status.put("backupVerified", backup.exists() && BASE_SHA256.equals(verifiedHash(backup, MODULE_BYTES)));
        } catch (IOException | JSONException | RuntimeException error) {
            try { status.put("error", error.getClass().getSimpleName()); }
            catch (JSONException ignored) { }
        }
        return status;
    }

    static JSONObject manifest(InputStream input) throws IOException {
        try {
            JSONObject manifest = new JSONObject(new String(readBounded(input, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
            if (!REVISION.equals(manifest.optString("revision")) || !ASSET.equals(manifest.optString("asset"))
                    || !"hidclass.sys".equals(manifest.optString("dllname"))
                    || !"PE32+-AMD64".equals(manifest.optString("machine"))
                    || !BASE_SHA256.equals(manifest.optString("base_sha256"))
                    || !PATCH_SHA256.equals(manifest.optString("sha256")) || manifest.optLong("bytes") != MODULE_BYTES)
                throw new IOException("Invalid HID read recovery manifest");
            return manifest;
        } catch (JSONException error) { throw new IOException("Invalid HID read recovery manifest", error); }
    }

    /** Unknown runtime images are preserved. An active patch always requires its verified original. */
    static void install(File target, File backup, InputStream source, long size, String expected,
                        String baseline, boolean enable) throws IOException {
        if (size < 256 || size > MAX_MODULE_BYTES || !isHash(expected) || !isHash(baseline))
            throw new IOException("Invalid HID read recovery image metadata");
        verifyPath(target);
        String installed = verifiedHash(target, size);
        if (!installed.equals(baseline) && !installed.equals(expected))
            throw new IOException("The HID driver does not match the accepted Wine runtime; it has been kept");
        // An unused backup cannot block the unchanged, known baseline when the experiment is off.
        if (!enable && installed.equals(baseline)) return;
        verifyPath(backup);
        boolean backupPresent = backup.exists();
        if (backupPresent && !baseline.equals(verifiedHash(backup, size)))
            throw new IOException("The original HID driver backup cannot be verified; it has been kept");
        if (installed.equals(expected) && !backupPresent)
            throw new IOException("The original HID driver backup is unavailable");
        if (!enable) {
            try (InputStream original = new FileInputStream(backup)) {
                replaceVerified(target, original, size, baseline, expected);
            }
            return;
        }
        if (installed.equals(expected)) return;
        if (!backupPresent) {
            try (InputStream original = new FileInputStream(target)) {
                replaceVerified(backup, original, size, baseline, null);
            }
        }
        // Recheck the persisted original before publishing even the first replacement.
        if (!baseline.equals(verifiedHash(backup, size)))
            throw new IOException("The original HID driver backup verification failed");
        replaceVerified(target, source, size, expected, baseline);
    }

    private static void replaceVerified(File target, InputStream source, long size, String expected,
                                        String previous) throws IOException {
        verifyPath(target);
        File parent = target.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create HID driver directory");
        verifyPath(parent);
        File staging = File.createTempFile(".pd2-hid-read-", ".tmp", parent);
        try {
            byte[] bytes = readBounded(source, (int)size);
            if (bytes.length != size || !expected.equals(digest(bytes)) || !isAmd64Driver(bytes))
                throw new IOException("HID read recovery image verification failed");
            try (FileOutputStream output = new FileOutputStream(staging)) {
                output.write(bytes);
                output.getFD().sync();
            }
            if (!staging.setReadable(true, false) || !staging.setExecutable(true, false))
                throw new IOException("Cannot set HID driver permissions");
            if (!expected.equals(verifiedHash(staging, size)))
                throw new IOException("Staged HID driver verification failed");
            verifyPath(target);
            if (previous == null ? target.exists() : !previous.equals(verifiedHash(target, size)))
                throw new IOException("HID driver changed during installation; it has been kept");
            if (!staging.renameTo(target)) throw new IOException("Cannot install HID read recovery image");
            if (!expected.equals(verifiedHash(target, size)))
                throw new IOException("Installed HID driver verification failed");
        } finally { staging.delete(); }
    }

    private static void verifyPath(File file) throws IOException {
        File absolute = file.getAbsoluteFile();
        if (Files.isSymbolicLink(absolute.toPath()) || !absolute.equals(absolute.getCanonicalFile()))
            throw new IOException("HID driver path contains a symbolic link; it has been kept");
    }

    private static String verifiedHash(File file, long size) throws IOException {
        verifyPath(file);
        if (!file.isFile() || size > MAX_MODULE_BYTES || file.length() != size)
            throw new IOException("The HID driver is missing or exceeds its size limit");
        byte[] bytes;
        try (InputStream input = new FileInputStream(file)) { bytes = readBounded(input, MAX_MODULE_BYTES); }
        if (bytes.length != size || !isAmd64Driver(bytes)) throw new IOException("The HID driver is not a PE AMD64 image");
        return digest(bytes);
    }

    private static boolean isAmd64Driver(byte[] bytes) {
        if (bytes.length < 256 || bytes[0] != 'M' || bytes[1] != 'Z') return false;
        long offset = unsigned32(bytes, 0x3c);
        if (offset < 64 || offset > bytes.length - 136) return false;
        int pe = (int)offset;
        int sections = unsigned16(bytes, pe + 6), optionalSize = unsigned16(bytes, pe + 20);
        return bytes[pe] == 'P' && bytes[pe + 1] == 'E' && bytes[pe + 2] == 0 && bytes[pe + 3] == 0
                && unsigned16(bytes, pe + 4) == 0x8664 && unsigned16(bytes, pe + 24) == 0x20b
                && sections > 0 && sections <= 96 && optionalSize >= 112
                && (long)pe + 24 + optionalSize + (long)sections * 40 <= bytes.length;
    }

    private static int unsigned16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8;
    }

    private static long unsigned32(byte[] bytes, int offset) {
        return (long)unsigned16(bytes, offset) | (long)unsigned16(bytes, offset + 2) << 16;
    }

    private static byte[] readBounded(InputStream source, int limit) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = source.read(buffer)) != -1) {
            if (data.size() + read > limit) throw new IOException("HID read recovery data exceeds its size limit");
            data.write(buffer, 0, read);
        }
        return data.toByteArray();
    }

    private static boolean isHash(String hash) { return hash != null && hash.matches("[a-f0-9]{64}"); }

    private static String digest(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
                hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
