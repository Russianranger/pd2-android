package com.winlator.pd2;

import android.content.Context;
import com.winlator.core.EnvVars;
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
import java.util.Arrays;
import java.util.Locale;

/** Explicit Eco selection; the normal ALSA module, configuration and Wine stay intact. */
public final class Pd2AudioRuntime {
    public static final String PREFERENCE = "pd2_audio_eco";
    public static final String REVISION = "alsa-eco-1";
    public static final String MODULE = "libasound_module_pcm_pd2_eco.so";
    public static final int MODULE_BYTES = 71448;
    public static final String MODULE_SHA256 = "c3c344f2f8740aaca3db6ba6216b6908dcceb367358cdca31e1907ec7043a3bd";
    private static final String DIRECTORY = "pd2/runtime/audio";
    private static final String CONFIG = "alsa-eco.conf";
    private static final String ENVIRONMENT = "ALSA_CONFIG_PATH";

    private Pd2AudioRuntime() {}

    /** Must run under the captured Wine session's preflight lease, before its guest starts. */
    public static synchronized JSONObject prepareAfterCleanup(Context context, Pd2WineSession.Result cleanup,
                                                              boolean requested, EnvVars launchEnvironment)
            throws IOException {
        requireCleanup(cleanup);
        if (launchEnvironment == null) throw new IOException("Audio launch environment is missing");
        File files = context.getFilesDir().getCanonicalFile();
        File config = new File(files, DIRECTORY + "/" + CONFIG);
        if (!requested) {
            // An unrelated explicitly supplied ALSA configuration is not ours to remove.
            if (config.getPath().equals(launchEnvironment.get(ENVIRONMENT))) launchEnvironment.remove(ENVIRONMENT);
            return report(false, false);
        }
        try (InputStream source = context.getAssets().open("pd2/audio/" + MODULE)) {
            install(files, source, launchEnvironment);
        }
        return report(true, true);
    }

    static void requireCleanup(Pd2WineSession.Result cleanup) throws IOException {
        if (cleanup == null || !cleanup.passed || !"beforeLaunch".equals(cleanup.phase))
            throw new IOException("Eco audio requires successful before-launch Wine cleanup");
    }

    static void install(File filesDirectory, InputStream source, EnvVars environment) throws IOException {
        File files = filesDirectory.getCanonicalFile();
        File directory = new File(files, DIRECTORY);
        File module = new File(directory, MODULE);
        File config = new File(directory, CONFIG);
        byte[] image = readBounded(source, MODULE_BYTES);
        if (image.length != MODULE_BYTES || !MODULE_SHA256.equals(digest(image)) || !arm64SharedObject(image))
            throw new IOException("Eco audio module verification failed");
        byte[] configuration = configuration(module);
        // Validate both destinations before creating either. Existing unknown files remain intact.
        requirePath(directory);
        requireExactOrAbsent(module, image);
        requireExactOrAbsent(config, configuration);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private audio directory");
        requirePath(directory);
        installExact(module, image, true);
        installExact(config, configuration, false);
        requireExactOrAbsent(module, image);
        requireExactOrAbsent(config, configuration);
        if (!module.isFile() || !config.isFile()) throw new IOException("Eco audio installation is incomplete");
        // Publish the route only after both immutable files have been verified.
        environment.put(ENVIRONMENT, config.getPath());
    }

    private static JSONObject report(boolean requested, boolean enabled) throws IOException {
        try {
            return new JSONObject().put("revision", REVISION).put("requested", requested)
                    .put("enabled", enabled).put("installedVerified", enabled)
                    .put("moduleSha256", enabled ? MODULE_SHA256 : "")
                    .put("requestedMixerRateHz", enabled ? 22050 : 0)
                    .put("originalPluginPreserved", true)
                    .put("scope", "Eco PCM selection and asset verification; actual negotiated rate and underruns are in audio diagnostics. No game, Wine or original ALSA files are replaced.");
        } catch (JSONException error) { throw new IOException("Cannot record audio runtime status", error); }
    }

    static byte[] configuration(File module) throws IOException {
        String path = module.getPath();
        if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\0') >= 0)
            throw new IOException("Invalid private audio path");
        path = path.replace("\\", "\\\\").replace("\"", "\\\"");
        return ("pcm_type.pd2_eco { lib \"" + path + "\" }\n"
                + "pcm.!default { type pd2_eco }\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void installExact(File target, byte[] bytes, boolean executable) throws IOException {
        requireExactOrAbsent(target, bytes);
        if (target.exists()) return;
        File staging = File.createTempFile(".pd2-audio-", ".tmp", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(staging)) {
                output.write(bytes);
                output.getFD().sync();
            }
            if (!staging.setReadable(true, true) || executable && !staging.setExecutable(true, true))
                throw new IOException("Cannot set private audio file permissions");
            requireExactOrAbsent(staging, bytes);
            requirePath(target);
            // An atomic no-replace hard link cannot overwrite a concurrently created file.
            // Both names live on the same private app filesystem; staging is removed below.
            try { Files.createLink(target.toPath(), staging.toPath()); }
            catch (java.nio.file.FileAlreadyExistsException raced) { requireExactOrAbsent(target, bytes); }
            requireExactOrAbsent(target, bytes);
        } finally { staging.delete(); }
    }

    private static void requireExactOrAbsent(File file, byte[] expected) throws IOException {
        requirePath(file);
        if (!file.exists()) return;
        if (!file.isFile() || file.length() != expected.length)
            throw new IOException("Unknown private audio file preserved: " + file.getName());
        try (InputStream input = new FileInputStream(file)) {
            if (!Arrays.equals(expected, readBounded(input, expected.length)))
                throw new IOException("Unknown private audio file preserved: " + file.getName());
        }
    }

    private static void requirePath(File file) throws IOException {
        File absolute = file.getAbsoluteFile();
        if (Files.isSymbolicLink(absolute.toPath()) || !absolute.equals(absolute.getCanonicalFile()))
            throw new IOException("Private audio path contains a symbolic link; it has been preserved");
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        if (input == null) throw new IOException("Eco audio payload is missing");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] block = new byte[8192];
        int count;
        while ((count = input.read(block, 0, Math.min(block.length, limit + 1 - output.size()))) != -1) {
            if (count == 0) {
                int next = input.read();
                if (next < 0) break;
                output.write(next);
            } else output.write(block, 0, count);
            if (output.size() > limit) throw new IOException("Eco audio file exceeds its verified size");
        }
        return output.toByteArray();
    }

    private static boolean arm64SharedObject(byte[] bytes) {
        return bytes.length > 64 && bytes[0] == 0x7f && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F'
                && bytes[4] == 2 && bytes[5] == 1 && bytes[16] == 3 && bytes[17] == 0
                && (bytes[18] & 0xff) == 183 && bytes[19] == 0;
    }

    private static String digest(byte[] bytes) throws IOException {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 is unavailable", impossible); }
    }
}
