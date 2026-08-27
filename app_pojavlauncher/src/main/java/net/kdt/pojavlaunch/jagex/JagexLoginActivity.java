package net.kdt.pojavlaunch.jagex;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
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
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Jagex account login, without the desktop Jagex Launcher.
 *
 * The flow is the launcher's own, split across three places for one reason:
 * account.jagex.com is behind Cloudflare, and POST /api/auth/login/jagex is
 * refused with a flat 403 from a firewall rule whenever the request comes from
 * a WebView. That is decided on the TLS handshake, so no user agent or client
 * hint fixes it. The same login succeeds in the device's real browser.
 *
 *   1. The password step runs in the real browser. Its redirect ends on a
 *      jagex: URL, which is a custom scheme, so Android hands it back to this
 *      activity through the intent filter.
 *   2. The code is exchanged for tokens over plain HTTP from the app.
 *      /oauth2/token is not behind the bot rules.
 *   3. The consent step runs in the WebView here. It carries the identity in
 *      id_token_hint rather than a login cookie, so it does not matter that the
 *      browser and the WebView have separate cookie jars, and /oauth2/auth only
 *      presents the ordinary Cloudflare challenge, which the WebView passes.
 *
 * The consent id_token then buys a game session id, the session id lists the
 * characters, and the result is what the gamepack reads out of JX_SESSION_ID
 * and JX_CHARACTER_ID.
 */
public class JagexLoginActivity extends Activity {
    private static final String TAG = "JagexLogin";

    private static final String AUTH_URL     = "https://account.jagex.com/oauth2/auth";
    private static final String TOKEN_URL    = "https://account.jagex.com/oauth2/token";
    private static final String SESSIONS_URL = "https://auth.jagex.com/game-session/v1/sessions";
    private static final String ACCOUNTS_URL = "https://auth.jagex.com/game-session/v1/accounts";

    private static final String LAUNCHER_CLIENT_ID = "com_jagex_auth_desktop_launcher";
    private static final String LAUNCHER_REDIRECT  = "https://secure.runescape.com/m=weblogin/launcher-redirect";
    private static final String LAUNCHER_SCOPE     = "openid offline gamesso.token.create user.profile.read";
    private static final String CONSENT_CLIENT_ID  = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2";
    private static final String CONSENT_REDIRECT   = "http://localhost";

    /** The browser step leaves this process in the background, and it can be
     *  killed while the user types their password, so the PKCE verifier has to
     *  outlive the activity. */
    private static final String PREFS = "jagex_login_state";
    private static final String KEY_VERIFIER = "code_verifier";
    private static final String KEY_STATE = "state";

    private WebView mWeb;
    private TextView mStatus;
    private String mConsentState;
    /** The consent redirect gets reported twice, by shouldOverrideUrlLoading and
     *  again by onPageStarted. */
    private boolean mBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        if (handleBrowserCallback(getIntent())) return;
        startBrowserLogin();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleBrowserCallback(intent);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        mStatus = new TextView(this);
        mStatus.setText(R.string.jagex_login_in_browser);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setPadding(48, 48, 48, 48);
        root.addView(mStatus, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mWeb = new WebView(this);
        root.addView(mWeb, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        WebSettings settings = mWeb.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(mWeb, true);
        }

        mWeb.setWebViewClient(new WebViewClient() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return intercept(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return intercept(request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (intercept(url)) view.stopLoading();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (!request.isForMainFrame()) return;
                Log.e(TAG, "load error " + error.getErrorCode() + " " + error.getDescription()
                    + " for " + redact(request.getUrl().toString()));
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            android.webkit.WebResourceResponse response) {
                Log.e(TAG, "HTTP " + response.getStatusCode()
                    + (request.isForMainFrame() ? " (main) " : " ")
                    + request.getMethod() + " " + redact(request.getUrl().toString()));
            }
        });
    }

    // --- step 1: the password, in the user's own browser --------------------

