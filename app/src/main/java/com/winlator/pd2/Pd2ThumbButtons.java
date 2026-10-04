package com.winlator.pd2;

import java.util.HashMap;

/** Resolves the reserved two-stick shortcut without delaying Native thumb input. */
public final class Pd2ThumbButtons {
    public enum Action { NONE, NATIVE_DOWN, NATIVE_UP, POINTER_TAP, OPEN_MENU }

    private static final class Pad {
        final boolean[] down = new boolean[2];
        final boolean[] deferred = new boolean[2];
        final boolean[] delivered = new boolean[2];
        boolean chord;
    }

    private final HashMap<Integer, Pad> pads = new HashMap<>();
    private boolean routeKnown;
    private boolean pointerRoute;

    /** Call alongside the existing input release when focus, modal or mode ownership changes. */
    public void clear() {
        pads.clear();
        routeKnown = false;
    }

    public Action event(int deviceId, boolean left, boolean down, boolean repeat,
                        boolean pointerControls, boolean inputAvailable) {
        if (!inputAvailable) { clear(); return Action.NONE; }
        if (repeat) return Action.NONE;
        if (routeKnown && pointerRoute != pointerControls) clear();
        routeKnown = true;
        pointerRoute = pointerControls;
        Pad pad = pads.get(deviceId);
        int side = left ? 0 : 1;
        if (down) {
            if (pad == null) { pad = new Pad(); pads.put(deviceId, pad); }
            if (pad.down[side]) return Action.NONE;
            pad.down[side] = true;
            pad.deferred[side] = pointerControls;
            if (pad.down[0] && pad.down[1] && !pad.chord) {
                pad.chord = true;
                pad.deferred[0] = pad.deferred[1] = false;
                // OPEN_MENU's caller releases any first Native thumb already delivered.
                pad.delivered[0] = pad.delivered[1] = false;
                return Action.OPEN_MENU;
            }
            if (pad.chord || pointerControls) return Action.NONE;
            pad.delivered[side] = true;
            return Action.NATIVE_DOWN;
        }
        if (pad == null) return Action.NONE;
        Action action = Action.NONE;
        if (!pad.chord) {
            if (pointerControls && pad.deferred[side]) action = Action.POINTER_TAP;
            else if (!pointerControls && pad.delivered[side]) action = Action.NATIVE_UP;
        }
        pad.down[side] = pad.deferred[side] = pad.delivered[side] = false;
        if (!pad.down[0] && !pad.down[1]) pads.remove(deviceId);
        return action;
    }
}
