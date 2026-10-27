package com.winlator.renderer;

import com.winlator.xserver.Drawable;

class RenderableWindow implements CompositorPolicy.Layer {
    final Drawable content;
    short rootX;
    short rootY;
    final boolean transparent;
    final FullscreenTransformation fullscreenTransformation;

    public RenderableWindow(Drawable content, int rootX, int rootY, boolean transparent, FullscreenTransformation fullscreenTransformation) {
        this.content = content;
        this.rootX = (short)rootX;
        this.rootY = (short)rootY;
        this.transparent = transparent;
        this.fullscreenTransformation = fullscreenTransformation;
    }

    // These are the exact quad coordinates used by renderWindowDrawable.
    @Override public int left() { return fullscreenTransformation != null ? fullscreenTransformation.x : rootX; }
    @Override public int top() { return fullscreenTransformation != null ? fullscreenTransformation.y : rootY; }
    @Override public int width() { return fullscreenTransformation != null ? fullscreenTransformation.width : content.width; }
    @Override public int height() { return fullscreenTransformation != null ? fullscreenTransformation.height : content.height; }
    @Override public boolean transparent() { return transparent; }
    @Override public boolean offscreen() { return content.isOffscreenStorage(); }
}
