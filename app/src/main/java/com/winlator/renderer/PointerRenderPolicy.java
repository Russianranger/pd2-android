package com.winlator.renderer;

/** Motion-only invalidation; framebuffer and cursor visibility changes invalidate separately. */
final class PointerRenderPolicy {
    private PointerRenderPolicy() {}

    static boolean needsMotionFrame(boolean cursorVisible, boolean cursorOverlayVisible,
                                    boolean menuPointerCursorVisible, boolean viewFollowsPointer) {
        // Keep enabled overlays conservative: the point window can update after the
        // renderer's motion listener, including a crossing into a visible guest cursor.
        // The gold Menu reticle is independent of the hidden white X cursor overlays.
        return viewFollowsPointer || cursorVisible && (cursorOverlayVisible || menuPointerCursorVisible);
    }
}
