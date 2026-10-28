package com.winlator.widget;

/**
 * Coalesces content invalidations without blocking the producer. Urgent pointer,
 * geometry and visibility requests remain immediate in the owning View.
 */
final class ContentFrameCoalescer implements AutoCloseable {
    interface Clock { long nanoTime(); }
    interface Scheduler {
        boolean postDelayed(Runnable callback, long milliseconds);
        void remove(Runnable callback);
    }

    private final Clock clock;
    private final Scheduler scheduler;
    private Runnable render, callback;
    private int fps;
    private long intervalNanos, nextDueNanos, lastForwardNanos, generation;
    private boolean attached, resumed = true, closed, dirty, scheduled, timelineStarted;
    private long requests, coalesced, deferredCallbacks, forwarded, immediateRequests, cancellations, postFailures;

    static final class Snapshot {
        final int fps;
        final long requests, coalesced, deferredCallbacks, forwarded, immediateRequests, cancellations, postFailures;
        final boolean attached, resumed, closed, dirty, scheduled;

        Snapshot(ContentFrameCoalescer source) {
            fps = source.fps;
            requests = source.requests;
            coalesced = source.coalesced;
            deferredCallbacks = source.deferredCallbacks;
            forwarded = source.forwarded;
            immediateRequests = source.immediateRequests;
            cancellations = source.cancellations;
            postFailures = source.postFailures;
            attached = source.attached;
            resumed = source.resumed;
            closed = source.closed;
            dirty = source.dirty;
            scheduled = source.scheduled;
        }
    }

    ContentFrameCoalescer(int requestedFps, Clock clock, Scheduler scheduler, Runnable render) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.render = render;
        setLimit(requestedFps);
    }

    synchronized void setLimit(int requestedFps) {
        if (closed) return;
        cancelLocked();
        fps = requestedFps == 30 || requestedFps == 60 ? requestedFps : 0;
        intervalNanos = fps == 0 ? 0 : (1_000_000_000L + fps - 1) / fps;
        timelineStarted = false;
        if (active() && dirty) forwardLocked(clock.nanoTime());
    }

    synchronized void setAttached(boolean value) {
        if (attached == value || closed) return;
        attached = value;
        lifecycleChangedLocked();
    }

    synchronized void setResumed(boolean value) {
        if (resumed == value || closed) return;
        resumed = value;
        lifecycleChangedLocked();
    }

    private boolean active() { return attached && resumed && !closed; }

    private void lifecycleChangedLocked() {
        if (!active()) {
            cancelLocked();
            timelineStarted = false;
        } else if (dirty) forwardLocked(clock.nanoTime());
    }

    synchronized void request() {
        if (closed) return;
        requests++;
        if (dirty) coalesced++;
        dirty = true;
        if (!active() || scheduled) return;
        long now = clock.nanoTime();
        if (intervalNanos == 0 || !timelineStarted || now - lastForwardNanos < 0 || now - nextDueNanos >= 0) {
            forwardLocked(now);
        } else postLocked(nextDueNanos - now);
    }

    /** The immediate frame includes the current content, so its delayed duplicate is unnecessary. */
    synchronized void immediate() {
        if (closed) return;
        immediateRequests++;
        dirty = false;
        cancelLocked();
        long now = clock.nanoTime();
        timelineStarted = true;
        lastForwardNanos = now;
        nextDueNanos = now + intervalNanos;
    }

    private void postLocked(long nanos) {
        if (callback == null) {
            final long token = generation;
            callback = () -> due(token);
        }
        scheduled = true;
        deferredCallbacks++;
        long milliseconds = Math.max(1, (nanos + 999_999L) / 1_000_000L);
        if (!scheduler.postDelayed(callback, milliseconds)) {
            scheduled = false;
            postFailures++;
        }
    }

    private synchronized void due(long token) {
        if (closed || generation != token || !scheduled || !active()) return;
        scheduled = false;
        if (!dirty) return;
        long now = clock.nanoTime();
        if (timelineStarted && now - lastForwardNanos >= 0 && now - nextDueNanos < 0) {
            postLocked(nextDueNanos - now);
        } else forwardLocked(now);
    }

    private void forwardLocked(long now) {
        dirty = false;
        forwarded++;
        if (intervalNanos > 0) {
            // Preserve fractional millisecond phase through normal Handler rounding.
            // Long stalls start fresh instead of building a catch-up queue.
            if (!timelineStarted || now - lastForwardNanos < 0 || now - nextDueNanos >= intervalNanos) {
                nextDueNanos = now + intervalNanos;
            } else nextDueNanos += intervalNanos;
            lastForwardNanos = now;
            timelineStarted = true;
        }
        // This only forwards requestRender; no drawing or sleeping occurs here.
        // Keep cancellation ordered with forwarding, including a concurrent close.
        if (render != null) render.run();
    }

    private void cancelLocked() {
        if (scheduled) {
            scheduler.remove(callback);
            cancellations++;
        }
        scheduled = false;
        callback = null;
        generation++;
    }

    synchronized Snapshot snapshot() { return new Snapshot(this); }

    @Override public synchronized void close() {
        if (closed) return;
        cancelLocked();
        closed = true;
        dirty = false;
        // A stale dequeued callback can retain this policy, but never its View.
        render = null;
    }
}
