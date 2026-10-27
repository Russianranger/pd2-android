package com.winlator.renderer;

import java.util.List;

/** Per-pass work selection; the caller keeps the complete scene for subsequent frames. */
final class CompositorPolicy {
    interface Layer {
        int left();
        int top();
        int width();
        int height();
        boolean transparent();
        boolean offscreen();
    }

    interface Pass<T extends Layer> {
        void blending(boolean enabled);
        void draw(T layer);
    }

    /** Reused by the GL thread; no frame-sized lists or result objects are allocated. */
    static final class Stats {
        int considered, drawn, occluded, offscreen, opaque, transparent, blendChanges;

        void reset(int considered) {
            this.considered = considered;
            drawn = occluded = offscreen = opaque = transparent = blendChanges = 0;
        }
    }

    private CompositorPolicy() {}

    static boolean needsCursorPass(boolean cursorVisible, boolean cursorOverlayVisible,
                                   boolean menuPointerVisible) {
        return cursorVisible && (cursorOverlayVisible || menuPointerVisible);
    }

    static boolean coversViewport(Layer layer, int width, int height) {
        return width > 0 && height > 0 && !layer.transparent() && !layer.offscreen()
                && layer.width() > 0 && layer.height() > 0
                && layer.left() <= 0 && layer.top() <= 0
                && (long) layer.left() + layer.width() >= width
                && (long) layer.top() + layer.height() >= height;
    }

    static <T extends Layer> void render(List<T> layers, int width, int height,
                                        boolean allowOcclusion, Pass<T> pass, Stats stats) {
        stats.reset(layers.size());
        int first = 0;
        if (allowOcclusion) {
            for (int i = layers.size() - 1; i >= 0; i--) {
                if (coversViewport(layers.get(i), width, height)) {
                    first = i;
                    break;
                }
            }
        }
        for (int i = 0; i < first; i++) {
            if (layers.get(i).offscreen()) stats.offscreen++;
            else stats.occluded++;
        }

        // GLRenderer enters and leaves this pass with alpha blending enabled.
        boolean blending = true;
        try {
            for (int i = first; i < layers.size(); i++) {
                T layer = layers.get(i);
                if (layer.offscreen()) { stats.offscreen++; continue; }
                boolean nextBlending = layer.transparent();
                if (nextBlending != blending) {
                    pass.blending(nextBlending);
                    blending = nextBlending;
                    stats.blendChanges++;
                }
                pass.draw(layer);
                stats.drawn++;
                if (layer.transparent()) stats.transparent++;
                else stats.opaque++;
            }
        } finally {
            // Cursor, effects and screenshots retain their existing blend state.
            if (!blending) {
                pass.blending(true);
                stats.blendChanges++;
            }
        }
    }
}
