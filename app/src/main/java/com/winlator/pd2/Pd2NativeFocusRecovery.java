package com.winlator.pd2;

import android.os.Handler;
import android.os.Looper;

import java.util.function.BooleanSupplier;

/** Bounded foreground recovery when native input resumes or the game replaces/resizes its window. */
public final class Pd2NativeFocusRecovery {
    public interface Target {
        boolean isReady();
        void restore(BooleanSupplier allowed, String reason);
    }

    private static final long COALESCE_MS = 75;
    private static final long SETTLE_MS = 250;
    private static final int MAX_READINESS_ATTEMPTS = 8;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Target target;
    private volatile boolean active;
    private volatile long generation;
    private Runnable pending;

    public Pd2NativeFocusRecovery(Target target) { this.target = target; }

    public void setActive(boolean enabled) {
        if (active == enabled) return;
        active = enabled;
        cancel();
        if (enabled) request("route");
    }

    public void windowChanged() {
        if (active) request("window");
    }

    private void cancel() {
        generation++;
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
    }

    private void request(String reason) {
        cancel();
        long epoch = generation;
        schedule(epoch, reason, 0, false, COALESCE_MS);
    }

    private void schedule(long epoch, String reason, int attempt, boolean settled, long delay) {
        pending = () -> {
            pending = null;
            if (!active || generation != epoch) return;
            if (!target.isReady()) {
                if (attempt + 1 < MAX_READINESS_ATTEMPTS)
                    schedule(epoch, reason, attempt + 1, settled, SETTLE_MS);
                return;
            }
            target.restore(() -> active && generation == epoch, settled ? "settle" : reason);
            // Fullscreen/menu transitions may replace the foreground window just after the first request.
            if (!settled) schedule(epoch, reason, 0, true, SETTLE_MS);
        };
        handler.postDelayed(pending, delay);
    }
}
