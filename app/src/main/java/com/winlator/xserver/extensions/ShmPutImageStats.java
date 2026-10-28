package com.winlator.xserver.extensions;

/** Fixed-size request observations only; these do not acknowledge a completed pixel transfer. */
final class ShmPutImageStats {
    private long requests, sharedBorrowed, copied, nonzeroOffset, subrect, logicalBytes, unknownByteEstimates;

    static final class Snapshot {
        final long requests, sharedBorrowed, copied, nonzeroOffset, subrect, logicalBytes, unknownByteEstimates;
        Snapshot(ShmPutImageStats source) {
            requests = source.requests;
            sharedBorrowed = source.sharedBorrowed;
            copied = source.copied;
            nonzeroOffset = source.nonzeroOffset;
            subrect = source.subrect;
            logicalBytes = source.logicalBytes;
            unknownByteEstimates = source.unknownByteEstimates;
        }
    }

    synchronized void record(boolean borrowed, int offset, boolean partial, long estimatedBytes) {
        requests = add(requests, 1);
        if (borrowed) sharedBorrowed = add(sharedBorrowed, 1);
        else copied = add(copied, 1);
        if (offset != 0) nonzeroOffset = add(nonzeroOffset, 1);
        if (partial) subrect = add(subrect, 1);
        if (estimatedBytes < 0) unknownByteEstimates = add(unknownByteEstimates, 1);
        else logicalBytes = add(logicalBytes, estimatedBytes);
    }

    synchronized Snapshot snapshot() { return new Snapshot(this); }

    /** This server stores depth24/32 pixels in four bytes; stride/padding/transport are excluded. */
    static long estimateBytes(long width, long height, int depth) {
        if (width < 0 || height < 0 || depth != 24 && depth != 32) return -1;
        if (width != 0 && height > Long.MAX_VALUE / width) return -1;
        long pixels = width * height;
        return pixels > Long.MAX_VALUE / 4 ? -1 : pixels * 4;
    }

    static long add(long value, long amount) {
        return amount > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + amount;
    }
}
