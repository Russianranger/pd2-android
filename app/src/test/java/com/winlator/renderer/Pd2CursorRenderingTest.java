package com.winlator.renderer;

import com.winlator.xserver.Cursor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Covers the renderer's actual source choice without constructing the native X server. */
public final class Pd2CursorRenderingTest {
    private static Cursor guestCursor(boolean visible) {
        Cursor cursor = new Cursor(1, 3, 4, null, null, null);
        cursor.setVisible(visible);
        return cursor;
    }

    @Test public void defaultFallbackArrowAndForcedMenuArrowKeepTheirPreviousBehavior() {
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(null, false, true));
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(null, true, true));
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(guestCursor(true), true, true));
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(guestCursor(false), true, true));
    }

    @Test public void hidingTheArrowSuppressesFallbackAndForcedMenuRendering() {
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(null, false, false));
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(null, true, false));
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(guestCursor(false), false, false));
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(guestCursor(false), true, false));
    }

    @Test public void guestCursorSurvivesHidingTheArrowInNativeAndMenuModes() {
        Cursor cursor = guestCursor(true);
        assertEquals(GLRenderer.CursorSource.GUEST, GLRenderer.cursorSource(cursor, false, false));
        assertEquals(GLRenderer.CursorSource.GUEST, GLRenderer.cursorSource(cursor, true, false));
        assertEquals(GLRenderer.CursorSource.GUEST, GLRenderer.cursorSource(cursor, false, true));
    }

    @Test public void showingTheArrowAgainRestoresForcedMenuWithoutChangingTheGuestCursor() {
        Cursor cursor = guestCursor(true);
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(cursor, true, true));
        assertEquals(GLRenderer.CursorSource.GUEST, GLRenderer.cursorSource(cursor, true, false));
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(cursor, true, true));
        assertEquals(GLRenderer.CursorSource.GUEST, GLRenderer.cursorSource(cursor, false, true));
    }

    @Test public void guestHiddenCursorIsRespectedWhenTheAppArrowIsNotForced() {
        Cursor cursor = guestCursor(false);
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(cursor, false, true));
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(cursor, false, false));
    }
}