    private void startBrowserLogin() {
        String verifier = randomToken(48);
        String state = randomToken(12);
        prefs().edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply();

        String url = AUTH_URL
            + "?flow=launcher"
            + "&response_type=code"
            + "&client_id=" + LAUNCHER_CLIENT_ID
            + "&redirect_uri=" + enc(LAUNCHER_REDIRECT)
            + "&scope=" + enc(LAUNCHER_SCOPE)
            + "&state=" + enc(state)
            + "&code_challenge=" + enc(pkceChallenge(verifier))
            + "&code_challenge_method=S256"
            + "&prompt=login";

        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(browser);
        } catch (Exception e) {
            fail("No browser available for the Jagex login");
        }
    }

    /** Step 1's redirect ends at jagex:code=...,state=...,intent=... */
    private boolean handleBrowserCallback(Intent intent) {
        String data = intent == null ? null : intent.getDataString();
        if (data == null || !data.startsWith("jagex:")) return false;

        String params = data.substring("jagex:".length());
        final String code = param(params, "code");
        String state = param(params, "state");
        String expected = prefs().getString(KEY_STATE, "");
        final String verifier = prefs().getString(KEY_VERIFIER, "");

        if (code == null || expected.isEmpty() || !expected.equals(state)) {
            fail("Login callback did not match this session");
            return true;
        }
        prefs().edit().clear().apply();

        mStatus.setText(R.string.jagex_finishing);
        run(() -> {
            JSONObject tokens = new JSONObject(postForm(TOKEN_URL,
                "grant_type=authorization_code"
                    + "&client_id=" + LAUNCHER_CLIENT_ID
                    + "&code=" + enc(code)
                    + "&code_verifier=" + enc(verifier)
                    + "&redirect_uri=" + enc(LAUNCHER_REDIRECT)));
            String idToken = tokens.optString("id_token", "");
            if (idToken.isEmpty()) throw new IllegalStateException("no id_token in token response");

            // A migrated Jagex account needs the consent step. An un-migrated
            // RuneScape account logs in through RuneLite itself with no JX_ vars.
            String provider = jwtClaims(idToken).optString("login_provider", "jagex");
            if (!"jagex".equalsIgnoreCase(provider)) {
                runOnUiThread(() -> {
                    JagexAccount.saveLegacy(this);
                    done();
                });
                return;
            }
            // The launcher leg asks for the gamesso.token.create scope, so try
            // trading its id_token for a game session directly. If that is
            // refused we still have to go through the consent step, which needs
            // the login cookie and therefore the browser.
            try {
                JSONObject session = new JSONObject(postJson(SESSIONS_URL, null,
                    new JSONObject().put("idToken", idToken).toString()));
                final String sessionId = session.getString("sessionId");
                Log.i(TAG, "game session created straight from the launcher token");
                JSONArray accounts = new JSONArray(get(ACCOUNTS_URL, sessionId));
                if (accounts.length() == 0) throw new IllegalStateException("account has no characters");
                runOnUiThread(() -> chooseCharacter(sessionId, accounts));
                return;
            } catch (Exception e) {
                Log.w(TAG, "launcher token not accepted for a game session, needs consent: " + e);
            }
            runOnUiThread(() -> startConsent(idToken));
        });
        return true;
    }

    // --- step 2: consent, in the WebView, carried by id_token_hint -----------

    private void startConsent(String launcherIdToken) {
        mConsentState = randomToken(12);
        mStatus.setText(R.string.jagex_finishing);
        mWeb.loadUrl(AUTH_URL
            + "?id_token_hint=" + enc(launcherIdToken)
            + "&nonce=" + enc(randomToken(36))
            + "&prompt=consent"
            + "&response_type=" + enc("id_token code")
            + "&client_id=" + CONSENT_CLIENT_ID
            + "&redirect_uri=" + enc(CONSENT_REDIRECT)
            + "&scope=" + enc("openid offline")
            + "&state=" + enc(mConsentState));
    }

    private boolean intercept(String url) {
        if (url == null || mBusy) return false;
        Log.i(TAG, "navigating to " + redact(url));
        if (url.startsWith(CONSENT_REDIRECT)) {
            mBusy = true;
            onConsentRedirect(url);
            return true;
        }
        return false;
    }

    /** http://localhost/#id_token=...&code=...&state=... */
    private void onConsentRedirect(String url) {
        int hash = url.indexOf('#');
        String fragment = hash < 0 ? "" : url.substring(hash + 1);
        final String idToken = param(fragment, "id_token");
        String state = param(fragment, "state");
        if (idToken == null || mConsentState == null || !mConsentState.equals(state)) {
            fail("Consent callback did not match this session");
            return;
        }

        run(() -> {
            JSONObject session = new JSONObject(postJson(SESSIONS_URL, null,
                new JSONObject().put("idToken", idToken).toString()));
            final String sessionId = session.getString("sessionId");

            JSONArray accounts = new JSONArray(get(ACCOUNTS_URL, sessionId));
            if (accounts.length() == 0) throw new IllegalStateException("account has no characters");

            runOnUiThread(() -> chooseCharacter(sessionId, accounts));
        });
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
        done();
    }

    private void done() {
        setResult(RESULT_OK);
        finish();
    }

    private void fail(String message) {
        Log.e(TAG, message);
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Run a network step off the UI thread, surfacing any failure as a toast. */
    private interface Step { void run() throws Exception; }

    private void run(Step step) {
        new Thread(() -> {
            try {
                step.run();
            } catch (Exception e) {
                Log.e(TAG, "login step failed", e);
                fail("Jagex login failed: " + e);
            }
        }).start();
    }

    // --- plumbing ---------------------------------------------------------

    private static String postForm(String url, String body) throws Exception {
        return request(url, "POST", "application/x-www-form-urlencoded", null, body);
    }

    private static String postJson(String url, String bearer, String body) throws Exception {
        return request(url, "POST", "application/json", bearer, body);
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
            if (status >= 400) throw new IllegalStateException("HTTP " + status + " from " + url + ": " + text);
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

    /** Claims of a JWT. Not verified - the token came straight from Jagex over TLS
     *  and we only read a routing hint out of it. */
    private static JSONObject jwtClaims(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) throw new IllegalStateException("malformed id_token");
        return new JSONObject(new String(
            Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP), "UTF-8"));
    }

    /** Scheme, host and path only. The query and fragment of these URLs carry the
     *  authorization code and the id_token, and logcat is world-readable to any
     *  process holding READ_LOGS. */
    private static String redact(String url) {
        if (url == null) return "null";
        if (url.startsWith("jagex:")) return "jagex:<redacted>";
        int cut = url.length();
        for (int i = 0; i < cut; i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        String head = url.substring(0, cut);
        return cut == url.length() ? head : head + "?<redacted>";
    }

    /** Pull a value out of a "a=1,b=2" or "a=1&b=2" parameter blob. */
    private static String param(String blob, String key) {
        for (String pair : blob.split("[,&]")) {
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

    private static String pkceChallenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("UTF-8"));
            return Base64.encodeToString(hash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
