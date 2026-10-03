package com.winlator.pd2;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;

/** Install crash capture before Android creates manifest content providers. */
public final class Pd2Application extends Application {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Thread.UncaughtExceptionHandler androidHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                Pd2CrashLog.record(base, thread, error, "App: " + base.getPackageName()
                        + "\nAndroid: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                        + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL);
            } catch (Throwable ignored) {
                // A full disk or low-memory failure must not replace the original Android crash.
            } finally {
                if (androidHandler != null) androidHandler.uncaughtException(thread, error);
                else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            }
        });
    }
}
