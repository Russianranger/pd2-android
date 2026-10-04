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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Install only the matching Wine 9 HID backend before the Windows session starts. */
public final class Pd2ControllerRuntime {
    public static final String ENABLED_PREFERENCE = "pd2_controller_notifications";
    public static final String REVISION = "wine9-hid-1";
    private static final String ASSET = "pd2/controller/winebus.so";
    private static final String MANIFEST = "pd2/controller/manifest.json";
    private static final String BASE_SHA256 = "4ca5b1dd5f2d56ae9ca3090f356b700f4e5282b6915a195719d0a1ab66128117";
    private static final String MODULE_PATH = "opt/wine/lib/wine/x86_64-unix/winebus.so";
    private static final int MAX_MODULE_BYTES = 2 * 1024 * 1024;
    private static final Object INSTALL_LOCK = new Object();

    private Pd2ControllerRuntime() { }

    public static boolean enabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(ENABLED_PREFERENCE, true);
    }

    public static void prepare(Context context) throws IOException {
        synchronized (INSTALL_LOCK) {
            JSONObject manifest = manifest(context);
            File target = new File(RootFS.find(context).getRootDir(), MODULE_PATH);
            File backup = new File(context.getFilesDir(), "pd2/runtime/winebus-original.so");
            try (InputStream source = context.getAssets().open(ASSET)) {
                install(target, backup, source, manifest.optLong("bytes"), manifest.optString("sha256"),
                        BASE_SHA256, enabled(context));
            }
        }
    }

    public static JSONObject status(Context context) {
        JSONObject status = new JSONObject();
        try {
            JSONObject manifest = manifest(context);
            File target = new File(RootFS.find(context).getRootDir(), MODULE_PATH);
            String installed = target.isFile() ? digest(target) : "missing";
            status.put("revision", REVISION).put("enabled", enabled(context))
                    .put("installed", installed.equals(manifest.optString("sha256")))
                    .put("installedSha256", installed).put("expectedSha256", manifest.optString("sha256"));
        }
        catch (IOException | JSONException | RuntimeException error) {
            try { status.put("error", error.getClass().getSimpleName()); }
            catch (JSONException ignored) { }
        }
        return status;
    }

    private static JSONObject manifest(Context context) throws IOException {
        try (InputStream input = context.getAssets().open(MANIFEST)) {
            JSONObject manifest = new JSONObject(new String(readBounded(input, 16 * 1024), StandardCharsets.UTF_8));
            long size = manifest.optLong("bytes");
            if (!REVISION.equals(manifest.optString("revision")) || !ASSET.equals(manifest.optString("asset"))
                    || !BASE_SHA256.equals(manifest.optString("base_sha256")) || size < 24 || size > MAX_MODULE_BYTES
                    || !manifest.optString("sha256").matches("[a-f0-9]{64}"))
                throw new IOException("Invalid controller backend manifest");
            return manifest;
        }
        catch (JSONException error) { throw new IOException("Invalid controller backend manifest", error); }
    }

    /** Verify each staged file before replacing the live module; preserve unknown runtime files. */
    static void install(File target, File backup, InputStream source, long size, String expected,
                        String baseline, boolean enable) throws IOException {
        if (!target.isFile()) throw new IOException("The Wine controller backend is missing");
        String installed = digest(target);
        if (!installed.equals(baseline) && !installed.equals(expected))
            throw new IOException("The controller backend does not match this Wine runtime");
        if (!enable) {
            if (installed.equals(baseline)) return;
            if (!backup.isFile() || !digest(backup).equals(baseline))
                throw new IOException("The original controller backend backup is unavailable");
            try (InputStream original = new FileInputStream(backup)) {
                replaceVerified(target, original, backup.length(), baseline);
            }
            return;
        }
        if (installed.equals(expected)) return;
        // The verified original is saved before publishing a replacement.
        if (!backup.isFile() || !digest(backup).equals(baseline)) {
            try (InputStream original = new FileInputStream(target)) {
                replaceVerified(backup, original, target.length(), baseline);
            }
        }
        replaceVerified(target, source, size, expected);
    }

    private static void replaceVerified(File target, InputStream source, long size, String expected) throws IOException {
        if (size < 24 || size > MAX_MODULE_BYTES) throw new IOException("Invalid controller backend size");
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create controller backend directory");
        File staging = File.createTempFile(".pd2-controller-", ".tmp", parent);
        try {
            byte[] bytes = readBounded(source, (int)size);
            if (bytes.length != size || !digest(bytes).equals(expected) || !isWineUnixModule(bytes))
                throw new IOException("Controller backend verification failed");
            try (FileOutputStream output = new FileOutputStream(staging)) {
                output.write(bytes);
                output.getFD().sync();
            }
            if (!staging.setReadable(true, false) || !staging.setExecutable(true, false))
                throw new IOException("Cannot set controller backend permissions");
            if (!staging.renameTo(target)) throw new IOException("Cannot install controller backend");
        }
        finally { staging.delete(); }
    }

    private static boolean isWineUnixModule(byte[] bytes) {
        return bytes.length >= 24 && bytes[0] == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F'
                && bytes[4] == 2 && bytes[5] == 1 && bytes[16] == 3 && bytes[17] == 0
                && bytes[18] == 62 && bytes[19] == 0;
    }

    private static byte[] readBounded(InputStream source, int limit) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int read;
        while ((read = source.read(buffer)) != -1) {
            if (data.size() + read > limit) throw new IOException("Controller backend exceeds its size limit");
            data.write(buffer, 0, read);
        }
        return data.toByteArray();
    }

    private static String digest(File file) throws IOException {
        if (file.length() > MAX_MODULE_BYTES) throw new IOException("Controller backend exceeds its size limit");
        try (InputStream input = new FileInputStream(file)) { return digest(readBounded(input, MAX_MODULE_BYTES)); }
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
