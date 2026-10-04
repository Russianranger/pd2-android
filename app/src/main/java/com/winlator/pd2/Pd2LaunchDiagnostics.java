package com.winlator.pd2;

import android.content.Context;
import androidx.preference.PreferenceManager;
import com.winlator.container.Container;
import com.winlator.xenvironment.RootFS;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Bounded runtime reports, PE metadata and text logs, without game binaries or save data. */
public final class Pd2LaunchDiagnostics {
    static final int MAX_HASH_BYTES = 8 * 1024 * 1024;
    static final int MAX_GAME_LOG_BYTES = 256 * 1024;
    private static final byte[] GAME_LOG_TRUNCATED =
            "\n\n[PD2 game log truncated: middle omitted; first and last sections preserved]\n\n"
                    .getBytes(StandardCharsets.UTF_8);
    private Pd2LaunchDiagnostics() {}

    public static String begin(Context context, Container container, String arguments) throws IOException, JSONException {
        File logs = logs(context);
        if (!logs.isDirectory() && !logs.mkdirs()) throw new IOException("Cannot create diagnostic directory");
        Pd2SessionLog.archivePrevious(context);
        String id = UUID.randomUUID().toString();
        JSONObject report = new JSONObject().put("launchId", id).put("startedAt", System.currentTimeMillis())
                .put("controllerRuntime", Pd2ControllerRuntime.status(context))
                .put("renderer", container.getGraphicsDriver()).put("arguments", arguments)
                .put("cpuPreset", container.getBox64Preset()).put("environment", container.getEnvVars())
                .put("wineVersion", container.getWineVersion()).put("winePath", "/opt/wine")
                .put("runtimeRevision", Pd2Runtime.RUNTIME_REVISION)
                .put("prefixRevision", container.getExtra("pd2RuntimeRevision"))
                .put("rootfsVersion", RootFS.find(context).getVersion())
                .put("registry", Pd2Runtime.registrySnapshot(container))
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
        return new JSONObject().put("scope", "Top-level base and ProjectD2 files; PE headers and selected core-file SHA-256 hashes, no binaries or saves")
                .put("maxFilesPerDirectory", 256).put("maxHashBytes", MAX_HASH_BYTES).put("files", files);
    }

