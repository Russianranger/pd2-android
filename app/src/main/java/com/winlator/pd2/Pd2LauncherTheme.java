package com.winlator.pd2;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.winlator.R;

/** Static stone surfaces and licensed fantasy headings, with platform body text for readability. */
public final class Pd2LauncherTheme {
    public static final int BONE = 0xFFE9DFCC;
    public static final int MUTED = 0xFFB8AA96;
    public static final int GOLD = 0xFFD5B47A;
    private static Typeface headingTypeface;

    private Pd2LauncherTheme() {}

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static Typeface headingTypeface(Context context) {
        if (headingTypeface == null) {
            try { headingTypeface = Typeface.createFromAsset(context.getAssets(), "fonts/CinzelDecorative-Regular.ttf"); }
            catch (RuntimeException unavailable) { headingTypeface = Typeface.create("serif", Typeface.NORMAL); }
        }
        return headingTypeface;
    }

    public static TextView heading(Context context, String text, float size) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(GOLD);
        view.setTypeface(headingTypeface(context));
        view.setLetterSpacing(0.035f);
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }

    public static TextView body(Context context, String text, float size) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(MUTED);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setLineSpacing(dp(context, 2), 1.0f);
        return view;
    }

    public static LinearLayout panel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        panel.setBackgroundResource(R.drawable.pd2_launcher_panel);
        return panel;
    }

    public static AlertDialog.Builder dialog(Context context) {
        return new AlertDialog.Builder(context, R.style.Pd2LauncherDialogTheme);
    }

    public static View dialogHeader(Context context, String title, String explanation) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 8));
        header.addView(heading(context, title, 19));
        TextView summary = body(context, explanation, 13);
        summary.setTextColor(BONE);
        summary.setPadding(0, dp(context, 7), 0, 0);
        header.addView(summary);
        return header;
    }

    public static void styleButton(Button button, boolean primary) {
        Context context = button.getContext();
        button.setAllCaps(false);
        button.setTextSize(primary ? 18 : 14);
        button.setTypeface(primary ? headingTypeface(context) : Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0xFF8B8071, primary ? 0xFFF3DEB3 : BONE}));
        button.setBackground(buttonBackground(context, primary));
        button.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        button.setMinHeight(dp(context, 54));
        button.setMinimumHeight(dp(context, 54));
        button.setMinWidth(0);
        button.setMaxLines(3);
        button.setFocusable(true);
        button.setStateListAnimator(null);
    }

    private static StateListDrawable buttonBackground(Context context, boolean primary) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, surface(context, 0xFF1B1816, 0xFF161413, 0xFF3C342B, 1));
        states.addState(new int[]{android.R.attr.state_pressed}, surface(context, 0xFF5D3828, 0xFF3A211A, 0xFFF1D095, 2));
        states.addState(new int[]{android.R.attr.state_focused}, surface(context, 0xFF403124, 0xFF251D18, GOLD, 2));
        states.addState(new int[]{}, primary
                ? surface(context, 0xFF683D2C, 0xFF391E1B, 0xFFB28C57, 1)
                : surface(context, 0xFF302921, 0xFF201B17, 0xFF675039, 1));
        return states;
    }

    private static GradientDrawable surface(Context context, int top, int bottom, int stroke, int width) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(dp(context, 2));
        drawable.setStroke(dp(context, width), stroke);
        return drawable;
    }
}
