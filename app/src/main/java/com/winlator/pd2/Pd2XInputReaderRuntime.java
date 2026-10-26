package com.winlator.pd2;

import android.content.Context;

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

/** Exact-image, fresh-launch-only experiment in Wine's 32-bit XInput consumer. */
public final class Pd2XInputReaderRuntime {
    public static final String REVISION = "wine9-xinput-reader-1";
    private static final String ASSET = "pd2/xinput-reader/xinput1_4.dll";
    private static final String MANIFEST = "pd2/xinput-reader/manifest.json";
    private static final String MODULE_PATH = "opt/wine/lib/wine/i386-windows/xinput1_4.dll";
    private static final String BACKUP_PATH = "pd2/runtime/xinput1_4-original.dll";
    private static final String BASE_SHA256 = "c8f692e7fe22fb922886026d97bf39549c8eac12629fee3e06f49078b34a60fa";
    private static final long BASE_BYTES = 49152;
    private static final String PATCH_SHA256 = "5a05fabaaf028749c306b76103451f72215ce69c0605e3876f9beb981f417ce0";
    private static final long PATCH_BYTES = 53760;
    static final int MAX_MODULE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;
    private static final Object INSTALL_LOCK = new Object();

    private Pd2XInputReaderRuntime() {}

    /** The caller captures the mode once, holds the environment lock and has no live guest. */
    public static boolean prepareAfterCleanup(Context context, Pd2WineSession.Result cleanup,
                                              boolean enabled) throws IOException {
        requireCleanup(cleanup);
        synchronized (INSTALL_LOCK) {
            File target = new File(RootFS.find(context).getRootDir().getCanonicalFile(), MODULE_PATH);
            File backup = new File(context.getFilesDir().getCanonicalFile(), BACKUP_PATH);
            if (!enabled) {
                install(target, backup, null, PATCH_BYTES, PATCH_SHA256, BASE_BYTES, BASE_SHA256, false);
                return false;
            }
            try (InputStream input = context.getAssets().open(MANIFEST)) { manifest(input); }
            try (InputStream source = context.getAssets().open(ASSET)) {
                install(target, backup, source, PATCH_BYTES, PATCH_SHA256, BASE_BYTES, BASE_SHA256, true);
            }
            return true;
        }
    }

    static void requireCleanup(Pd2WineSession.Result cleanup) throws IOException {
        if (cleanup == null || !cleanup.passed || !"beforeLaunch".equals(cleanup.phase))
            throw new IOException("XInput reader recovery requires completed Wine cleanup before guest startup");
    }

