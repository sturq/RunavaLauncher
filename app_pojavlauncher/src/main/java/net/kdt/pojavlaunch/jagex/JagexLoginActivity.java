package net.kdt.pojavlaunch.jagex;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
 * The Jagex account login, in a WebView, without the desktop Jagex Launcher.
 *
 * Two OAuth legs against account.jagex.com, exactly what the official launcher
 * does:
 *   1. "launcher" client, auth code + PKCE, redirect lands on a jagex: URL that
 *      carries the code. Exchanged for an id_token.
 *   2. "consent" client, implicit id_token, redirect lands on http://localhost.
 *      Rides the session cookie leg 1 left in the WebView, so it is usually
 *      invisible to the user.
 * The consent id_token buys a game session id, the session id lists the
 * characters on the account, and session id + character id are what the
 * gamepack wants in JX_SESSION_ID / JX_CHARACTER_ID.
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

    private WebView mWeb;
    private String mCodeVerifier;
    private String mLauncherState;
    private String mConsentState;
    /** Both redirects can be reported twice (shouldOverrideUrlLoading + onPageStarted). */
    private boolean mBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCodeVerifier = randomToken(48);
        mLauncherState = randomToken(12);

        mWeb = new WebView(this);
        setContentView(mWeb);

        WebSettings settings = mWeb.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(mWeb, true);
        }
        // We only get here when there are no credentials, so start from a clean
        // slate - otherwise a stale cookie silently logs back into the account
        // the user just logged out of.
        CookieManager.getInstance().removeAllCookies(ignored -> startLogin());
    }

    private void startLogin() {
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
        });

        mWeb.loadUrl(AUTH_URL
            + "?response_type=code"
            + "&client_id=" + LAUNCHER_CLIENT_ID
            + "&redirect_uri=" + enc(LAUNCHER_REDIRECT)
            + "&scope=" + enc(LAUNCHER_SCOPE)
            + "&state=" + enc(mLauncherState)
            + "&code_challenge=" + enc(pkceChallenge(mCodeVerifier))
            + "&code_challenge_method=S256");
    }

    private boolean intercept(String url) {
        if (url == null || mBusy) return false;
        if (url.startsWith("jagex:")) {
            mBusy = true;
            onLauncherRedirect(url.substring("jagex:".length()));
            return true;
        }
        if (url.startsWith(CONSENT_REDIRECT)) {
            mBusy = true;
            onConsentRedirect(url);
            return true;
        }
        return false;
    }

    /** Leg 1 callback: jagex:code=...,state=...,intent=... */
    private void onLauncherRedirect(String params) {
        final String code = param(params, "code");
        String state = param(params, "state");
        if (code == null || !mLauncherState.equals(state)) {
            fail("Login callback did not match this session");
            return;
        }

        run(() -> {
            JSONObject tokens = new JSONObject(postForm(TOKEN_URL,
                "grant_type=authorization_code"
                    + "&client_id=" + LAUNCHER_CLIENT_ID
                    + "&code=" + enc(code)
                    + "&code_verifier=" + enc(mCodeVerifier)
                    + "&redirect_uri=" + enc(LAUNCHER_REDIRECT)));
            String idToken = tokens.optString("id_token", "");
            if (idToken.isEmpty()) throw new IllegalStateException("no id_token in token response");

            // A migrated Jagex account needs the second leg. An un-migrated
            // RuneScape account logs in through RuneLite itself with no JX_ vars.
            String provider = jwtClaims(idToken).optString("login_provider", "jagex");
            if (!"jagex".equalsIgnoreCase(provider)) {
                runOnUiThread(() -> {
                    JagexAccount.saveLegacy(this);
                    done();
                });
                return;
            }

            mConsentState = randomToken(12);
            final String consentUrl = AUTH_URL
                + "?response_type=" + enc("id_token code")
                + "&client_id=" + CONSENT_CLIENT_ID
                + "&redirect_uri=" + enc(CONSENT_REDIRECT)
                + "&scope=" + enc("openid offline")
                + "&state=" + enc(mConsentState)
                + "&nonce=" + enc(randomToken(36));
            runOnUiThread(() -> {
                mBusy = false;
                mWeb.loadUrl(consentUrl);
            });
        });
    }

    /** Leg 2 callback: http://localhost/#id_token=...&code=...&state=... */
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
