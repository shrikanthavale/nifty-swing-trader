package com.shrikane.swingtrader.auth;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import com.shrikane.swingtrader.config.AppConfig;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * The daily Kite Connect login flow (blueprint §2).
 *
 * Kite access tokens expire every morning, and fully unattended token
 * generation violates Zerodha's ToS — so this is deliberately a ~30-second
 * human-in-the-loop ritual:
 *
 *   1. If a token stored today already works, reuse it (verified via a
 *      profile call, so a mid-day rerun is a no-op).
 *   2. Otherwise print/open the Kite login URL; you log in on Zerodha's page.
 *   3. Zerodha redirects to http://127.0.0.1:{port}/callback — a one-shot
 *      local listener catches the request_token automatically.
 *      (Fallback: paste the redirect URL or token into the terminal.)
 *   4. Exchange request_token → access_token via the official Kite client
 *      (which computes the SHA-256 checksum) and persist it for the day.
 *
 * All times use Asia/Kolkata: token freshness follows the exchange's clock,
 * not the machine's.
 */
public final class KiteAuthenticator {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Duration LOGIN_WAIT = Duration.ofMinutes(5);

    private final AppConfig config;
    private final TokenStore tokenStore;

    public KiteAuthenticator(AppConfig config, TokenStore tokenStore) {
        this.config = config;
        this.tokenStore = tokenStore;
    }

    /**
     * Returns a KiteConnect client with a working access token, or throws.
     * Never trades on a token it hasn't verified today.
     */
    public KiteConnect authenticate() throws IOException {
        KiteConnect kite = new KiteConnect(config.kiteApiKey());
        LocalDate today = LocalDate.now(IST);

        Optional<String> cached = tokenStore.freshAccessToken(today, config.kiteApiKey());
        if (cached.isPresent()) {
            kite.setAccessToken(cached.get());
            if (tokenWorks(kite)) {
                System.out.println("Reusing today's access token (verified).");
                return kite;
            }
            System.out.println("Stored token was rejected by Kite — starting a fresh login.");
            tokenStore.clear();
        }

        String requestToken = obtainRequestToken();
        try {
            User user = kite.generateSession(requestToken, config.kiteApiSecret());
            kite.setAccessToken(user.accessToken);
            kite.setPublicToken(user.publicToken);
            kite.setUserId(user.userId);
            tokenStore.save(user.accessToken, user.publicToken, today, config.kiteApiKey());
            System.out.println("Authenticated with Kite as user " + user.userId
                    + "; token saved for today (" + today + ").");
            return kite;
        } catch (KiteException e) {
            throw new IOException("Kite token exchange failed: " + e.message
                    + " (code " + e.code + ")", e);
        }
    }

    /** A cheap authenticated call to prove the token is alive. */
    private boolean tokenWorks(KiteConnect kite) {
        try {
            kite.getProfile();
            return true;
        } catch (KiteException | IOException e) {
            return false;
        }
    }

    private String obtainRequestToken() throws IOException {
        String loginUrl = kite().getLoginURL();
        System.out.println("""

                ── Kite daily login ─────────────────────────────────────────
                Open this URL and log in to Zerodha:

                  %s
                """.formatted(loginUrl));
        tryOpenBrowser(loginUrl);

        try (RequestTokenListener listener = new RequestTokenListener(config.kiteRedirectPort())) {
            System.out.println("Waiting up to " + LOGIN_WAIT.toMinutes()
                    + " minutes for the redirect on 127.0.0.1:"
                    + config.kiteRedirectPort() + RequestTokenListener.CALLBACK_PATH + " ...");
            Optional<String> token = listener.await(LOGIN_WAIT);
            if (token.isPresent()) return token.get();
            System.out.println("No redirect received in time.");
        } catch (IOException e) {
            System.out.println("Could not start the local listener on port "
                    + config.kiteRedirectPort() + " (" + e.getMessage() + ").");
        }
        return promptManualToken();
    }

    private KiteConnect kite() {
        return new KiteConnect(config.kiteApiKey());
    }

    /**
     * Fallback: the user pastes either the raw request_token or the full
     * redirect URL from the browser's address bar (we extract the token).
     */
    private String promptManualToken() throws IOException {
        System.out.print("Paste the request_token (or the full redirect URL): ");
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null || line.isBlank()) {
            throw new IOException("No request token provided — aborting auth.");
        }
        line = line.trim();
        if (line.contains("request_token=")) {
            int q = line.indexOf('?');
            Optional<String> parsed = RequestTokenListener
                    .parseRequestToken(q >= 0 ? line.substring(q + 1) : line);
            return parsed.orElseThrow(() ->
                    new IOException("Could not extract request_token from the pasted URL."));
        }
        return line;
    }

    /** Best effort — on a headless box the printed URL is the path. */
    private void tryOpenBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            // fine — user opens the printed URL themselves
        }
    }
}
