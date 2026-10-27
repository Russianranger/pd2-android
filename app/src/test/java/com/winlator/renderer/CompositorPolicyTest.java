package com.winlator.renderer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public final class CompositorPolicyTest {
    private static final int WIDTH = 20, HEIGHT = 12;

    private static final class Layer implements CompositorPolicy.Layer {
        int x, y, width, height;
        final boolean transparent, offscreen;
        final int color;

        Layer(int x, int y, int width, int height, boolean transparent, boolean offscreen, int color) {
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.transparent = transparent; this.offscreen = offscreen; this.color = color;
        }

        @Override public int left() { return x; }
        @Override public int top() { return y; }
        @Override public int width() { return width; }
        @Override public int height() { return height; }
        @Override public boolean transparent() { return transparent; }
        @Override public boolean offscreen() { return offscreen; }
    }

    /** Evaluates the compositor's source-alpha blend equation on small pixel fixtures. */
    private static final class SoftwarePass implements CompositorPolicy.Pass<Layer> {
        final int[] pixels = new int[WIDTH * HEIGHT];
        final List<Layer> draws = new ArrayList<>();
        final List<Boolean> blendStates = new ArrayList<>();
        boolean blending = true;

        @Override public void blending(boolean enabled) {
            blending = enabled;
            blendStates.add(enabled);
        }

        @Override public void draw(Layer layer) {
            draws.add(layer);
            // WindowMaterial forces alpha to one for all nontransparent windows.
            int color = layer.transparent ? layer.color : layer.color | 0xff000000;
            int right = (int) Math.min(WIDTH, (long) layer.x + layer.width);
            int bottom = (int) Math.min(HEIGHT, (long) layer.y + layer.height);
            for (int y = Math.max(0, layer.y); y < bottom; y++) {
                for (int x = Math.max(0, layer.x); x < right; x++) {
                    int at = y * WIDTH + x;
                    pixels[at] = blending ? blend(color, pixels[at]) : color;
                }
            }
        }
    }

    private static int blend(int source, int destination) {
        int alpha = source >>> 24, inverse = 255 - alpha;
        int result = 0;
        for (int shift : new int[]{0, 8, 16, 24}) {
            int component = (((source >>> shift) & 255) * alpha
                    + ((destination >>> shift) & 255) * inverse) / 255;
            result |= component << shift;
        }
        return result;
    }

    private static Layer full(boolean transparent, int color) {
        return new Layer(0, 0, WIDTH, HEIGHT, transparent, false, color);
    }

    private static SoftwarePass baseline(List<Layer> layers) {
        SoftwarePass pass = new SoftwarePass();
        for (Layer layer : layers) if (!layer.offscreen) pass.draw(layer);
        return pass;
    }

    private static SoftwarePass optimized(List<Layer> layers, boolean allow, CompositorPolicy.Stats stats) {
        SoftwarePass pass = new SoftwarePass();
        CompositorPolicy.render(layers, WIDTH, HEIGHT, allow, pass, stats);
        assertTrue("Blend state must be restored for cursors and effects", pass.blending);
        assertArrayEquals("Optimized composition must preserve the image", baseline(layers).pixels, pass.pixels);
        return pass;
    }

    @Test public void highestOpaqueFullScreenWindowSkipsLowerDrawsAndKeepsUpperTransparency() {
        Layer desktop = full(false, 0xff113355);
        Layer oldGame = full(false, 0xffaabbcc);
        Layer popupBelow = new Layer(2, 2, 4, 4, true, false, 0x80ff0000);
        Layer game = full(false, 0x10446688);
        Layer overlay = new Layer(2, 3, 8, 7, true, false, 0x8088ff44);
        List<Layer> layers = Arrays.asList(desktop, oldGame, popupBelow, game, overlay);
        CompositorPolicy.Stats stats = new CompositorPolicy.Stats();
        SoftwarePass pass = optimized(layers, true, stats);
        assertEquals(Arrays.asList(game, overlay), pass.draws);
        assertEquals(Arrays.asList(false, true), pass.blendStates);
        assertEquals(5, stats.considered); assertEquals(3, stats.occluded);
        assertEquals(2, stats.drawn); assertEquals(1, stats.opaque); assertEquals(1, stats.transparent);
        assertEquals(2, stats.blendChanges);
        assertEquals("The original scene must remain available", 5, layers.size());
    }

    @Test public void partialLetterboxedTransparentAndOffscreenWindowsCannotOcclude() {
        Layer desktop = full(false, 0xff123456);
        Layer[] candidates = {
                new Layer(0, 0, WIDTH - 1, HEIGHT, false, false, 0xff445566),
                new Layer(0, 1, WIDTH, HEIGHT - 2, false, false, 0xff445566),
                full(true, 0x80445566),
                new Layer(0, 0, WIDTH, HEIGHT, false, true, 0xff445566),
                new Layer(0, 0, 0, HEIGHT, false, false, 0xff445566)
        };
        for (Layer candidate : candidates) {
            CompositorPolicy.Stats stats = new CompositorPolicy.Stats();
            SoftwarePass pass = optimized(Arrays.asList(desktop, candidate), true, stats);
            assertTrue(pass.draws.contains(desktop));
            assertEquals(0, stats.occluded);
        }
    }

    @Test public void oversizedNegativeOriginCoversViewportWithoutIntegerOverflow() {
        assertTrue(CompositorPolicy.coversViewport(new Layer(-3, -2, WIDTH + 6, HEIGHT + 4,
                false, false, 0), WIDTH, HEIGHT));
        assertFalse(CompositorPolicy.coversViewport(new Layer(Integer.MIN_VALUE, 0, Integer.MAX_VALUE,
                HEIGHT, false, false, 0), WIDTH, HEIGHT));
        assertFalse(CompositorPolicy.coversViewport(full(false, 0), 0, HEIGHT));
        assertFalse(CompositorPolicy.coversViewport(full(false, 0), WIDTH, -1));
    }

    @Test public void movingOrRemovingTheCoverRestoresLowerDrawsOnTheNextPass() {
        Layer desktop = full(false, 0xff112233), game = full(false, 0xffaabbcc);
        ArrayList<Layer> scene = new ArrayList<>(Arrays.asList(desktop, game));
        CompositorPolicy.Stats stats = new CompositorPolicy.Stats();
        assertEquals(Arrays.asList(game), optimized(scene, true, stats).draws);
        game.x = 1;
        assertEquals(Arrays.asList(desktop, game), optimized(scene, true, stats).draws);
        assertEquals(0, stats.occluded);
        scene.remove(game);
        assertEquals(Arrays.asList(desktop), optimized(scene, true, stats).draws);
    }

    @Test public void pointerFollowingViewRetainsTheWholeSceneAndOffscreenAccounting() {
        Layer desktop = full(false, 0xff123456), game = full(false, 0xff445566);
        Layer offscreen = new Layer(0, 0, WIDTH, HEIGHT, false, true, 0xff000000);
        CompositorPolicy.Stats stats = new CompositorPolicy.Stats();
        SoftwarePass pass = optimized(Arrays.asList(desktop, offscreen, game), false, stats);
        assertEquals(Arrays.asList(desktop, game), pass.draws);
        assertEquals(0, stats.occluded); assertEquals(1, stats.offscreen);
        pass = optimized(Arrays.asList(offscreen, desktop, game), true, stats);
        assertEquals(Arrays.asList(game), pass.draws);
        assertEquals(1, stats.occluded); assertEquals(1, stats.offscreen);
    }

    @Test public void opaqueBatchDisablesBlendOnceAndRestoresItEvenWhenDrawingFails() {
        Layer first = new Layer(0, 0, 8, HEIGHT, false, false, 0xff123456);
        Layer second = new Layer(8, 0, 8, HEIGHT, false, false, 0xff445566);
        CompositorPolicy.Stats stats = new CompositorPolicy.Stats();
        SoftwarePass pass = optimized(Arrays.asList(first, second), true, stats);
        assertEquals(Arrays.asList(false, true), pass.blendStates);
        List<Boolean> states = new ArrayList<>();
        try {
            CompositorPolicy.render(Arrays.asList(first), WIDTH, HEIGHT, true, new CompositorPolicy.Pass<Layer>() {
                @Override public void blending(boolean enabled) { states.add(enabled); }
                @Override public void draw(Layer layer) { throw new IllegalStateException("fixture"); }
            }, stats);
            fail("Fixture must throw");
        } catch (IllegalStateException expected) {
            assertEquals("fixture", expected.getMessage());
        }
        assertEquals(Arrays.asList(false, true), states);
    }

    @Test public void hiddenCursorAvoidsItsPassWhileTheMenuReticleStillRenders() {
        for (boolean visible : new boolean[]{false, true}) {
            for (boolean overlay : new boolean[]{false, true}) {
                for (boolean menu : new boolean[]{false, true}) {
                    assertEquals(visible && (overlay || menu), CompositorPolicy.needsCursorPass(visible, overlay, menu));
                }
            }
        }
        assertFalse(CompositorPolicy.needsCursorPass(true, false, false));
        assertTrue(CompositorPolicy.needsCursorPass(true, false, true));
    }
}
