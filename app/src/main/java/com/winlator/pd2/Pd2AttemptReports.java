package com.winlator.pd2;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Keep bounded, generation-matched reports with the previous runtime attempt. */
public final class Pd2AttemptReports {
    static final int MAX_LAUNCH_BYTES = 128 * 1024;
    static final int MAX_CONTROLLER_BYTES = 64 * 1024;
    static final int MAX_MEMORY_BYTES = 512 * 1024;
    private static final int MAX_DIRECTORY_ENTRIES = 256;
    private static final String RUNTIME_PATTERN = "runtime-attempt-[0-9]{13,19}\\.log";
    private static final String REPORT_PATTERN = "(?:launch|controller|memory)-attempt-[0-9]{13,19}\\.json";
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String[] TYPES = {"launch", "controller", "memory"};
    private static final int[] LIMITS = {MAX_LAUNCH_BYTES, MAX_CONTROLLER_BYTES, MAX_MEMORY_BYTES};

    private Pd2AttemptReports() {}

    /** Called before launch.json is replaced and before the controller report is cleared. */
    public static void archivePrevious(File filesDirectory, File runtimeArchive) throws IOException {
        synchronized (Pd2SessionLog.LOCK) {
            File logs = safeLogsDirectory(filesDirectory);
            File directory = safeAttemptsDirectory(logs);
            if (directory == null) return;
            pruneReports(directory);
            if (runtimeArchive == null || !runtimeArchive.getName().matches(RUNTIME_PATTERN)
                    || !safeFile(directory, runtimeArchive)) return;
            byte[] launch = readBounded(new File(logs, "launch.json"), logs, MAX_LAUNCH_BYTES);
            String id = launchId(launch);
            if (id == null) return;
            String stamp = stamp(runtimeArchive);
            byte[][] reports = new byte[TYPES.length][];
            reports[0] = launch;
            for (int index = 1; index < TYPES.length; index++) {
                byte[] report = readBounded(new File(logs, TYPES[index] + ".json"), logs, LIMITS[index]);
                if (id.equals(launchId(report))) reports[index] = report;
            }
            for (int index = 0; index < TYPES.length; index++) {
                if (reports[index] != null) publish(directory,
                        TYPES[index] + "-attempt-" + stamp + ".json", reports[index]);
            }
        }
    }

    /** Export at most four runtime attempts and their validated companion reports. */
    public static void exportArchived(File filesDirectory, ZipOutputStream zip) throws IOException {
        synchronized (Pd2SessionLog.LOCK) {
            File directory = safeAttemptsDirectory(safeLogsDirectory(filesDirectory));
            if (directory == null) return;
            for (File runtime : runtimeArchives(directory)) {
                byte[] log = readBounded(runtime, directory, Pd2SessionLog.MAX_ARCHIVE_BYTES);
                if (log == null) continue;
                writeZip(zip, runtime.getName(), log);
                String stamp = stamp(runtime);
                byte[] launch = readBounded(new File(directory, "launch-attempt-" + stamp + ".json"),
                        directory, MAX_LAUNCH_BYTES);
                String id = launchId(launch);
                if (id == null) continue;
                writeZip(zip, "launch-attempt-" + stamp + ".json", launch);
                for (int index = 1; index < TYPES.length; index++) {
                    String name = TYPES[index] + "-attempt-" + stamp + ".json";
                    byte[] report = readBounded(new File(directory, name), directory, LIMITS[index]);
                    if (id.equals(launchId(report))) writeZip(zip, name, report);
                }
            }
        }
    }

