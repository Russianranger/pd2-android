package com.winlator.xserver.extensions;

import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Bounds the cadence of serial Present requests without accumulating future work.
 * The connector dispatches these requests serially; concurrent callers are best effort.
 * No monitor is held while sleeping, including when a window is reset or freed.
 */
final class PresentFramePacer {
    interface Clock { long nanoTime(); }
    interface Sleeper { void sleep(long nanos) throws InterruptedException; }

    static final int MAX_WINDOWS = 128;
    private final Clock clock;
    private final Sleeper sleeper;
    private final LinkedHashMap<Object, WindowState> windows = new LinkedHashMap<>(16, .75f, true);
    private int fps;
    private long intervalNanos, generation, configuredAt;
    private long presents, waits, requestedWaitNanos, actualWaitNanos, interruptedWaits;

    private static final class WindowState {
        long lastDispatch;
        WindowState(long now) { lastDispatch = now; }
    }

    static final class Snapshot {
        final int fps, trackedWindows;
        final long presents, waits, requestedWaitNanos, actualWaitNanos, interruptedWaits, elapsedNanos;

        Snapshot(int fps, int trackedWindows, long presents, long waits, long requestedWaitNanos,
                 long actualWaitNanos, long interruptedWaits, long elapsedNanos) {
            this.fps = fps;
            this.trackedWindows = trackedWindows;
            this.presents = presents;
            this.waits = waits;
            this.requestedWaitNanos = requestedWaitNanos;
            this.actualWaitNanos = actualWaitNanos;
            this.interruptedWaits = interruptedWaits;
            this.elapsedNanos = elapsedNanos;
        }
    }

    PresentFramePacer() { this(System::nanoTime, TimeUnit.NANOSECONDS::sleep); }

    PresentFramePacer(Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
        configure(0);
    }

    synchronized void configure(int requestedFps) {
        fps = requestedFps == 30 || requestedFps == 60 ? requestedFps : 0;
        intervalNanos = fps == 0 ? 0 : (1_000_000_000L + fps - 1) / fps;
        generation++;
        configuredAt = clock.nanoTime();
        windows.clear();
        presents = waits = requestedWaitNanos = actualWaitNanos = interruptedWaits = 0;
    }

    void pace(Object window) {
        final WindowState state;
        final long started, delay, observedGeneration;
        synchronized (this) {
            presents++;
            if (fps == 0 || window == null) return;
            started = clock.nanoTime();
            WindowState previous = windows.get(window);
            if (previous == null) {
                windows.put(window, new WindowState(started));
                if (windows.size() > MAX_WINDOWS) {
                    Object oldest = windows.keySet().iterator().next();
                    windows.remove(oldest);
                }
                return;
            }
            state = previous;
            long elapsed = started - state.lastDispatch;
            // A stall or a backwards test clock starts a fresh interval, without catch-up bursts.
            if (elapsed < 0 || elapsed >= intervalNanos) {
                state.lastDispatch = started;
                return;
            }
            delay = intervalNanos - elapsed;
            waits++;
            requestedWaitNanos += delay;
            observedGeneration = generation;
        }

        boolean interrupted = false;
        try { sleeper.sleep(delay); }
        catch (InterruptedException ignored) { interrupted = true; }
        finally {
            long finished = clock.nanoTime();
            synchronized (this) {
                if (generation == observedGeneration) {
                    actualWaitNanos += Math.max(0, finished - started);
                    if (interrupted) interruptedWaits++;
                    // A resize, free or bounded-cache eviction must not revive this history.
                    if (windows.get(window) == state) state.lastDispatch = finished;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    synchronized void forget(Object window) { windows.remove(window); }

    synchronized Snapshot stats() {
        return new Snapshot(fps, windows.size(), presents, waits, requestedWaitNanos, actualWaitNanos,
                interruptedWaits, Math.max(0, clock.nanoTime() - configuredAt));
    }
}
