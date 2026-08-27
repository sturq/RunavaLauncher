package net.kdt.pojavlaunch;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;

/**
 * Runs the desktop GL capability probe and writes the answer to
 * gl-probe-result.txt in the app's external files directory.
 *
 * A service in its own process, for two reasons. It is out of the game's
 * process because an earlier version ran on a timer inside the game activity,
 * and when the probe took that process down Android restarted the activity and
 * it happened again: a restart loop on the thing people actually use. And it is
 * a service rather than a broadcast receiver because receivers cannot be
 * instantiated in this app at all: PojavApplication wraps the base context in
 * LocaleUtils, and the framework casts that base context to ContextImpl while
 * setting a receiver up.
 *
 * Trigger:
 *   am start-service -n <package>/net.kdt.pojavlaunch.GlProbeService
 */
public class GlProbeService extends Service {
    private static final String TAG = "GlProbe";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            String result;
            android.media.ImageReader reader = null;
            try {
                GlProbe.ensureLoaded();
                // A real window without anything on screen. This Mesa is the
                // kopper build of zink, whose WSI layer expects a window and
                // crashes without one, but an ImageReader's surface is never
                // displayed, so the probe still needs no activity.
                reader = android.media.ImageReader.newInstance(
                        64, 64, android.graphics.PixelFormat.RGBA_8888, 2);
                result = GlProbe.probeDesktopGL(
                        getApplicationInfo().nativeLibraryDir, reader.getSurface());
            } catch (Throwable t) {
                result = "probe threw: " + t;
            }
            Log.i(TAG, "result:\n" + result);
            try {
                File out = new File(getExternalFilesDir(null), "gl-probe-result.txt");
                try (FileWriter w = new FileWriter(out)) {
                    w.write(result);
                }
                Log.i(TAG, "written to " + out);
            } catch (Throwable t) {
                Log.w(TAG, "could not write the result", t);
            }
            if (reader != null) reader.close();
            stopSelf();
        }, "GLProbe").start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
