package net.kdt.pojavlaunch;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.OutputStream;

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
    // JRE 25 from FCL-Team. We previously shipped AngelAuraMC's JRE 17 (crash at
    // libjvm.so+0xa14ca0) and JRE 21 (crash at libjvm.so+0xac44cc) — same bug
    // class, different offsets, JNI handle-list grow corruption that fires under
    // AWT-driven JNI bursts. FCL-Team is a completely separate OpenJDK port for
    // Android; disassembly shows the bug is structurally not present at those
    // offsets in FCL's libjvm.so. Different build, hopefully different (or no)
    // bug here. RuneLite's class files are Java 11 bytecode so they run on any
    // JRE 11+.
    private static final String JRE_NAME = "Internal-25";
    /** Asset path the CI build drops the JRE bundle into. Must match the install
     *  path the InternalRuntime enum points at in NewJREUtil. */
    private static final String JRE_ASSET_DIR = "components/jre-25";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyLauncherPrefs();
        copyRuneLiteLogToDownloads();
        ensureJreAsync(() -> {
            File jar = new File(getFilesDir(), JAR_NAME);
            if (jar.exists() && jar.length() > 0 && isValidJar(jar) && jarHasAudioClassPath(jar)) {
                diag("cached jar OK, size=" + jar.length() + " path=" + jar.getAbsolutePath());
                launchJar(jar);
            } else {
                if (jar.exists()) {
                    diag("cached jar needs refresh (missing audio Class-Path), size=" + jar.length());
                    jar.delete();
                }
                downloadAndLaunch(jar);
            }
        });
    }

    /** Verify the cached jar's manifest already has our audio Class-Path entry.
     *  If not, we need to redownload + re-dedup so dedupeJar can patch it in. */
    private boolean jarHasAudioClassPath(File jar) {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            java.util.jar.Manifest mf = jf.getManifest();
            if (mf == null) return false;
            String cp = mf.getMainAttributes().getValue("Class-Path");
            return cp != null && cp.contains("rldroid-audio.jar");
        } catch (Throwable t) {
            return false;
        }
    }

    private void ensureJreAsync(Runnable onReady) {
        try {
            String installed = MultiRTUtils.readInternalRuntimeVersion(JRE_NAME);
            String packaged = Tools.read(getAssets().open(JRE_ASSET_DIR + "/version"));
            if (packaged.equals(installed)) {
                diag("JRE 21already installed v=" + installed);
                onReady.run();
                return;
            }
            diag("JRE 21needs install: packaged=" + packaged + " installed=" + installed);
        } catch (Throwable t) {
            diag("JRE 21version check failed: " + t);
        }

        final ProgressDialog dlg = new ProgressDialog(this);
        dlg.setMessage(getString(R.string.runelite_installing_jre));
        dlg.setCancelable(false);
        dlg.show();
        new Thread(() -> {
            String error = null;
            try {
                diag("JRE install thread: enter");
                AssetManager am = getAssets();
                diag("JRE install thread: got AssetManager");
                String packaged = Tools.read(am.open(JRE_ASSET_DIR + "/version"));
                diag("JRE install thread: read version=" + packaged);
                String arch = Architecture.archAsString(Tools.DEVICE_ARCHITECTURE);
                diag("JRE install thread: arch=" + arch
                        + " freeBytes=" + getFilesDir().getFreeSpace());
                diag("JRE install thread: opening universal.tar.xz");
                java.io.InputStream uni = am.open(JRE_ASSET_DIR + "/universal.tar.xz");
                diag("JRE install thread: opening bin-" + arch + ".tar.xz");
                java.io.InputStream bin = am.open(JRE_ASSET_DIR + "/bin-" + arch + ".tar.xz");
                diag("JRE install thread: calling installRuntimeNamedBinpack");
                MultiRTUtils.installRuntimeNamedBinpack(uni, bin, JRE_NAME, packaged);
                diag("JRE install thread: installRuntimeNamedBinpack done");
                MultiRTUtils.postPrepare(JRE_NAME);
                diag("JRE 21 unpacked OK, arch=" + arch + " v=" + packaged);
            } catch (Throwable t) {
                Log.e(TAG, "JRE 21 install failed", t);
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                error = t + "\n" + sw;
                diag("JRE 21 install FAILED: " + error);
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
        if (!JRE_NAME.equals(p.getString("defaultRuntime", ""))) {
            p.edit().putString("defaultRuntime", JRE_NAME).commit();
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
     *  Keeps the LAST occurrence of each entry name — matches OpenJDK's last-wins semantics.
     *  ALSO patches META-INF/MANIFEST.MF to add `Class-Path: rldroid-audio.jar` so the
     *  JVM loads our audio MixerProvider on the application classpath alongside the
     *  RuneLite launcher — bootclasspath/a SPI discovery has been unreliable on JDK 25. */
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
        // Patch MANIFEST.MF to add a Class-Path entry pointing at our audio jar
        // (which we copy next to the dst path below). Class-Path: in the manifest
        // gets honored by `-jar X` JVM mode, adding the listed jars to the
        // application classpath. ServiceLoader then finds our MixerProvider.
        byte[] mfBytes = entries.get("META-INF/MANIFEST.MF");
        if (mfBytes != null) {
            String mf = new String(mfBytes, "UTF-8");
            if (!mf.contains("Class-Path:")) {
                // Append before final blank line. Manifest entries must end with CRLF.
                String addition = "Class-Path: rldroid-audio.jar\r\n";
                mf = mf.replaceAll("(\r?\n)$", addition + "$1");
                entries.put("META-INF/MANIFEST.MF", mf.getBytes("UTF-8"));
                diag("dedupeJar: added Class-Path: rldroid-audio.jar to MANIFEST.MF");
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
        // Copy the audio jar to the same dir as the dedup'd RuneLite.jar so the
        // Class-Path: relative reference resolves.
        try {
            File audioSrc = new File(Tools.DIR_GAME_HOME, "caciocavallo17/rldroid-audio.jar");
            File audioDst = new File(dst.getParentFile(), "rldroid-audio.jar");
            if (audioSrc.exists()) {
                try (FileInputStream in = new FileInputStream(audioSrc);
                     FileOutputStream out = new FileOutputStream(audioDst)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                diag("copied rldroid-audio.jar -> " + audioDst.getAbsolutePath()
                        + " (" + audioDst.length() + " bytes)");
            } else {
                diag("rldroid-audio.jar not found at " + audioSrc.getAbsolutePath()
                        + " — Class-Path won't resolve, audio will be unavailable");
            }
        } catch (Throwable t) {
            diag("rldroid-audio.jar copy failed: " + t);
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
        // --mode OPENGL: Java2D OpenGL backend. Doesn't enable RuneLite's GPU plugin
        // (that's a separate runtime toggle in the in-game plugin panel) but it
        // accelerates Swing/Java2D text & primitive drawing where possible.
        // --launch-mode REFLECT: in-process client launch (Android sandbox blocks
        // fork/exec, so the default JvmLauncher/ForkLauncher paths fail).
        // --scale 2: enlarge the Swing UI 2x. Cacio's virtual screen is near-native
        // resolution (sharp 3D scene), but at that res RuneLite's sidebar buttons
        // come out tiny. --scale only scales Swing components, leaving the OSRS
        // game canvas at its actual render size.
        intent.putExtra(RuneLiteGameActivity.EXTRA_JAVA_ARGS,
                "-jar " + jar.getAbsolutePath()
                        + " --mode OPENGL --launch-mode REFLECT --scale 2");
        startActivity(intent);
        finish();
    }

    /** Copy every log we can find from the previous run into public Downloads so we can
     *  read them from outside the app: RuneLite's own launcher.log AND Pojav's latestlog.txt
     *  (which has the JVM stdout/stderr — where GPU/GLFW init success or failure shows up). */
    private void copyRuneLiteLogToDownloads() {
        File ext = getExternalFilesDir(null);
        if (ext == null) return;
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
                long bytes = writeToDownloads(pair[1], src, /*append=*/false);
                diag("copied " + pair[0] + " -> Downloads/" + pair[1] + " (" + bytes + " bytes)");
            } catch (Throwable t) {
                diag("copy of " + pair[0] + " failed: " + t);
            }
        }
        // Snapshot the in-app diag log into Downloads too — the per-line appender
        // below also writes there, but only on Android <10. On 10+ we can't open
        // an OutputStream in append mode on a MediaStore URI, so we snapshot the
        // app-private diag (which always works) to Downloads on each launch.
        try {
            File diagSrc = new File(ext, DIAG_FILENAME);
            if (diagSrc.exists() && diagSrc.length() > 0) {
                long n = writeToDownloads(DIAG_FILENAME, diagSrc, /*append=*/false);
                Log.i(TAG, "snapshotted diag to Downloads (" + n + " bytes)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "diag snapshot failed", t);
        }
    }

    /** Write `src`'s contents to /Downloads/`dstName`, replacing any existing file
     *  with that name. Uses MediaStore on Android 10+ (scoped storage), and
     *  falls back to direct FileWriter on older devices. Returns dest size. */
    private long writeToDownloads(String dstName, File src, boolean append) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            // If a file with this name already exists in our collection (we created it
            // on a prior launch), delete it so the next insert isn't given " (1)".
            try (Cursor c = resolver.query(collection,
                    new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + "=?",
                    new String[]{dstName}, null)) {
                while (c != null && c.moveToNext()) {
                    Uri u = Uri.withAppendedPath(collection, c.getString(0));
                    resolver.delete(u, null, null);
                }
            } catch (Throwable ignored) {}

            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, dstName);
            cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(collection, cv);
            if (uri == null) throw new java.io.IOException("MediaStore insert returned null for " + dstName);

            try (OutputStream out = resolver.openOutputStream(uri);
                 FileInputStream in = new FileInputStream(src)) {
                if (out == null) throw new java.io.IOException("openOutputStream null for " + dstName);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, cv, null, null);
            return src.length();
        }
        // Legacy path: direct write to public Downloads (works on pre-scoped storage)
        File extPub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (extPub == null) throw new java.io.IOException("public Downloads unavailable");
        extPub.mkdirs();
        File dst = new File(extPub, dstName);
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst, append)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return dst.length();
    }

    private void diag(String msg) {
        Log.i(TAG, msg);
        try {
            // Always-on app-private append. /storage/emulated/0/Android/data/<pkg>/files/
            File ext = getExternalFilesDir(null);
            if (ext != null) {
                ext.mkdirs();
                File log = new File(ext, DIAG_FILENAME);
                try (FileWriter w = new FileWriter(log, true)) {
                    String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                    w.write(ts + " " + msg + "\n");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "diag write failed", t);
        }
        // We deliberately don't write to /Downloads/ from here — on Android 10+
        // direct writes to public Downloads silently fail under scoped storage.
        // The full diag is snapshotted via MediaStore on each launch (see
        // copyRuneLiteLogToDownloads → writeToDownloads).
    }
}
