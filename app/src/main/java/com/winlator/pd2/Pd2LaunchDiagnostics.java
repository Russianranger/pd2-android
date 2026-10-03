package com.winlator.pd2;

import android.content.Context;
import androidx.preference.PreferenceManager;
import com.winlator.container.Container;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

/** Small runtime reports and PE headers, without exporting game binaries or save data. */
public final class Pd2LaunchDiagnostics {
    private Pd2LaunchDiagnostics() {}

    public static String begin(Context context, Container container, String arguments) throws IOException, JSONException {
        File logs = logs(context);
        if (!logs.isDirectory() && !logs.mkdirs()) throw new IOException("Cannot create diagnostic directory");
        Pd2SessionLog.archivePrevious(context);
        String id = UUID.randomUUID().toString();
        JSONObject report = new JSONObject().put("launchId", id).put("startedAt", System.currentTimeMillis())
                .put("renderer", container.getGraphicsDriver()).put("arguments", arguments)
                .put("cpuPreset", container.getBox64Preset()).put("environment", container.getEnvVars())
                .put("interpreter", PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(Pd2LaunchPolicy.CPU_PREFERENCE, false));
        write(new File(logs, "launch.json"), report);
        return id;
    }

    public static void exited(Context context, String id, int status) {
        try {
            File reportFile = new File(logs(context), "launch.json");
            if (!reportFile.isFile() || reportFile.length() > 128 * 1024) return;
            byte[] bytes = new byte[(int)reportFile.length()];
            try (RandomAccessFile in = new RandomAccessFile(reportFile, "r")) { in.readFully(bytes); }
            JSONObject report = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!id.equals(report.optString("launchId"))) return;
            report.put("runtimeExitedAt", System.currentTimeMillis()).put("runtimeExitStatus", status);
            write(reportFile, report);
        } catch (IOException | JSONException ignored) { }
    }

    public static JSONObject installationFiles(File installed, Pd2InstallValidator.Result installation) throws JSONException {
        JSONArray files = new JSONArray();
        File base = installation.clientRootRelativePath.isEmpty() ? installed : new File(installed, installation.clientRootRelativePath);
        File game = new File(installed, installation.gameExecutableRelativePath).getParentFile();
        inventory(installed, base, files);
        if (!game.equals(base)) inventory(installed, game, files);
        return new JSONObject().put("scope", "Top-level base and ProjectD2 files; PE headers only, no game contents")
                .put("maxFilesPerDirectory", 256).put("files", files);
    }

    private static void inventory(File installed, File directory, JSONArray result) throws JSONException {
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        int count = 0;
        for (File file : files) {
            if (!file.isFile() || count++ >= 256) continue;
            JSONObject entry = new JSONObject().put("path", installed.toURI().relativize(file.toURI()).getPath())
                    .put("bytes", file.length());
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.endsWith(".exe") || name.endsWith(".dll")) {
                try { entry.put("pe", peHeader(file)); }
                catch (IOException e) { entry.put("headerError", e.getMessage()); }
            }
            result.put(entry);
        }
    }

    static JSONObject peHeader(File file) throws IOException, JSONException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 64 || in.readUnsignedByte() != 'M' || in.readUnsignedByte() != 'Z')
                throw new IOException("Not an MZ executable");
            in.seek(60);
            long offset = Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
            if (offset < 64 || offset > 1024 * 1024 || offset > in.length() - 84)
                throw new IOException("Invalid PE header offset");
            in.seek(offset);
            if (in.readInt() != 0x50450000) throw new IOException("Missing PE signature");
            int machine = Short.toUnsignedInt(Short.reverseBytes(in.readShort()));
            in.seek(offset + 20);
            int optionalSize = Short.toUnsignedInt(Short.reverseBytes(in.readShort()));
            if (optionalSize < 64 || offset + 24 + optionalSize > in.length())
                throw new IOException("Truncated optional header");
            in.seek(offset + 24);
            int magic = Short.toUnsignedInt(Short.reverseBytes(in.readShort()));
            if (magic != 0x10b && magic != 0x20b) throw new IOException("Unsupported PE format");
            in.seek(offset + (magic == 0x10b ? 52 : 48));
            long base = magic == 0x10b ? Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()))
                    : Long.reverseBytes(in.readLong());
            in.seek(offset + 80);
            long size = Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
            return new JSONObject().put("machine", "0x" + Integer.toHexString(machine))
                    .put("format", magic == 0x10b ? "PE32" : "PE32+")
                    .put("preferredImageBase", "0x" + Long.toHexString(base)).put("sizeOfImage", size);
        }
    }

    private static File logs(Context context) { return new File(context.getFilesDir(), "pd2/logs"); }

    private static void write(File destination, JSONObject report) throws IOException {
        File temp = new File(destination.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(report.toString().getBytes(StandardCharsets.UTF_8)); }
        if (!temp.renameTo(destination)) { temp.delete(); throw new IOException("Cannot save launch diagnostics"); }
    }
}
