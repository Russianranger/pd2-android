package com.winlator.pd2;

import android.app.Dialog;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.ListView;

import com.winlator.R;
import com.winlator.inputcontrols.ExternalController;

/** Explicit handheld controls for Android dialogs, independently of gameplay input. */
public final class Pd2ControllerDialogs {
    private Pd2ControllerDialogs() {}

    private static ListView focusedList(Dialog dialog) {
        View view = dialog.getCurrentFocus();
        while (view != null) {
            if (view instanceof ListView) return (ListView) view;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    public static void enable(Dialog dialog) {
        dialog.setOnKeyListener((window, code, event) -> {
            if (code != KeyEvent.KEYCODE_BUTTON_A && code != KeyEvent.KEYCODE_BUTTON_B) return false;
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return true;
            if (code == KeyEvent.KEYCODE_BUTTON_B) {
                View cancel = dialog.findViewById(R.id.BTCancel);
                if (cancel == null || cancel.getVisibility() != View.VISIBLE) cancel = dialog.findViewById(android.R.id.button2);
                if (cancel != null && cancel.getVisibility() == View.VISIBLE) cancel.performClick();
                else dialog.cancel();
                return true;
            }
            ListView list = focusedList(dialog);
            if (list != null && list.getCount() > 0) {
                int position = Math.max(0, list.getSelectedItemPosition());
                list.performItemClick(list.getSelectedView(), position, list.getItemIdAtPosition(position));
                return true;
            }
            View focus = dialog.getCurrentFocus();
            if (focus != null && focus.isClickable() && focus.performClick()) return true;
            View confirm = dialog.findViewById(R.id.BTConfirm);
            if (confirm == null || confirm.getVisibility() != View.VISIBLE) confirm = dialog.findViewById(android.R.id.button1);
            if (confirm != null) confirm.performClick();
            return true;
        });
        if (dialog.getWindow() == null) return;
        View decor = dialog.getWindow().getDecorView();
        final long[] lastMove = {0};
        decor.setOnGenericMotionListener((view, event) -> {
            if (!ExternalController.isJoystickDevice(event)) return false;
            float y = event.getAxisValue(MotionEvent.AXIS_Y);
            float x = event.getAxisValue(MotionEvent.AXIS_X);
            if (Math.abs(y) < 0.55f && Math.abs(x) < 0.55f) { lastMove[0] = 0; return true; }
            long now = SystemClock.uptimeMillis();
            if (now - lastMove[0] < 200) return true;
            lastMove[0] = now;
            ListView list = focusedList(dialog);
            if (list != null && list.getCount() > 0 && Math.abs(y) >= Math.abs(x)) {
                int position = list.getSelectedItemPosition();
                if (position < 0) position = 0;
                else position = Math.max(0, Math.min(list.getCount() - 1, position + (y > 0 ? 1 : -1)));
                list.setSelection(position);
            } else {
                View current = dialog.getCurrentFocus();
                int direction = Math.abs(y) >= Math.abs(x) ? (y > 0 ? View.FOCUS_DOWN : View.FOCUS_UP) : (x > 0 ? View.FOCUS_RIGHT : View.FOCUS_LEFT);
                View next = current != null ? current.focusSearch(direction) : decor.focusSearch(direction);
                if (next != null) next.requestFocus();
            }
            return true;
        });
    }
}