    private static void inventory(File installed, File directory, JSONArray result) throws JSONException {
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        int count = 0;
        for (File file : files) {
            if (!safeFile(installed, directory, file) || count++ >= 256) continue;
            JSONObject entry = new JSONObject().put("path", installed.toURI().relativize(file.toURI()).getPath())
                    .put("bytes", file.length());
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".exe") || name.endsWith(".dll")) {
                try { entry.put("pe", peHeader(file)); }
                catch (IOException e) { entry.put("headerError", e.getMessage()); }
            }
            if (name.equals("game.exe") || name.equals("fog.dll") || name.equals("pd2_ext.dll")) {
                try { entry.put("sha256", coreFileSha256(file)); }
                catch (IOException e) { entry.put("hashError", e.getMessage()); }
            }
            result.put(entry);
        }
    }

    /** Bounded, read-only text-log snapshots from the accepted game's directory. */
    public static void exportGameLogs(File installed, Pd2InstallValidator.Result installation,
                                      ZipOutputStream zip) throws IOException {
        if (installation == null || !installation.valid) return;
        File directory = new File(installed, installation.gameExecutableRelativePath).getParentFile();
        File[] children = directory.listFiles();
        if (children == null) return;
        List<File> dated = new ArrayList<>();
        List<File> wrapper = new ArrayList<>();
        Set<String> wrapperNames = new HashSet<>();
        for (File file : children) {
            if (!safeFile(installed, directory, file)) continue;
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (name.matches("d2[0-9]{6,8}\\.txt")) dated.add(file);
            else if ((name.equals("d2dx_log.txt") || name.equals("d2gl.log")) && wrapperNames.add(name)) wrapper.add(file);
        }
        dated.sort(Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName));
        wrapper.sort(Comparator.comparing(File::getName));
        List<File> selected = new ArrayList<>(dated.subList(0, Math.min(2, dated.size())));
        selected.addAll(wrapper);
        for (File file : selected) {
            // Recheck after selection, before opening, so stale links are not deliberately followed.
            if (!safeFile(installed, directory, file)) continue;
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                long bytes = input.length();
                zip.putNextEntry(new ZipEntry("game-logs/" + file.getName()));
                try {
                    if (bytes <= MAX_GAME_LOG_BYTES) copy(input, zip, bytes);
                    else {
                        int budget = MAX_GAME_LOG_BYTES - GAME_LOG_TRUNCATED.length;
                        int head = budget / 2;
                        copy(input, zip, head);
                        zip.write(GAME_LOG_TRUNCATED);
                        input.seek(bytes - (budget - head));
                        copy(input, zip, budget - head);
                    }
                } finally { zip.closeEntry(); }
            }
        }
    }

    private static boolean safeFile(File installed, File directory, File file) {
        try {
            File root = installed.getCanonicalFile();
            File parent = directory.getCanonicalFile();
            String boundary = root.getPath() + File.separator;
            return (parent.equals(root) || parent.getPath().startsWith(boundary))
                    && !Files.isSymbolicLink(file.toPath()) && file.isFile()
                    && file.getCanonicalFile().getParentFile().equals(parent);
        } catch (IOException | SecurityException ignored) { return false; }
    }

    static String coreFileSha256(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long bytes = input.length();
            if (bytes > MAX_HASH_BYTES) throw new IOException("File exceeds 8 MiB hash limit");
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IOException("SHA-256 unavailable"); }
            byte[] buffer = new byte[64 * 1024];
            long remaining = bytes;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                if (read < 0) throw new IOException("File changed while hashing");
                digest.update(buffer, 0, read);
                remaining -= read;
            }
            if (input.length() != bytes) throw new IOException("File changed while hashing");
            StringBuilder hash = new StringBuilder(64);
            for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hash.toString();
        } catch (IOException e) {
            String message = e.getMessage();
            if ("File exceeds 8 MiB hash limit".equals(message) || "File changed while hashing".equals(message)
                    || "SHA-256 unavailable".equals(message)) throw e;
            throw new IOException("Cannot read core file for hashing");
        } catch (SecurityException e) { throw new IOException("Cannot read core file for hashing"); }
    }

    private static void copy(RandomAccessFile input, ZipOutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Game log changed while exporting");
            output.write(buffer, 0, read);
            remaining -= read;
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
            if (optionalSize < (magic == 0x10b ? 80 : 88))
                throw new IOException("Truncated stack fields in optional header");
            in.seek(offset + (magic == 0x10b ? 52 : 48));
            long base = magic == 0x10b ? Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()))
                    : Long.reverseBytes(in.readLong());
            in.seek(offset + 80);
            long size = Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
            in.seek(offset + 96);
            long reserve = magic == 0x10b ? Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()))
                    : Long.reverseBytes(in.readLong());
            long commit = magic == 0x10b ? Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()))
                    : Long.reverseBytes(in.readLong());
            return new JSONObject().put("machine", "0x" + Integer.toHexString(machine))
                    .put("format", magic == 0x10b ? "PE32" : "PE32+")
                    .put("preferredImageBase", "0x" + Long.toHexString(base)).put("sizeOfImage", size)
                    .put("stackReserveBytes", reserve >= 0 ? reserve : Long.toUnsignedString(reserve))
                    .put("stackCommitBytes", commit >= 0 ? commit : Long.toUnsignedString(commit));
        }
    }

    private static File logs(Context context) { return new File(context.getFilesDir(), "pd2/logs"); }

    private static void write(File destination, JSONObject report) throws IOException {
        File temp = new File(destination.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(report.toString().getBytes(StandardCharsets.UTF_8)); }
        if (!temp.renameTo(destination)) { temp.delete(); throw new IOException("Cannot save launch diagnostics"); }
    }
}
