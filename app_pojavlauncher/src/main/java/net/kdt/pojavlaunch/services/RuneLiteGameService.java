package net.kdt.pojavlaunch.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.RuneLiteGameActivity;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.NotificationUtils;

/**
 * Foreground service that lives in the same :runelitegame process as the JVM,
 * so Android won't reap the process while the user has the app backgrounded.
 *
 * Without this, switching away from the app for ~10s and back lets Android
 * kill :runelitegame for memory, and the JVM dies mid-AWT-dispatch with a
 * SIGSEGV in libjvm.so (seen in runelitedroid-jvm.log). Returning to the app
 * just re-launches RuneLite from scratch (re-downloads, re-installs the JRE
 * runtime entry, etc.). The notification keeps the process priority high
 * enough that Android leaves it alone.
 */
public class RuneLiteGameService extends Service {

    private PowerManager.WakeLock mWakeLock;

    @Override
    public void onCreate() {
        Tools.buildNotificationChannel(getApplicationContext());
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "RunavaLauncher:JvmKeepAlive");
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire();
        } catch (Throwable ignored) {
            // WakeLock is a bonus — the foreground service already keeps the
            // process up. Don't fail the service if Power isn't available.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("kill", false)) {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                try { mWakeLock.release(); } catch (Throwable ignored) {}
            }
            stopSelf();
            Process.killProcess(Process.myPid());
            return START_NOT_STICKY;
        }

        Intent killIntent = new Intent(getApplicationContext(), RuneLiteGameService.class);
        killIntent.putExtra("kill", true);
        PendingIntent pendingKillIntent = PendingIntent.getService(this,
                NotificationUtils.PENDINGINTENT_CODE_KILL_RUNELITE_SERVICE,
                killIntent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, RuneLiteGameActivity.class).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, "channel_id")
                .setContentTitle("RunavaLauncher")
                .setContentText("RuneLite is running")
                .setContentIntent(contentIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Quit", pendingKillIntent)
                .setSmallIcon(R.drawable.notif_icon)
                .setNotificationSilent();

        Notification notification = b.build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NotificationUtils.NOTIFICATION_ID_RUNELITE_SERVICE, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NotificationUtils.NOTIFICATION_ID_RUNELITE_SERVICE, notification);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            try { mWakeLock.release(); } catch (Throwable ignored) {}
        }
        stopSelf();
        Process.killProcess(Process.myPid());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
