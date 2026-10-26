package com.winlator.pd2;

import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real native Android/Skia previews; these do not exercise Wine or the game renderer. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class Pd2LauncherPreviewTest {
    private static Thread.UncaughtExceptionHandler hostHandler;

    @BeforeClass public static void preserveHandler() { hostHandler = Thread.getDefaultUncaughtExceptionHandler(); }
    @After public void restoreHandler() { Thread.setDefaultUncaughtExceptionHandler(hostHandler); }
    @AfterClass public static void restoreFinalHandler() { Thread.setDefaultUncaughtExceptionHandler(hostHandler); }

    @Test @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    public void thorLandscapeRendersReadableActionsAndFocusReachesToolsBelowTheFold() throws Exception {
        try (ActivityController<Pd2Activity> controller = Robolectric.buildActivity(Pd2Activity.class)) {
            Pd2Activity activity = controller.setup().get();
            settle();
            View content = activity.findViewById(android.R.id.content);
            layout(content, 1280, 720);
            assertActionsFit(content);
            save(content, "pd2-launcher-landscape.png");

            Button first = (Button) findText(content, "Prepare runtime");
            assertNotNull(first);
            assertTrue(first.requestFocus());
            boolean toolsReached = false;
            for (int i = 0; i < 48; i++) {
                View focused = activity.getCurrentFocus();
                if (focused instanceof TextView && "Advanced runtime settings".contentEquals(((TextView) focused).getText())) {
                    toolsReached = true;
                    break;
                }
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
            }
            assertTrue("D-pad navigation must reach the support/tools actions below the first viewport", toolsReached);
            save(content, "pd2-launcher-landscape-tools.png");

            previewDialog(activity, "showNativeInputSettings", "pd2-native-input-landscape.png", 1184, 672);
            previewDialog(activity, "showPerformanceSettings", "pd2-performance-landscape.png", 1184, 672);
        }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port-xhdpi")
    public void narrowPortraitWithLargerTextKeepsAllActionsReadableAndDialogsUsable() throws Exception {
        float previousFontScale = RuntimeEnvironment.getFontScale();
        RuntimeEnvironment.setFontScale(1.3f);
        try (ActivityController<Pd2Activity> controller = Robolectric.buildActivity(Pd2Activity.class)) {
            Pd2Activity activity = controller.setup().get();
            assertEquals(1.3f, activity.getResources().getConfiguration().fontScale, 0.001f);
            settle();
            View content = activity.findViewById(android.R.id.content);
            layout(content, 720, 1280);
            assertActionsFit(content);
            save(content, "pd2-launcher-portrait-large-font.png");
            previewDialog(activity, "showNativeInputSettings", "pd2-native-input-portrait-large-font.png", 656, 1184);
            previewDialog(activity, "showInputTraceSettings", "pd2-trace-portrait-large-font.png", 656, 1184);
        } finally {
            RuntimeEnvironment.setFontScale(previousFontScale);
        }
    }

    private static void settle() throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (!Pd2Activity.isOperationInProgress()) break;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        assertFalse("Read-only installation validation should finish before rendering", Pd2Activity.isOperationInProgress());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(520));
    }

    private static void assertActionsFit(View content) {
        String[] labels = {"Play", "Stop client", "Prepare runtime", "Launch settings", "Performance & temperature",
                "Import installation folder", "Import installation ZIP", "Export support logs", "Advanced runtime settings"};
        for (String label : labels) {
            View found = findText(content, label);
            assertTrue("Missing launcher action: " + label, found instanceof Button);
            assertLabelFits((TextView) found);
            assertTrue("Launcher actions must remain focusable: " + label, found.isFocusable());
        }
    }

    private static void assertLabelFits(TextView text) {
        assertTrue(text.getText() + " has no layout width", text.getWidth() > 0);
        assertTrue(text.getText() + " has no layout height", text.getHeight() > 0);
        assertNotNull(text.getLayout());
        int lines = text.getLayout().getLineCount();
        assertTrue(lines > 0);
        int availableWidth = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        int availableHeight = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
        for (int i = 0; i < lines; i++) {
            assertEquals("Action label must not be ellipsized: " + text.getText(), 0, text.getLayout().getEllipsisCount(i));
            assertTrue("Action label exceeds its width: " + text.getText(), text.getLayout().getLineWidth(i) <= availableWidth + 1);
        }
        assertEquals("Action label must retain its complete text", text.length(), text.getLayout().getLineEnd(lines - 1));
        assertTrue("Action label is clipped vertically: " + text.getText(), text.getLayout().getLineBottom(lines - 1) <= availableHeight + 1);
    }

    private static void previewDialog(Pd2Activity activity, String methodName, String fileName,
                                      int maxWidth, int maxHeight) throws Exception {
        Method method = Pd2Activity.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(activity);
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        View decor = dialog.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, maxWidth, decor.getMeasuredHeight());
        assertNotNull(dialog.getListView());
        assertTrue("Choice lists must be attached and visible", dialog.getListView().isShown());
        assertTrue("A choice list needs usable height", dialog.getListView().getHeight() >= 48);
        Button close = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        assertTrue("Close must remain visible", close.isShown());
        assertLabelFits(close);
        save(decor, fileName);
        dialog.dismiss();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static void save(View view, String name) throws Exception {
        File directory = new File("build/outputs/ui-previews");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            view.draw(new Canvas(bitmap));
            try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally { bitmap.recycle(); }
    }

    private static View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findText(group.getChildAt(i), text);
                if (match != null) return match;
            }
        }
        return null;
    }
}
