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
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(null, false, true, true));
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(null, true, true, true));
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(guestCursor(true), true, true, true));
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(guestCursor(false), true, true, true));
    }

    @Test public void hidingTheArrowSuppressesFallbackAndForcedMenuRendering() {
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(null, false, false, true));
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(null, true, false, true));
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(guestCursor(false), false, false, true));
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(guestCursor(false), true, false, true));
    }

    @Test public void guestCursorSurvivesHidingTheArrowInNativeAndMenuModes() {
        Cursor cursor = guestCursor(true);
        assertEquals(GLRenderer.CursorSource.GUEST, GLRenderer.cursorSource(cursor, false, false, true));
        assertEquals(GLRenderer.CursorSource.GUEST, GLRenderer.cursorSource(cursor, true, false, true));
        assertEquals(GLRenderer.CursorSource.GUEST, GLRenderer.cursorSource(cursor, false, true, true));
    }

    @Test public void showingTheArrowAgainRestoresForcedMenuWithoutChangingTheGuestCursor() {
        Cursor cursor = guestCursor(true);
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(cursor, true, true, true));
        assertEquals(GLRenderer.CursorSource.GUEST, GLRenderer.cursorSource(cursor, true, false, true));
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(cursor, true, true, true));
        assertEquals(GLRenderer.CursorSource.GUEST, GLRenderer.cursorSource(cursor, false, true, true));
    }

    @Test public void guestHiddenCursorIsRespectedWhenTheAppArrowIsNotForced() {
        Cursor cursor = guestCursor(false);
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(cursor, false, true, true));
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(cursor, false, false, true));
    }

    @Test public void hidingCursorOverlaysSuppressesRootAndGuestInEveryMenuState() {
        for (Cursor cursor : new Cursor[]{null, guestCursor(true), guestCursor(false)}) {
            for (boolean forcedMenuArrow : new boolean[]{false, true}) {
                for (boolean rootArrowVisible : new boolean[]{false, true}) {
                    assertEquals(GLRenderer.CursorSource.NONE,
                            GLRenderer.cursorSource(cursor, forcedMenuArrow, rootArrowVisible, false));
                }
            }
        }
    }

    @Test public void restoringCursorOverlaysUsesTheExistingRootAndGuestPolicy() {
        Cursor cursor = guestCursor(true);
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(cursor, true, true, false));
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(cursor, true, true, true));
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(cursor, false, false, false));
        assertEquals(GLRenderer.CursorSource.GUEST, GLRenderer.cursorSource(cursor, false, false, true));
        assertEquals(GLRenderer.CursorSource.NONE, GLRenderer.cursorSource(null, false, true, false));
        assertEquals(GLRenderer.CursorSource.ROOT, GLRenderer.cursorSource(null, false, true, true));
    }

    @Test public void activeMenuReticleSurvivesHidingWhiteCursorsWithoutDrawingGuestOrRoot() {
        for (Cursor cursor : new Cursor[]{null, guestCursor(true), guestCursor(false)}) {
            for (boolean forcedMenuArrow : new boolean[]{false, true}) {
                for (boolean rootArrowVisible : new boolean[]{false, true}) {
                    assertEquals(GLRenderer.CursorSource.MENU_POINTER,
                            GLRenderer.cursorSource(cursor, forcedMenuArrow, rootArrowVisible, false, true));
                }
            }
        }
    }

    @Test public void closingMenuRouteHidesReticleWhileWhiteCursorsRemainHidden() {
        Cursor cursor = guestCursor(false);
        assertEquals(GLRenderer.CursorSource.MENU_POINTER,
                GLRenderer.cursorSource(cursor, true, false, false, true));
        assertEquals(GLRenderer.CursorSource.NONE,
                GLRenderer.cursorSource(cursor, false, false, false, false));
        assertEquals(GLRenderer.CursorSource.MENU_POINTER,
                GLRenderer.cursorSource(cursor, true, false, false, true));
    }

    @Test public void inactiveReticlePreservesNativeFullMouseAndUnhiddenMenuPolicy() {
        assertEquals(GLRenderer.CursorSource.NONE,
                GLRenderer.cursorSource(guestCursor(true), false, false, false, false));
        assertEquals(GLRenderer.CursorSource.GUEST,
                GLRenderer.cursorSource(guestCursor(true), false, true, true, false));
        assertEquals(GLRenderer.CursorSource.ROOT,
                GLRenderer.cursorSource(guestCursor(false), true, true, true, false));
    }
}
