package com.winlator.pd2;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/** Preserve the previous runtime attempt before the upstream log view replaces runtime.log. */
public final class Pd2SessionLog {
    public static final int MAX_ARCHIVE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ATTEMPTS = 4;
    private static final String PREFIX = "runtime-attempt-";
    private static final String ARCHIVE_PATTERN = "runtime-attempt-[0-9]{13,19}\\.log";
    private static final byte[] TRUNCATED =
            "\n\n[Runtime log truncated: middle omitted; first and last sections preserved]\n\n"
                    .getBytes(StandardCharsets.UTF_8);
    // Shared with the live file-backed logger: a snapshot never reads a partial rotation.
    static final Object LOCK = new Object();

    private Pd2SessionLog() {}

    /** Returns null if no nonempty previous log exists. Does not alter runtime.log. */
    public static File archivePrevious(Context context) throws IOException {
        return archivePrevious(context.getFilesDir());
    }

    public static File getAttemptsDirectory(Context context) { return getAttemptsDirectory(context.getFilesDir()); }
    public static File getAttemptsDirectory(File filesDirectory) { return new File(filesDirectory, "pd2/logs/attempts"); }

    public static File archivePrevious(File filesDirectory) throws IOException {
        synchronized (LOCK) {
            File base = filesDirectory.getCanonicalFile();
            File pd2 = new File(base, "pd2");
            File logs = new File(pd2, "logs");
            File source = new File(logs, "runtime.log");
            File directory = new File(logs, "attempts");
            if (Files.isSymbolicLink(pd2.toPath()) || Files.isSymbolicLink(logs.toPath())
                    || Files.isSymbolicLink(directory.toPath())) return null;
            if (!safeFile(logs, source) || source.length() == 0) {
                if (directory.isDirectory()) { removeStaleTemporaries(directory); retainLatest(directory); }
                return null;
            }
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create attempt log directory");
            removeStaleTemporaries(directory);
            File target = reserveArchive(directory);
            File temporary = new File(directory, target.getName() + ".tmp");
            boolean published = false;
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    writeSnapshot(source, output);
                    output.getFD().sync();
                }
                if (!temporary.renameTo(target)) throw new IOException("Cannot publish attempt log");
                published = true;
                retainLatest(directory);
                return target;
            } finally {
                temporary.delete();
                if (!published) target.delete();
            }
        }
    }

    /**
     * Write a bounded head/tail snapshot, including capture metadata, into an existing stream.
     * Leaves the caller's stream open and does not create archives or change the source file.
     */
    public static void writeSnapshot(File source, OutputStream output) throws IOException {
        synchronized (LOCK) {
            if (Files.isSymbolicLink(source.toPath())) throw new IOException("Runtime log must be a regular private file");
            try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
                long originalBytes = input.length();
                byte[] header = ("PD2 Android runtime log snapshot\nCaptured: " + new Date()
                        + "\nOriginal modification time: " + new Date(source.lastModified())
                        + "\nOriginal log bytes: " + originalBytes + "\n\n").getBytes(StandardCharsets.UTF_8);
                output.write(header);
                int budget = MAX_ARCHIVE_BYTES - header.length;
                if (originalBytes <= budget) {
                    long end = utf8End(input, 0, originalBytes);
                    input.seek(0);
                    copy(input, output, end);
                }
                else {
                    int contentBudget = budget - TRUNCATED.length;
                    int head = contentBudget / 2;
                    int tail = contentBudget - head;
                    long headEnd = utf8End(input, 0, head);
                    long tailStart = utf8Start(input, originalBytes - tail, originalBytes);
                    long tailEnd = utf8End(input, tailStart, originalBytes);
                    input.seek(0);
                    copy(input, output, headEnd);
                    output.write(TRUNCATED);
                    input.seek(tailStart);
                    copy(input, output, tailEnd - tailStart);
                }
            }
        }
    }

    /** Avoid beginning a retained UTF-8 slice in the middle of a code point. */
    static long utf8Start(RandomAccessFile input, long start, long end) throws IOException {
        long boundary = start;
        while (boundary < end && boundary < start + 3) {
            input.seek(boundary);
            if ((input.readUnsignedByte() & 0xc0) != 0x80) break;
            boundary++;
        }
        return boundary;
    }

    /** Avoid ending a retained UTF-8 slice with a partial code point. */
    static long utf8End(RandomAccessFile input, long start, long end) throws IOException {
        if (end <= start) return end;
        long lead = end - 1;
        input.seek(lead);
        int value = input.readUnsignedByte();
        while ((value & 0xc0) == 0x80 && lead > start && lead > end - 4) {
            input.seek(--lead);
            value = input.readUnsignedByte();
        }
        int bytes = value >= 0xc2 && value <= 0xdf ? 2
                : value >= 0xe0 && value <= 0xef ? 3 : value >= 0xf0 && value <= 0xf4 ? 4 : 1;
        return lead + bytes > end ? lead : end;
    }

    private static void copy(RandomAccessFile input, OutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Runtime log changed while being archived");
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static File[] archives(File directory) throws IOException {
        File[] files = directory.listFiles(file -> safeFile(directory, file) && file.getName().matches(ARCHIVE_PATTERN));
        if (files == null) throw new IOException("Cannot read attempt log directory");
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private static File reserveArchive(File directory) throws IOException {
        File[] existing = archives(directory);
        long timestamp = System.currentTimeMillis();
        if (existing.length > 0) {
            String latest = existing[existing.length - 1].getName();
            long previous = Long.parseLong(latest.substring(PREFIX.length(), latest.length() - 4));
            timestamp = Math.max(timestamp, previous + 1);
        }
        // The exclusive reservation also handles another process or a same-millisecond launch.
        for (int attempt = 0; attempt < 1000; attempt++, timestamp++) {
            File target = new File(directory, PREFIX + String.format(Locale.ROOT, "%013d", timestamp) + ".log");
            if (target.createNewFile()) return target;
        }
        throw new IOException("Cannot reserve attempt log filename");
    }

    private static void retainLatest(File directory) throws IOException {
        File[] files = archives(directory);
        for (int index = 0; index < files.length - MAX_ATTEMPTS; index++) {
            if (!files[index].delete()) throw new IOException("Cannot prune old attempt log");
            String name = files[index].getName();
            String stamp = name.substring(PREFIX.length(), name.length() - 4);
            for (String type : new String[]{"launch", "controller", "memory"}) {
                File report = new File(directory, type + "-attempt-" + stamp + ".json");
                if (safeFile(directory, report)) report.delete();
            }
        }
    }

    private static void removeStaleTemporaries(File directory) throws IOException {
        File[] files = directory.listFiles(file -> safeFile(directory, file)
                && file.getName().matches(ARCHIVE_PATTERN + "\\.tmp"));
        if (files == null) throw new IOException("Cannot read temporary attempt logs");
        for (File file : files) {
            if (!file.delete()) throw new IOException("Cannot remove interrupted attempt log");
            File reservation = new File(directory, file.getName().substring(0, file.getName().length() - 4));
            if (safeFile(directory, reservation) && reservation.length() == 0) reservation.delete();
        }
    }

    private static boolean safeFile(File directory, File file) {
        try {
            return !Files.isSymbolicLink(file.toPath()) && file.isFile()
                    && file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile());
        } catch (IOException | SecurityException ignored) { return false; }
    }
}
