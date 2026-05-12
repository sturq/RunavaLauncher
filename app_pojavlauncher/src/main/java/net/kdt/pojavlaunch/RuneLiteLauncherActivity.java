package net.kdt.pojavlaunch;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipFile;

public class RuneLiteLauncherActivity extends Activity {
    private static final String TAG = "RuneLiteLauncher";
    private static final String RUNELITE_URL = "https://github.com/runelite/launcher/releases/latest/download/RuneLite.jar";
    private static final String JAR_NAME = "RuneLite.jar";
    private static final String DIAG_FILENAME = "runelitedroid-diag.txt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File jar = new File(getFilesDir(), JAR_NAME);
        if (jar.exists() && jar.length() > 0 && isValidJar(jar)) {
            diag("cached jar OK, size=" + jar.length() + " path=" + jar.getAbsolutePath());
            launchJar(jar);
        } else {
            if (jar.exists()) {
                diag("cached jar invalid, size=" + jar.length() + " — redownloading");
                jar.delete();
            }
            downloadAndLaunch(jar);
        }
    }

    private boolean isValidJar(File f) {
        try (ZipFile z = new ZipFile(f)) {
            return z.getEntry("META-INF/MANIFEST.MF") != null;
        } catch (Throwable t) {
            diag("isValidJar failed: " + t);
            return false;
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
            tmp.delete();
            long written = 0;
            int code = -1;
            String finalUrl = "";
            String ctype = "";
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(RUNELITE_URL).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "RuneLiteDroid/1.0");
                code = conn.getResponseCode();
                finalUrl = conn.getURL().toString();
                ctype = String.valueOf(conn.getContentType());
                if (code / 100 != 2) throw new RuntimeException("HTTP " + code);
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        written += n;
                    }
                    out.flush();
                    out.getFD().sync();
                }
                diag("downloaded bytes=" + written + " code=" + code + " ctype=" + ctype + " finalUrl=" + finalUrl);
                if (!isValidJar(tmp)) throw new RuntimeException("downloaded file is not a valid JAR (size=" + written + ")");
                if (!tmp.renameTo(jar)) throw new RuntimeException("rename failed");
                diag("rename OK, jar=" + jar.getAbsolutePath() + " size=" + jar.length());
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                error = e + "\nwritten=" + written + " code=" + code + " ctype=" + ctype + "\nfinalUrl=" + finalUrl + "\n" + sw;
                diag("download FAILED: " + error);
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
        diag("launching jar: path=" + jar.getAbsolutePath() + " exists=" + jar.exists()
                + " size=" + jar.length() + " canRead=" + jar.canRead());
        Intent intent = new Intent(this, JavaGUILauncherActivity.class);
        intent.putExtra("javaArgs", "-jar " + jar.getAbsolutePath());
        startActivity(intent);
        finish();
    }

    private void diag(String msg) {
        Log.i(TAG, msg);
        try {
            File ext = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            ext.mkdirs();
            File log = new File(ext, DIAG_FILENAME);
            try (FileWriter w = new FileWriter(log, true)) {
                String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                w.write(ts + " " + msg + "\n");
            }
        } catch (Throwable t) {
            Log.w(TAG, "diag write failed", t);
        }
    }
}
