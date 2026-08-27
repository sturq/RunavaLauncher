package net.kdt.pojavlaunch.jagex;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Properties;

/**
 * The five JX_* values the OSRS gamepack reads out of the environment
 * (System.getenv) to log into a Jagex account. On desktop the Jagex Launcher
 * sets them; here {@link JagexLoginActivity} obtains them and we push them into
 * the JVM environment before it starts.
 *
 * Backed by a file rather than SharedPreferences because the launcher and the
 * game run in different processes, and SharedPreferences caches per process.
 */
public final class JagexAccount {
    private static final String FILE_NAME = "jagex.properties";
    private static final String KEY_LEGACY = "LEGACY_ACCOUNT";

    /** JX_ACCESS_TOKEN / JX_REFRESH_TOKEN are deliberately left empty: the game
     *  session id alone is enough to log in, and it saves us owning a token
     *  refresh loop. */
    public static final String[] ENV_KEYS = {
        "JX_ACCESS_TOKEN", "JX_REFRESH_TOKEN",
        "JX_SESSION_ID", "JX_CHARACTER_ID", "JX_DISPLAY_NAME"
    };

    private JagexAccount() {}

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    private static Properties load(Context ctx) {
        Properties props = new Properties();
        File f = file(ctx);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                props.load(in);
            } catch (Exception ignored) {
                // Unreadable credentials are the same as none: log in again.
            }
        }
        return props;
    }

    private static void store(Context ctx, Properties props) {
        try (FileOutputStream out = new FileOutputStream(file(ctx))) {
            props.store(out, "Do not share this file with anyone");
        } catch (Exception ignored) {
        }
    }

    /** True once we either have a Jagex session or know the account is a legacy one. */
    public static boolean isConfigured(Context ctx) {
        Properties props = load(ctx);
        return "true".equals(props.getProperty(KEY_LEGACY))
            || !props.getProperty("JX_SESSION_ID", "").isEmpty();
    }

    public static String displayName(Context ctx) {
        return load(ctx).getProperty("JX_DISPLAY_NAME", "");
    }

    public static void saveJagex(Context ctx, String sessionId, String characterId, String displayName) {
        Properties props = new Properties();
        props.setProperty("JX_SESSION_ID", sessionId);
        props.setProperty("JX_CHARACTER_ID", characterId);
        props.setProperty("JX_DISPLAY_NAME", displayName);
        props.setProperty("JX_ACCESS_TOKEN", "");
        props.setProperty("JX_REFRESH_TOKEN", "");
        store(ctx, props);
    }

    /** Old-style RuneScape account: RuneLite logs in with username/password, no JX_* vars. */
    public static void saveLegacy(Context ctx) {
        Properties props = new Properties();
        props.setProperty(KEY_LEGACY, "true");
        store(ctx, props);
    }

    public static void clear(Context ctx) {
        file(ctx).delete();
    }

    /** Copy the stored credentials into the environment the JVM is about to inherit. */
    public static void putEnv(Context ctx, Map<String, String> envMap) {
        Properties props = load(ctx);
        if (props.getProperty("JX_SESSION_ID", "").isEmpty()) return;
        for (String key : ENV_KEYS) envMap.put(key, props.getProperty(key, ""));
    }
}
