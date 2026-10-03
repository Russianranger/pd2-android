package com.winlator.pd2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

/** Protects an explicit import/export job while Android's document picker or another app is visible. */
public final class Pd2WorkService extends Service {
    private PowerManager.WakeLock wakeLock;
    public static void start(Context context) {
        context.startForegroundService(new Intent(context, Pd2WorkService.class));
    }
    public static void stop(Context context) {
        context.stopService(new Intent(context, Pd2WorkService.class));
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("pd2-import", "PD2 installation", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, Pd2Activity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "pd2-import")
            .setSmallIcon(com.winlator.R.drawable.pd2_icon).setContentTitle("PD2 Android")
            .setContentText("Copying or checking your installation").setContentIntent(open).setOngoing(true).build();
        startForeground(4102, notification);
        PowerManager power = (PowerManager)getSystemService(POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pd2:import");
        wakeLock.acquire(30 * 60 * 1000L);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
    @Override public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
