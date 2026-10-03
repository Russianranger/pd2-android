package com.winlator.pd2;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Bound live diagnostic output, retaining the first fault and an explicit cap marker. */
public final class Pd2LogOutputStream extends OutputStream {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final byte[] MARKER = "\n[PD2 runtime log reached 8 MiB; further output omitted]\n".getBytes(StandardCharsets.UTF_8);
    private final OutputStream delegate;
    private int written;
    private boolean capped;

    public Pd2LogOutputStream(OutputStream delegate) { this.delegate = delegate; }

    @Override public synchronized void write(int value) throws IOException {
        write(new byte[]{(byte)value}, 0, 1);
    }

    @Override public synchronized void write(byte[] data, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > data.length - length) throw new IndexOutOfBoundsException();
        if (capped || length == 0) return;
        int remaining = MAX_BYTES - MARKER.length - written;
        int accepted = Math.min(remaining, length);
        delegate.write(data, offset, accepted);
        written += accepted;
        if (accepted < length) {
            delegate.write(MARKER);
            capped = true;
        }
    }

    @Override public synchronized void flush() throws IOException { delegate.flush(); }
    @Override public synchronized void close() throws IOException { delegate.close(); }
}