    private static void writeZip(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry("attempts/" + name));
        try { zip.write(bytes); }
        finally { zip.closeEntry(); }
    }

    private static void publish(File directory, String name, byte[] bytes) throws IOException {
        File target = new File(directory, name);
        File temporary = new File(directory, name + ".tmp");
        // Exclusive creation prevents following or replacing an existing report/link.
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        boolean reserved = false;
        try {
            Files.createFile(target.toPath());
            reserved = true;
            try (FileChannel out = FileChannel.open(temporary.toPath(),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) out.write(data);
                out.force(true);
            }
            if (!safeFile(directory, target) || target.length() != 0 || !safeFile(directory, temporary))
                throw new IOException("Attempt report path changed while being archived");
            if (!temporary.renameTo(target)) throw new IOException("Cannot publish attempt report");
            reserved = false;
        } finally {
            if (safeFile(directory, temporary)) temporary.delete();
            if (reserved && safeFile(directory, target) && target.length() == 0) target.delete();
        }
    }

    private static void pruneReports(File directory) throws IOException {
        Set<String> retained = new HashSet<>();
        for (File runtime : runtimeArchives(directory)) retained.add(stamp(runtime));
        for (File file : children(directory)) {
            String name = file.getName();
            if (!safeFile(directory, file)) continue;
            if (name.matches(REPORT_PATTERN) && !retained.contains(reportStamp(name))) file.delete();
            else if (name.matches(REPORT_PATTERN + "\\.tmp")) {
                file.delete();
                File reservation = new File(directory, name.substring(0, name.length() - 4));
                if (safeFile(directory, reservation) && reservation.length() == 0) reservation.delete();
            }
        }
    }

    private static List<File> runtimeArchives(File directory) throws IOException {
        List<File> archives = new ArrayList<>();
        for (File file : children(directory)) {
            if (file.getName().matches(RUNTIME_PATTERN) && safeFile(directory, file)) archives.add(file);
        }
        archives.sort(Comparator.comparing(File::getName));
        return archives.subList(Math.max(0, archives.size() - Pd2SessionLog.MAX_ATTEMPTS), archives.size());
    }

    private static List<File> children(File directory) throws IOException {
        List<File> result = new ArrayList<>();
        try (DirectoryStream<java.nio.file.Path> paths = Files.newDirectoryStream(directory.toPath())) {
            for (java.nio.file.Path path : paths) {
                if (result.size() >= MAX_DIRECTORY_ENTRIES)
                    throw new IOException("Attempt report directory exceeds its scan limit");
                result.add(path.toFile());
            }
        }
        return result;
    }

    private static File safeLogsDirectory(File filesDirectory) throws IOException {
        File base = filesDirectory.getCanonicalFile();
        File pd2 = new File(base, "pd2");
        File logs = new File(pd2, "logs");
        return !Files.isSymbolicLink(pd2.toPath()) && !Files.isSymbolicLink(logs.toPath())
                && logs.isDirectory() && logs.getCanonicalFile().equals(logs) ? logs : null;
    }

    private static File safeAttemptsDirectory(File logs) throws IOException {
        if (logs == null) return null;
        File attempts = new File(logs, "attempts");
        return !Files.isSymbolicLink(attempts.toPath()) && attempts.isDirectory()
                && attempts.getCanonicalFile().getParentFile().equals(logs) ? attempts : null;
    }

    private static boolean safeFile(File directory, File file) {
        try {
            return directory != null && !Files.isSymbolicLink(file.toPath()) && file.isFile()
                    && file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile());
        } catch (IOException | SecurityException invalid) { return false; }
    }

    private static byte[] readBounded(File file, File directory, int limit) throws IOException {
        if (!safeFile(directory, file) || file.length() == 0 || file.length() > limit) return null;
        try (InputStream input = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (bytes.size() + read > limit) return null;
                bytes.write(buffer, 0, read);
            }
            return bytes.toByteArray();
        }
    }

    private static String launchId(byte[] bytes) {
        if (bytes == null) return null;
        try {
            String id = new JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                    .optString("launchId", "");
            return id.matches(UUID_PATTERN) ? id : null;
        } catch (JSONException malformed) { return null; }
    }

    private static String stamp(File runtime) {
        String name = runtime.getName();
        return name.substring("runtime-attempt-".length(), name.length() - ".log".length());
    }

    private static String reportStamp(String name) {
        return name.substring(name.indexOf("-attempt-") + "-attempt-".length(), name.length() - ".json".length());
    }
}
