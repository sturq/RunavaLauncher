package net.kdt.pojavlaunch;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import net.kdt.pojavlaunch.multirt.MultiRTUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class RuneLiteLauncherActivity extends Activity {
    private static final String TAG = "RuneLiteLauncher";
    private static final String RUNELITE_URL = "https://github.com/runelite/launcher/releases/latest/download/RuneLite.jar";
    private static final String JAR_NAME = "RuneLite.jar";
    private static final String DIAG_FILENAME = "runelitedroid-diag.txt";
    private static final String JRE17_NAME = "Internal-17";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyLauncherPrefs();
        copyRuneLiteLogToDownloads();
        ensureJre17Async(() -> {
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
        });
    }

    private void ensureJre17Async(Runnable onReady) {
        try {
            String installed = MultiRTUtils.readInternalRuntimeVersion(JRE17_NAME);
            String packaged = Tools.read(getAssets().open("components/jre-new/version"));
            if (packaged.equals(installed)) {
                diag("JRE 17 already installed v=" + installed);
                onReady.run();
                return;
            }
            diag("JRE 17 needs install: packaged=" + packaged + " installed=" + installed);
        } catch (Throwable t) {
            diag("JRE 17 version check failed: " + t);
        }

        final ProgressDialog dlg = new ProgressDialog(this);
        dlg.setMessage(getString(R.string.runelite_installing_jre));
        dlg.setCancelable(false);
        dlg.show();
        new Thread(() -> {
            String error = null;
            try {
                AssetManager am = getAssets();
                String packaged = Tools.read(am.open("components/jre-new/version"));
                String arch = Architecture.archAsString(Tools.DEVICE_ARCHITECTURE);
                MultiRTUtils.installRuntimeNamedBinpack(
                        am.open("components/jre-new/universal.tar.xz"),
                        am.open("components/jre-new/bin-" + arch + ".tar.xz"),
                        JRE17_NAME, packaged);
                MultiRTUtils.postPrepare(JRE17_NAME);
                diag("JRE 17 unpacked OK, arch=" + arch + " v=" + packaged);
            } catch (Throwable t) {
                Log.e(TAG, "JRE 17 install failed", t);
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                error = t + "\n" + sw;
                diag("JRE 17 install FAILED: " + error);
            }
            final String finalError = error;
            runOnUiThread(() -> {
                try { dlg.dismiss(); } catch (Throwable ignored) {}
                if (finalError != null) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.runelite_jre_install_failed)
                            .setMessage(finalError)
                            .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                            .show();
                } else {
                    onReady.run();
                }
            });
        }, "JreInstall").start();
    }

    private void applyLauncherPrefs() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        boolean changed = false;
        if (!JRE17_NAME.equals(p.getString("defaultRuntime", ""))) {
            p.edit().putString("defaultRuntime", JRE17_NAME).commit();
            changed = true;
        }
        if (!p.getBoolean("disable_autojre_select", false)) {
            p.edit().putBoolean("disable_autojre_select", true).commit();
            changed = true;
        }
        // Pojav's ProGrade SecurityManager sandbox breaks RuneLite (it reads system props,
        // opens files, makes HTTPS calls that the default policy doesn't allow).
        if (p.getBoolean("java_sandbox", true)) {
            p.edit().putBoolean("java_sandbox", false).commit();
            changed = true;
        }
        diag("applyLauncherPrefs changed=" + changed
                + " defaultRuntime=" + p.getString("defaultRuntime", "")
                + " disable_autojre_select=" + p.getBoolean("disable_autojre_select", false)
                + " java_sandbox=" + p.getBoolean("java_sandbox", true));
    }

    private boolean isValidJar(File f) {
        try (ZipFile z = new ZipFile(f)) {
            return z.getEntry("META-INF/MANIFEST.MF") != null;
        } catch (Throwable t) {
            diag("isValidJar failed: " + t);
            return false;
        }
    }

    /** Re-pack a zip that Android's strict ZipFile won't open (duplicate entries, etc).
     *  Keeps the LAST occurrence of each entry name — matches OpenJDK's last-wins semantics. */
    private void dedupeJar(File src, File dst) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int dupCount = 0;
        try (FileInputStream fis = new FileInputStream(src);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                if (entries.put(e.getName(), bos.toByteArray()) != null) dupCount++;
            }
        }
        try (FileOutputStream fos = new FileOutputStream(dst);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        diag("dedupeJar: entries=" + entries.size() + " duplicatesDropped=" + dupCount
                + " src=" + src.length() + " dst=" + dst.length());
    }

    private void downloadAndLaunch(final File jar) {
        final ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage(getString(R.string.runelite_downloading));
        dialog.setCancelable(false);
        dialog.show();

        new Thread(() -> {
            String error = null;
            File tmp = new File(jar.getAbsolutePath() + ".part");
            File deduped = new File(jar.getAbsolutePath() + ".dedup");
            tmp.delete();
            deduped.delete();
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
                diag("downloaded bytes=" + written + " code=" + code + " ctype=" + ctype);
                File toRename = tmp;
                if (!isValidJar(tmp)) {
                    diag("strict ZipFile rejected raw jar — repacking");
                    dedupeJar(tmp, deduped);
                    if (!isValidJar(deduped)) throw new RuntimeException("repacked jar still invalid (size=" + deduped.length() + ")");
                    tmp.delete();
                    toRename = deduped;
                }
                jar.delete();
                if (!toRename.renameTo(jar)) throw new RuntimeException("rename failed");
                diag("jar ready, path=" + jar.getAbsolutePath() + " size=" + jar.length());
            } catch (Exception e) {
                Log.e(TAG, "Download/repack failed", e);
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                error = e + "\nwritten=" + written + " code=" + code + " ctype=" + ctype + "\nfinalUrl=" + finalUrl + "\n" + sw;
                diag("download FAILED: " + error);
                tmp.delete();
                deduped.delete();
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
        Toast.makeText(this, "Launch: " + jar.length() + " bytes", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(this, RuneLiteGameActivity.class);
        // --mode AUTO: let RuneLite's GPU plugin try to claim the GLFW context Pojav
        // bridges to our SurfaceView in RuneLiteGameActivity. Falls back to software
        // rendering if the GL init can't find a context.
        // --launch-mode REFLECT: don't fork/exec a child JVM for the client — Android
        // sandboxes block that (errno 13 from ProcessBuilder.start). Run in-process via
        // ReflectionLauncher instead.
        intent.putExtra(RuneLiteGameActivity.EXTRA_JAVA_ARGS,
                "-jar " + jar.getAbsolutePath() + " --mode AUTO --launch-mode REFLECT");
        startActivity(intent);
        finish();
    }

    /** Copy every log we can find from the previous run into public Downloads so we can
     *  read them from outside the app: RuneLite's own launcher.log AND Pojav's latestlog.txt
     *  (which has the JVM stdout/stderr — where GPU/GLFW init success or failure shows up). */
    private void copyRuneLiteLogToDownloads() {
        File ext = getExternalFilesDir(null);
        if (ext == null) return;
        File extPub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (extPub == null) return;
        extPub.mkdirs();
        // Pair: source -> dest filename in Downloads
        String[][] pairs = {
                {".runelite/logs/launcher.log", "runelite-launcher.log"},
                {"latestlog.txt", "runelitedroid-jvm.log"},
        };
        for (String[] pair : pairs) {
            try {
                File src = new File(ext, pair[0]);
                if (!src.exists() || src.length() == 0) {
                    diag("log skip: " + pair[0] + " (missing or empty)");
                    continue;
                }
                File dst = new File(extPub, pair[1]);
                try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                diag("copied " + pair[0] + " -> Downloads/" + pair[1] + " (" + dst.length() + " bytes)");
            } catch (Throwable t) {
                diag("copy of " + pair[0] + " failed: " + t);
            }
        }
    }

    private void diag(String msg) {
        Log.i(TAG, msg);
        try {
            File ext = getExternalFilesDir(null);
            if (ext != null) {
                ext.mkdirs();
                File log = new File(ext, DIAG_FILENAME);
                try (FileWriter w = new FileWriter(log, true)) {
                    String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                    w.write(ts + " " + msg + "\n");
                }
            }
            File extPub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (extPub != null) {
                extPub.mkdirs();
                File logPub = new File(extPub, DIAG_FILENAME);
                try (FileWriter w = new FileWriter(logPub, true)) {
                    String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                    w.write(ts + " " + msg + "\n");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "diag write failed", t);
        }
    }
}
