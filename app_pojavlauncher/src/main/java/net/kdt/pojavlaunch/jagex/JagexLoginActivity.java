package net.kdt.pojavlaunch.jagex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import net.kdt.pojavlaunch.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;

/**
 * Jagex account login, without the desktop Jagex Launcher.
 *
 * The login runs in the device's own browser, never in a WebView. Cloudflare
 * refuses POST /api/auth/login/jagex with a flat 403 whenever the request comes
 * from a WebView, and that is decided on the TLS handshake before a header is
 * read, so no user agent or client hint can change it. The same login succeeds
 * in a real browser.
 *
 * One OAuth leg is enough, the same one rsprox uses: the game client id with
 * response_type=id_token+code and prompt=login. The launcher leg and its
 * consent follow-up are not needed, and neither is a login cookie.
 *
 * The redirect lands on http://localhost with the id_token in the fragment.
 * Desktop launchers bind port 80 and read it with a local server; an Android
 * app cannot bind a privileged port, and a browser never hands an http:// URL
 * to an app, so the address comes back through the clipboard instead. That is
 * picked up automatically when the user switches back.
 *
 * The id_token then buys a game session id, the session id lists the
 * characters, and the result is what the gamepack reads out of JX_SESSION_ID
 * and JX_CHARACTER_ID.
 */
public class JagexLoginActivity extends Activity {
    private static final String TAG = "JagexLogin";

    private static final String AUTH_URL     = "https://account.jagex.com/oauth2/auth";
    private static final String SESSIONS_URL = "https://auth.jagex.com/game-session/v1/sessions";
    private static final String ACCOUNTS_URL = "https://auth.jagex.com/game-session/v1/accounts";
    private static final String CLIENT_ID    = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2";

    private EditText mPaste;
    private TextView mStatus;
    private String mState;
    private boolean mDone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mState = randomToken(12);
        buildUi();
        openBrowser();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from the browser with the address copied is the normal
        // path, so take it without making the user press anything.
        String clip = readClipboard();
        if (clip != null && clip.contains("id_token=")) submit(clip);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        mStatus = new TextView(this);
        mStatus.setText(R.string.jagex_browser_instructions);
        root.addView(mStatus);

        mPaste = new EditText(this);
        mPaste.setHint(R.string.jagex_paste_hint);
        mPaste.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(mPaste);

        Button paste = new Button(this);
        paste.setText(R.string.jagex_paste_button);
        paste.setOnClickListener(v -> {
            String clip = readClipboard();
            if (clip == null) {
                Toast.makeText(this, R.string.jagex_clipboard_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            mPaste.setText(clip);
            submit(clip);
        });
        root.addView(paste);

        Button continueButton = new Button(this);
        continueButton.setText(R.string.jagex_continue);
        continueButton.setOnClickListener(v -> submit(mPaste.getText().toString()));
        root.addView(continueButton);

        Button reopen = new Button(this);
        reopen.setText(R.string.jagex_reopen_browser);
        reopen.setOnClickListener(v -> openBrowser());
        root.addView(reopen);

        Button legacy = new Button(this);
        legacy.setText(R.string.jagex_legacy_account);
        legacy.setOnClickListener(v -> {
            JagexAccount.saveLegacy(this);
            setResult(RESULT_OK);
            finish();
        });
        root.addView(legacy);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(root, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroller);
    }

    private void openBrowser() {
        String url = AUTH_URL
            + "?response_type=" + enc("id_token code")
            + "&client_id=" + CLIENT_ID
            + "&nonce=" + enc(randomToken(24))
            + "&state=" + enc(mState)
            + "&prompt=login"
            + "&scope=" + enc("openid offline");
        try {
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(browser);
        } catch (Exception e) {
            Toast.makeText(this, R.string.jagex_no_browser, Toast.LENGTH_LONG).show();
        }
    }

    private String readClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return null;
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        return text == null ? null : text.toString().trim();
    }

    /** Takes the whole localhost address the browser was left on. */
    private void submit(String pastedUrl) {
        if (mDone) return;
        int hash = pastedUrl.indexOf('#');
        String fragment = hash < 0 ? pastedUrl : pastedUrl.substring(hash + 1);
        final String idToken = param(fragment, "id_token");
        if (idToken == null) {
            Toast.makeText(this, R.string.jagex_paste_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        String state = param(fragment, "state");
        if (state != null && !mState.equals(state)) {
            Toast.makeText(this, R.string.jagex_paste_stale, Toast.LENGTH_LONG).show();
            return;
        }
        mDone = true;
        mStatus.setText(R.string.jagex_finishing);

        new Thread(() -> {
            try {
                JSONObject session = new JSONObject(postJson(SESSIONS_URL,
                    new JSONObject().put("idToken", idToken).toString()));
                final String sessionId = session.getString("sessionId");
                JSONArray accounts = new JSONArray(get(ACCOUNTS_URL, sessionId));
                if (accounts.length() == 0) throw new IllegalStateException("account has no characters");
                runOnUiThread(() -> chooseCharacter(sessionId, accounts));
            } catch (Exception e) {
                Log.e(TAG, "could not turn the id_token into a game session", e);
                mDone = false;
                runOnUiThread(() -> {
                    mStatus.setText(R.string.jagex_browser_instructions);
                    Toast.makeText(this, "Jagex login failed: " + e, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void chooseCharacter(String sessionId, JSONArray accounts) {
        if (accounts.length() == 1) {
            saveAndFinish(sessionId, accounts.optJSONObject(0));
            return;
        }
        final CharSequence[] names = new CharSequence[accounts.length()];
        for (int i = 0; i < accounts.length(); i++) {
            names[i] = accounts.optJSONObject(i).optString("displayName", "(no display name)");
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.jagex_pick_character)
            .setCancelable(false)
            .setItems(names, (d, which) -> saveAndFinish(sessionId, accounts.optJSONObject(which)))
            .show();
    }

    private void saveAndFinish(String sessionId, JSONObject account) {
        JagexAccount.saveJagex(this, sessionId,
            account.optString("accountId", ""),
            account.optString("displayName", ""));
        // The address still sitting in the clipboard is a working credential.
        clearClipboard();
        setResult(RESULT_OK);
        finish();
    }

    private void clearClipboard() {
        try {
            ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
        } catch (Exception ignored) {
        }
    }

    // --- plumbing ---------------------------------------------------------

    private static String postJson(String url, String body) throws Exception {
        return request(url, "POST", "application/json", null, body);
    }

    private static String get(String url, String bearer) throws Exception {
        return request(url, "GET", null, bearer, null);
    }

    private static String request(String url, String method, String contentType,
                                  String bearer, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/json");
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                conn.setDoOutput(true);
                byte[] out = body.getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(out.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (status >= 400) throw new IllegalStateException("HTTP " + status + " from " + url);
            return text;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toString("UTF-8");
    }

    /** Pull a value out of an "a=1&b=2" blob. */
    private static String param(String blob, String key) {
        for (String pair : blob.split("[&?]")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equals(key)) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        return Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
