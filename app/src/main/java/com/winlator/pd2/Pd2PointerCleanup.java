package com.winlator.pd2;

import com.winlator.xserver.Pointer;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;

/** Balance held mouse input without emitting unsolicited raw button events. */
public final class Pd2PointerCleanup {
    private Pd2PointerCleanup() { }

    public static void releaseHeldButtons(XServer xServer) {
        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
            for (Pointer.Button button : Pointer.Button.values()) {
                if (xServer.pointer.isButtonPressed(button)) xServer.injectPointerButtonRelease(button);
            }
        }
    }
}
