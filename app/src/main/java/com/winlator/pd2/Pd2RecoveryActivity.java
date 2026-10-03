package com.winlator.pd2;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

/** Minimal platform UI: independent of AppCompat, launcher styles and the game runtime. */
public final class Pd2RecoveryActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        scroll.addView(layout);
        TextView title = new TextView(this);
        title.setText("PD2 Android recovery"); title.setTextSize(24);
        layout.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText("The app closed after a Java error. Its last crash report is saved. Export the details so the failure can be diagnosed, or retry the launcher. Your imported installation is kept.");
        explanation.setTextSize(17); explanation.setPadding(0, padding, 0, padding);
        layout.addView(explanation);
        Button export = new Button(this);
        export.setText("Export crash details"); export.setAllCaps(false);
        export.setOnClickListener(view -> shareCrash()); layout.addView(export);
        Button retry = new Button(this);
        retry.setText("Retry launcher"); retry.setAllCaps(false);
        retry.setOnClickListener(view -> {
            Pd2CrashLog.acknowledge(this);
            startActivity(new Intent().setClassName(getPackageName(), "com.winlator.pd2.Pd2Activity"));
            finish();
        });
        layout.addView(retry);
        setContentView(scroll); export.requestFocus();
    }

    private void shareCrash() {
        File crash = Pd2CrashLog.getCrashFile(this);
        if (!crash.isFile()) { Toast.makeText(this, "The crash report is unavailable.", Toast.LENGTH_LONG).show(); return; }
        try {
            android.net.Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".FileProvider", crash);
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "PD2 Android crash report")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri("PD2 Android crash report", uri));
            startActivity(Intent.createChooser(send, "Export crash details"));
        } catch (RuntimeException error) {
            Toast.makeText(this, "Cannot open sharing: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                View focused = getCurrentFocus(); if (focused != null) focused.performClick();
            }
            return true;
        }
        // Leave the pending report intact when returning home instead of explicitly retrying.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
