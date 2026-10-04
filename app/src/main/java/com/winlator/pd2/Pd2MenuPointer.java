package com.winlator.pd2;

import android.os.SystemClock;

import com.winlator.winhandler.MouseEventFlags;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Cursor;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.Window;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Temporary front-end input through the existing Windows input helper, independent of X grabs. */
public final class Pd2MenuPointer implements Pd2InputRouter.MenuInput {
    private static final long FEEDBACK_TIMEOUT_MS = 125;
    private final XServer xServer;
    private final WinHandler winHandler;
    private final EnumSet<Pointer.Button> buttons = EnumSet.noneOf(Pointer.Button.class);
    private final EnumSet<XKeycode> keys = EnumSet.noneOf(XKeycode.class);
    private volatile boolean active;
    private volatile long generation;
    private boolean movePending;
    private boolean initializing;
    private boolean recenterPending;
    private long moveSentAt;
    private int feedbackX, feedbackY;
    private boolean hasFeedback;

    public Pd2MenuPointer(XServer xServer, WinHandler winHandler) {
        this.xServer = xServer;
        this.winHandler = winHandler;
    }

    public synchronized void activate() {
        if (active) return;
        generation++;
        active = true;
        movePending = false;
        hasFeedback = false;
        initializing = recenterPending = true;
        try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            Bounds bounds = selectBounds();
            if (winHandler.isInputReady()) {
                final long epoch = generation;
                winHandler.bringToFront("Game.exe", bounds.game != null ? bounds.game.getHandle() : 0,
                        () -> active && generation == epoch);
            }
            // A zero move asks the Windows helper for GetCursorPos before using any cached X position.
            sendMove(0, 0);
        }
    }

    /** Restore only the game's foreground window; native recovery never moves or clicks the pointer. */
    public synchronized void reacquireGameWindow(BooleanSupplier allowed) {
        try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            Bounds bounds = selectBounds();
            winHandler.bringToFront("Game.exe", bounds.game != null ? bounds.game.getHandle() : 0, allowed);
        }
    }

    public synchronized boolean belongsToGame(Window window) {
        try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            return gameAncestor(window) != null;
        }
    }

    /** Also balances controls if a caller disables the route before releasing its router state. */
    public synchronized void deactivate() {
        active = false;
        generation++;
        movePending = false;
        hasFeedback = false;
        initializing = recenterPending = false;
        for (Pointer.Button button : EnumSet.copyOf(buttons)) button(button, false);
        for (XKeycode key : EnumSet.copyOf(keys)) key(key, false);
    }

    public synchronized void onCursorFeedback(int x, int y) {
        if (!active) return;
        feedbackX = x;
        feedbackY = y;
        hasFeedback = true;
        movePending = false;
        if (recenterPending) {
            recenterPending = false;
            try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                Bounds bounds = selectBounds();
                sendMove(bounds.left + (bounds.right - bounds.left) / 2 - x,
                        bounds.top + (bounds.bottom - bounds.top) / 2 - y);
            }
        } else initializing = false;
    }

    @Override public synchronized void move(int dx, int dy) {
        if (!active || !winHandler.isInputReady()) return;
        if (movePending && SystemClock.uptimeMillis() - moveSentAt < FEEDBACK_TIMEOUT_MS) return;
        if (initializing) {
            // A lost initial reply must not turn stale desktop coordinates into a relative warp.
            recenterPending = true;
            sendMove(0, 0);
            return;
        }
        try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            Bounds bounds = selectBounds();
            int x = hasFeedback ? feedbackX : xServer.pointer.getX();
            int y = hasFeedback ? feedbackY : xServer.pointer.getY();
            int targetX = clamp((long)x + dx, bounds.left, bounds.right - 1);
            int targetY = clamp((long)y + dy, bounds.top, bounds.bottom - 1);
            if (targetX != x || targetY != y) sendMove(targetX - x, targetY - y);
        }
    }

    private void sendMove(int dx, int dy) {
        if (!active || !winHandler.isInputReady()) return;
        final long epoch = generation;
        BooleanSupplier allowed = () -> active && generation == epoch;
        winHandler.mouseEvent(MouseEventFlags.MOVE, clamp(dx, Short.MIN_VALUE, Short.MAX_VALUE),
                clamp(dy, Short.MIN_VALUE, Short.MAX_VALUE), 0, allowed);
        winHandler.controllerDiagnostics.recordPointerOutput("move");
        movePending = true;
        moveSentAt = SystemClock.uptimeMillis();
    }

    @Override public synchronized void button(Pointer.Button button, boolean down) {
        if (button != Pointer.Button.BUTTON_LEFT) return;
        if (down) {
            if (!active || initializing || !winHandler.isInputReady() || !buttons.add(button)) return;
            final long epoch = generation;
            winHandler.mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0, () -> active && generation == epoch);
        } else {
            if (!buttons.remove(button)) return;
            if (!winHandler.isInputReady()) return;
            winHandler.mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0, () -> true);
        }
        winHandler.controllerDiagnostics.recordPointerOutput("button");
    }

    @Override public synchronized void key(XKeycode key, boolean down) {
        int vkey = virtualKey(key);
        if (vkey == 0) return;
        if (down) {
            if (!active || !winHandler.isInputReady() || !keys.add(key)) return;
            final long epoch = generation;
            winHandler.keyboardEvent((byte)vkey, 0, () -> active && generation == epoch);
        } else {
            if (!keys.remove(key)) return;
            if (!winHandler.isInputReady()) return;
            winHandler.keyboardEvent((byte)vkey, 2, () -> true);
        }
        winHandler.controllerDiagnostics.recordPointerOutput("key");
    }

    private static int virtualKey(XKeycode key) {
        switch (key) {
            case KEY_ESC: return 0x1b;
            case KEY_ENTER: return 0x0d;
            case KEY_TAB: return 0x09;
            case KEY_LEFT: return 0x25;
            case KEY_UP: return 0x26;
            case KEY_RIGHT: return 0x27;
            case KEY_DOWN: return 0x28;
            default: return 0;
        }
    }

    private static int clamp(long value, int min, int max) {
        return (int)Math.max(min, Math.min(max, value));
    }

    private static boolean viewable(Window window) {
        return window != null && window.getMapState() == Window.MapState.VIEWABLE
                && window.isInputOutput() && window.getWidth() > 1 && window.getHeight() > 1;
    }

    private boolean identifiesGame(Window window) {
        if (!viewable(window) || window == xServer.windowManager.rootWindow || window.isDesktopWindow()) return false;
        String title = window.getName().toLowerCase(Locale.ROOT);
        String windowClass = window.getClassName().replace('\0', ' ').toLowerCase(Locale.ROOT);
        return title.contains("diablo ii") || title.contains("project diablo")
                || windowClass.contains("game.exe") || windowClass.contains("diablo ii");
    }

    private Window gameAncestor(Window window) {
        for (int depth = 0; window != null && depth < 32; depth++, window = window.getParent())
            if (identifiesGame(window)) return window;
        return null;
    }

    private Window topmostGame(Window window, int depth) {
        if (window == null || depth > 32 || window.getMapState() != Window.MapState.VIEWABLE) return null;
        for (int index = window.getChildren().size() - 1; index >= 0; index--) {
            Window candidate = topmostGame(window.getChildren().get(index), depth + 1);
            if (candidate != null) return candidate;
        }
        return identifiesGame(window) ? window : null;
    }

    private Window clientWindow(Window game) {
        Window result = game;
        for (int depth = 0; depth < 32; depth++) {
            Window selected = null;
            long selectedArea = -1;
            for (int index = result.getChildren().size() - 1; index >= 0; index--) {
                Window child = result.getChildren().get(index);
                if (!viewable(child) || child.isDesktopWindow()) continue;
                if (child.getProcessId() != 0 && game.getProcessId() != 0 && child.getProcessId() != game.getProcessId()) continue;
                long area = (long)child.getWidth() * child.getHeight();
                // A marked client surface takes priority over ordinary child controls.
                if (child.isSurface()) area += 1L << 32;
                if (area > selectedArea) { selected = child; selectedArea = area; }
            }
            if (selected == null) break;
            result = selected;
        }
        return result;
    }

    private Bounds selectBounds() {
        Window game = gameAncestor(xServer.windowManager.getFocusedWindow());
        if (game == null) game = topmostGame(xServer.windowManager.rootWindow, 0);
        Window client = game != null ? clientWindow(game) : null;
        int left = 0, top = 0, right = xServer.screenInfo.width, bottom = xServer.screenInfo.height;
        if (client != null) {
            for (Window window = client; window != null && window != xServer.windowManager.rootWindow; window = window.getParent()) {
                left = Math.max(left, window.getRootX());
                top = Math.max(top, window.getRootY());
                right = Math.min(right, window.getRootX() + window.getWidth());
                bottom = Math.min(bottom, window.getRootY() + window.getHeight());
            }
        }
        if (right <= left || bottom <= top) return new Bounds(null, null, 0, 0, xServer.screenInfo.width, xServer.screenInfo.height);
        return new Bounds(game, client, left, top, right, bottom);
    }

    /** Whitelisted geometry only; never window titles, paths, key codes or button states. */
    public synchronized Map<String, Object> captureContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            Bounds bounds = selectBounds();
            context.put("screenWidth", (int)xServer.screenInfo.width);
            context.put("screenHeight", (int)xServer.screenInfo.height);
            context.put("pointerX", (int)xServer.pointer.getX());
            context.put("pointerY", (int)xServer.pointer.getY());
            context.put("relative", xServer.isRelativeMouseMovement());
            // Cursor feedback updates raw coordinates without emitting a synthetic X motion.
            Window point = xServer.windowManager.findPointWindow(xServer.pointer.getClampedX(),
                    xServer.pointer.getClampedY(), true);
            if (point == null) point = xServer.windowManager.rootWindow;
            Cursor cursor = point != null ? point.attributes.getCursor() : null;
            context.put("gameCursorVisible", cursor == null || cursor.isVisible());
            context.put("forceRoot", xServer.getRenderer() != null && xServer.getRenderer().isForceRootCursor());
            context.put("focusWindow", windowContext(xServer.windowManager.getFocusedWindow()));
            context.put("pointWindow", windowContext(point));
            context.put("grabWindow", windowContext(xServer.grabManager.getWindow()));
            context.put("menuWindow", windowContext(bounds.client));
        }
        return context;
    }

    private static Map<String, Object> windowContext(Window window) {
        if (window == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", window.id);
        result.put("x", (int)window.getRootX());
        result.put("y", (int)window.getRootY());
        result.put("width", (int)window.getWidth());
        result.put("height", (int)window.getHeight());
        String windowClass = window.getClassName().replace('\0', ' ').trim();
        if (windowClass.matches("[A-Za-z0-9_. -]{1,96}")) result.put("class", windowClass);
        return result;
    }

    private static final class Bounds {
        final Window game, client;
        final int left, top, right, bottom;
        Bounds(Window game, Window client, int left, int top, int right, int bottom) {
            this.game = game; this.client = client;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
    }
}
