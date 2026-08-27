package net.kdt.pojavlaunch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import net.kdt.pojavlaunch.utils.JREUtils;

import java.io.File;
import java.io.FileWriter;

/**
 * Runs the desktop GL capability probe and writes the answer to
 * gl-probe-result.txt in the app's external files directory.
 *
 * Deliberately a receiver in its own process. An earlier version ran the probe
 * from the game activity on a timer, and when the probe took the process down
 * the activity came straight back up and did it again, which is a restart loop
 * on the thing people actually use. Nothing here shares a process with the game,
 * so the worst this can do is kill itself.
 *
 * Trigger:
 *   am broadcast -a net.kdt.pojavlaunch.GL_PROBE \
 *     -n <package>/net.kdt.pojavlaunch.GlProbeReceiver
 */
public class GlProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "GlProbe";

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            String result;
            try {
                result = JREUtils.probeDesktopGL(app.getApplicationInfo().nativeLibraryDir);
            } catch (Throwable t) {
                result = "probe threw: " + t;
            }
            Log.i(TAG, "result:\n" + result);
            try {
                File out = new File(app.getExternalFilesDir(null), "gl-probe-result.txt");
                try (FileWriter w = new FileWriter(out)) {
                    w.write(result);
                }
                Log.i(TAG, "written to " + out);
            } catch (Throwable t) {
                Log.w(TAG, "could not write the result", t);
            }
            pending.finish();
        }, "GLProbe").start();
    }
}
