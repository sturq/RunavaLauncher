package net.kdt.pojavlaunch;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class RuneLiteLauncherActivity extends Activity {
    private static final String TAG = "RuneLiteLauncher";
    private static final String RUNELITE_URL = "https://github.com/runelite/launcher/releases/latest/download/RuneLite.jar";
    private static final String JAR_NAME = "RuneLite.jar";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File jar = new File(getFilesDir(), JAR_NAME);
        if (jar.exists() && jar.length() > 0) {
            launchJar(jar);
        } else {
            downloadAndLaunch(jar);
        }
    }

    private void downloadAndLaunch(final File jar) {
        final ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage(getString(R.string.runelite_downloading));
        dialog.setCancelable(false);
        dialog.show();

        new Thread(() -> {
            String error = null;
            File tmp = new File(jar.getAbsolutePath() + ".part");
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(RUNELITE_URL).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                int code = conn.getResponseCode();
                if (code / 100 != 2) throw new RuntimeException("HTTP " + code);
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (!tmp.renameTo(jar)) throw new RuntimeException("rename failed");
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                error = e.toString();
                tmp.delete();
            }
            final String finalError = error;
            runOnUiThread(() -> {
                try { dialog.dismiss(); } catch (Throwable ignored) {}
                if (finalError != null) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.runelite_download_failed)
                            .setMessage(finalError)
                            .setPositiveButton(android.R.string.ok, (d, w) -> downloadAndLaunch(jar))
                            .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                            .setCancelable(false)
                            .show();
                } else {
                    launchJar(jar);
                }
            });
        }, "RuneLiteDownload").start();
    }

    private void launchJar(File jar) {
        Intent intent = new Intent(this, JavaGUILauncherActivity.class);
        intent.putExtra("javaArgs", "-jar " + jar.getAbsolutePath());
        startActivity(intent);
        finish();
    }
}
