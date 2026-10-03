package com.winlator.pd2;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Route recovery before instantiating any AppCompat launcher or game runtime class. */
public final class Pd2EntryActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String destination = Pd2CrashLog.hasPending(this)
                ? "com.winlator.pd2.Pd2RecoveryActivity" : "com.winlator.pd2.Pd2Activity";
        startActivity(new Intent().setClassName(getPackageName(), destination));
        finish();
    }
}
