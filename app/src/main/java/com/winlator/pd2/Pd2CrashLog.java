package com.winlator.pd2;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/** A bounded last-crash report and a separate, explicitly acknowledged recovery marker. */
public final class Pd2CrashLog {
    public static final int MAX_CRASH_BYTES = 128 * 1024;
    private static final Object LOCK = new Object();
    private static final byte[] TRUNCATED = "\n[Crash report truncated at 128 KiB]\n".getBytes(StandardCharsets.UTF_8);

    private Pd2CrashLog() {}

    public static boolean hasPending(Context context) { return hasPending(context.getFilesDir()); }
    public static File getCrashFile(Context context) { return getCrashFile(context.getFilesDir()); }
    public static void acknowledge(Context context) { acknowledge(context.getFilesDir()); }

    public static boolean hasPending(File filesDirectory) {
        synchronized (LOCK) {
            return marker(filesDirectory).isFile() && getCrashFile(filesDirectory).isFile()
                    && getCrashFile(filesDirectory).length() > 0;
        }
    }

    public static File getCrashFile(File filesDirectory) { return new File(filesDirectory, "pd2/logs/crash.txt"); }

    /** Retain the report for sharing/support after the user chooses to retry. */
    public static void acknowledge(File filesDirectory) {
        synchronized (LOCK) { marker(filesDirectory).delete(); }
    }

    public static void record(Context context, Thread thread, Throwable error, String details) throws IOException {
        record(context.getFilesDir(), thread, error, details);
    }

    /** File-based entry point keeps storage behavior testable without an Android runtime. */
    public static void record(File filesDirectory, Thread thread, Throwable error, String details) throws IOException {
        synchronized (LOCK) {
            File target = getCrashFile(filesDirectory);
            File parent = target.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create crash report directory");
            File temporary = new File(parent, "crash.txt.tmp");
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    LimitedOutput bounded = new LimitedOutput(output, MAX_CRASH_BYTES - TRUNCATED.length);
                    PrintWriter writer = new PrintWriter(new OutputStreamWriter(bounded, StandardCharsets.UTF_8));
                    writer.println("PD2 Android uncaught Java exception");
                    writer.println("Time: " + new Date());
                    writer.println("Thread: " + (thread == null ? "unknown" : thread.getName()));
                    if (details != null && !details.isEmpty()) writer.println(details);
                    writer.println();
                    if (error == null) writer.println("No exception details available.");
                    else error.printStackTrace(writer);
                    writer.flush();
                    if (writer.checkError()) throw new IOException("Cannot write crash report");
                    if (bounded.truncated) output.write(TRUNCATED);
                    output.getFD().sync();
                }
                // Android uses Linux rename: replace the previous report only after the new one is complete.
                if (!temporary.renameTo(target)) throw new IOException("Cannot publish crash report");
                try (FileOutputStream pending = new FileOutputStream(marker(filesDirectory))) {
                    pending.write(1);
                    pending.getFD().sync();
                }
            } finally { temporary.delete(); }
        }
    }

    private static File marker(File filesDirectory) { return new File(filesDirectory, "pd2/logs/crash.pending"); }

    private static final class LimitedOutput extends OutputStream {
        private final OutputStream output;
        private int remaining;
        private boolean truncated;
        private LimitedOutput(OutputStream output, int limit) { this.output = output; remaining = limit; }
        @Override public void write(int value) throws IOException {
            if (remaining > 0) { output.write(value); remaining--; }
            else truncated = true;
        }
        @Override public void write(byte[] values, int offset, int length) throws IOException {
            int accepted = Math.min(remaining, length);
            if (accepted < length) {
                // OutputStreamWriter gives UTF-8 chunks. Keep a truncation boundary between code points.
                int start = accepted - 1;
                while (start >= 0 && (values[offset + start] & 0xc0) == 0x80) start--;
                if (start >= 0) {
                    int leading = values[offset + start] & 0xff;
                    int bytes = leading < 0x80 ? 1 : leading < 0xe0 ? 2 : leading < 0xf0 ? 3 : 4;
                    if (start + bytes > accepted) accepted = start;
                }
                truncated = true;
            }
            if (accepted > 0) { output.write(values, offset, accepted); remaining -= accepted; }
            if (truncated) remaining = 0;
        }
        @Override public void flush() throws IOException { output.flush(); }
    }
}