    /** Reports the captured request separately from the exact installed image; no gameplay claim. */
    public static JSONObject describe(Context context, boolean enabled) {
        JSONObject status = new JSONObject();
        try {
            status.put("revision", REVISION).put("enabledRequested", enabled)
                    .put("loadPath", MODULE_PATH).put("expectedSha256", PATCH_SHA256)
                    .put("baseSha256", BASE_SHA256)
                    .put("scope", "Runtime i386 xinput1_4.dll only; no imported game or prefix files are changed");
            File target = new File(RootFS.find(context).getRootDir().getCanonicalFile(), MODULE_PATH);
            File backup = new File(context.getFilesDir().getCanonicalFile(), BACKUP_PATH);
            String installed = target.exists() ? verifiedHash(target, target.length()) : "missing";
            status.put("installedSha256", installed)
                    .put("installed", PATCH_SHA256.equals(installed) && target.length() == PATCH_BYTES)
                    .put("baselineInstalled", BASE_SHA256.equals(installed) && target.length() == BASE_BYTES);
            status.put("backupVerified", backup.exists() && BASE_SHA256.equals(verifiedHash(backup, BASE_BYTES)));
        } catch (IOException | JSONException | RuntimeException error) {
            try { status.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
            catch (JSONException ignored) {}
        }
        return status;
    }

    static JSONObject manifest(InputStream input) throws IOException {
        try {
            JSONObject manifest = new JSONObject(new String(readBounded(input, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
            if (!REVISION.equals(manifest.optString("revision")) || !ASSET.equals(manifest.optString("asset"))
                    || !"xinput1_4.dll".equals(manifest.optString("dllname"))
                    || !"PE32-I386".equals(manifest.optString("machine"))
                    || !BASE_SHA256.equals(manifest.optString("base_sha256"))
                    || !PATCH_SHA256.equals(manifest.optString("sha256")) || manifest.optLong("bytes") != PATCH_BYTES)
                throw new IOException("Invalid XInput reader recovery manifest");
            return manifest;
        } catch (JSONException error) { throw new IOException("Invalid XInput reader recovery manifest", error); }
    }

    /** Unknown images stay untouched, and an installed experiment always needs its verified donor. */
    static void install(File target, File backup, InputStream source, long size, String expected,
                        long baseSize, String baseline, boolean enabled) throws IOException {
        if (!validSize(size) || !validSize(baseSize) || !isHash(expected) || !isHash(baseline)
                || expected.equals(baseline))
            throw new IOException("Invalid XInput reader image metadata");
        verifyPath(target);
        long installedSize = target.length();
        String installed = verifiedHash(target, installedSize);
        boolean original = installedSize == baseSize && baseline.equals(installed);
        boolean patched = installedSize == size && expected.equals(installed);
        if (!original && !patched)
            throw new IOException("The XInput DLL does not match the accepted Wine runtime; it has been kept");
        // An unused backup cannot block an unchanged, verified standard runtime.
        if (!enabled && original) return;
        verifyPath(backup);
        if (target.getCanonicalFile().equals(backup.getCanonicalFile()))
            throw new IOException("The XInput DLL and original backup must be separate files");
        boolean backupPresent = backup.exists();
        if (backupPresent) requireOriginalBackup(backup, baseSize, baseline);
        if (patched && !backupPresent)
            throw new IOException("The original XInput DLL backup is unavailable; the installed image has been kept");
        if (!enabled) {
            try (InputStream originalInput = new FileInputStream(backup)) {
                replaceVerified(target, originalInput, baseSize, baseline, expected, size, backup, baseSize, baseline);
            }
            return;
        }
        if (patched) return;
        if (!backupPresent) {
            try (InputStream originalInput = new FileInputStream(target)) {
                replaceVerified(backup, originalInput, baseSize, baseline, null, 0, null, 0, null);
            }
        }
        requireOriginalBackup(backup, baseSize, baseline);
        replaceVerified(target, source, size, expected, baseline, baseSize, backup, baseSize, baseline);
    }

    private static void requireOriginalBackup(File backup, long baseSize, String baseline) throws IOException {
        try {
            if (!baseline.equals(verifiedHash(backup, baseSize)))
                throw new IOException("Original XInput DLL digest differs");
        } catch (IOException error) {
            throw new IOException("The original XInput DLL backup cannot be verified; it has been kept", error);
        }
    }

    private static void replaceVerified(File target, InputStream source, long size, String expected,
                                        String previous, long previousSize, File originalBackup,
                                        long baseSize, String baseline) throws IOException {
        verifyPath(target);
        if (source == null) throw new IOException("XInput reader recovery payload is unavailable");
        File parent = target.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create XInput DLL directory");
        verifyPath(parent);
        File staging = File.createTempFile(".pd2-xinput-reader-", ".tmp", parent);
        try {
            byte[] bytes = readBounded(source, (int)size);
            if (bytes.length != size || !expected.equals(digest(bytes)) || !isI386Builtin(bytes))
                throw new IOException("XInput reader recovery image verification failed");
            try (FileOutputStream output = new FileOutputStream(staging)) {
                output.write(bytes);
                output.getFD().sync();
            }
            if (!staging.setReadable(true, false) || !staging.setExecutable(true, false))
                throw new IOException("Cannot set XInput DLL permissions");
            if (!expected.equals(verifiedHash(staging, size)))
                throw new IOException("Staged XInput DLL verification failed");
            if (originalBackup != null) requireOriginalBackup(originalBackup, baseSize, baseline);
            verifyPath(target);
            if (previous == null ? target.exists() : !previous.equals(verifiedHash(target, previousSize)))
                throw new IOException("The XInput DLL changed during installation; it has been kept");
            if (!staging.renameTo(target)) throw new IOException("Cannot install XInput reader recovery image");
            if (!expected.equals(verifiedHash(target, size)))
                throw new IOException("Installed XInput DLL verification failed");
        } finally { staging.delete(); }
    }

    private static void verifyPath(File file) throws IOException {
        File absolute = file.getAbsoluteFile();
        if (Files.isSymbolicLink(absolute.toPath()) || !absolute.equals(absolute.getCanonicalFile()))
            throw new IOException("XInput DLL path contains a symbolic link; it has been kept");
    }

    private static String verifiedHash(File file, long size) throws IOException {
        verifyPath(file);
        if (!validSize(size) || !file.isFile() || file.length() != size)
            throw new IOException("The XInput DLL is missing or differs from its expected size");
        byte[] bytes;
        try (InputStream input = new FileInputStream(file)) { bytes = readBounded(input, (int)size); }
        if (bytes.length != size || !isI386Builtin(bytes))
            throw new IOException("The XInput DLL is not a Wine PE32 i386 builtin image");
        return digest(bytes);
    }

    private static boolean isI386Builtin(byte[] bytes) {
        if (bytes.length < 256 || bytes[0] != 'M' || bytes[1] != 'Z') return false;
        byte[] marker = "Wine builtin DLL\0".getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index < marker.length; index++) if (bytes[64 + index] != marker[index]) return false;
        long offset = unsigned32(bytes, 0x3c);
        if (offset < 64 + marker.length || offset > bytes.length - 120) return false;
        int pe = (int)offset;
        int sections = unsigned16(bytes, pe + 6), optionalSize = unsigned16(bytes, pe + 20);
        return bytes[pe] == 'P' && bytes[pe + 1] == 'E' && bytes[pe + 2] == 0 && bytes[pe + 3] == 0
                && unsigned16(bytes, pe + 4) == 0x014c && unsigned16(bytes, pe + 24) == 0x010b
                && (unsigned16(bytes, pe + 22) & 0x2000) != 0
                && sections > 0 && sections <= 96 && optionalSize >= 96
                && (long)pe + 24 + optionalSize + (long)sections * 40 <= bytes.length;
    }

    private static int unsigned16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8;
    }

    private static long unsigned32(byte[] bytes, int offset) {
        return (long)unsigned16(bytes, offset) | (long)unsigned16(bytes, offset + 2) << 16;
    }

    private static boolean validSize(long size) { return size >= 256 && size <= MAX_MODULE_BYTES; }
    private static boolean isHash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (data.size() + read > limit) throw new IOException("XInput reader recovery data exceeds its size limit");
            data.write(buffer, 0, read);
        }
        return data.toByteArray();
    }

    private static String digest(byte[] bytes) {
        try {
            StringBuilder value = new StringBuilder();
            for (byte item : MessageDigest.getInstance("SHA-256").digest(bytes))
                value.append(String.format(Locale.ROOT, "%02x", item & 255));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
