package com.winlator.pd2;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** File-backed rolling output: preserve startup and continue retaining the latest events. */
public final class Pd2LogOutputStream extends OutputStream {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int STARTUP_BYTES = 512 * 1024;
    public static final int TAIL_BYTES = 4 * 1024 * 1024;
    private static final byte[] MARKER = ("\n[PD2 runtime log rotated: middle omitted; "
            + "startup and current tail retained; recording continues]\n").getBytes(StandardCharsets.UTF_8);
    private static final Map<String, Pd2LogOutputStream> ACTIVE = new HashMap<>();
    private final RandomAccessFile file;
    private final String path;
    private final byte[] buffer = new byte[64 * 1024];
    private final byte[] singleByte = new byte[1];
    private long written;
    private long startupBytes = -1;
    private boolean closed;

    /** Start a new attempt. The previous attempt must be archived before opening. */
    public Pd2LogOutputStream(File destination) throws IOException {
        path = destination.getCanonicalPath();
        synchronized (Pd2SessionLog.LOCK) {
            Pd2LogOutputStream previous = ACTIVE.get(path);
            if (previous != null) previous.close();
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                throw new IOException("Cannot create runtime log directory");
            file = new RandomAccessFile(destination, "rw");
            try { file.setLength(0); }
            catch (IOException error) { file.close(); throw error; }
            ACTIVE.put(path, this);
        }
    }

    @Override public void write(int value) throws IOException {
        synchronized (Pd2SessionLog.LOCK) {
            singleByte[0] = (byte)value;
            write(singleByte, 0, 1);
        }
    }

    @Override public void write(byte[] data, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > data.length - length) throw new IndexOutOfBoundsException();
        synchronized (Pd2SessionLog.LOCK) {
            requireOpen();
            while (length > 0) {
                if (written == MAX_BYTES) rotate();
                int accepted = (int)Math.min(MAX_BYTES - written, length);
                file.seek(written);
                file.write(data, offset, accepted);
                written += accepted;
                offset += accepted;
                length -= accepted;
            }
        }
    }

    /** Compact once per several MiB, using one reusable buffer and the snapshot lock. */
    private void rotate() throws IOException {
        if (startupBytes < 0) startupBytes = Pd2SessionLog.utf8End(file, 0, STARTUP_BYTES);
        long from = Pd2SessionLog.utf8Start(file, written - TAIL_BYTES, written);
        long remaining = written - from;
        long to = startupBytes + MARKER.length;
        file.seek(startupBytes);
        file.write(MARKER);
        // Destination is below source, so forward chunk copying cannot overwrite unread data.
        while (remaining > 0) {
            int count = (int)Math.min(buffer.length, remaining);
            file.seek(from);
            file.readFully(buffer, 0, count);
            file.seek(to);
            file.write(buffer, 0, count);
            from += count;
            to += count;
            remaining -= count;
        }
        file.setLength(to);
        written = to;
    }

    private void requireOpen() throws IOException {
        if (closed) throw new IOException("Runtime log stream is closed");
    }

    @Override public void flush() throws IOException {
        synchronized (Pd2SessionLog.LOCK) { requireOpen(); }
        // RandomAccessFile has no userspace write buffer; avoid fsync for every log line.
    }

    @Override public void close() throws IOException {
        synchronized (Pd2SessionLog.LOCK) {
            if (closed) return;
            closed = true;
            if (ACTIVE.get(path) == this) ACTIVE.remove(path);
            file.close();
        }
    }
}
