package in.shrikant.swingtrader.auth;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One-shot local HTTP listener that catches Zerodha's post-login redirect and
 * extracts the request_token, so the morning ritual is: click login URL, log
 * in on Zerodha's page, done.
 *
 * Requires the Kite app's Redirect URL (developers.kite.trade console) to be
 * registered as exactly:  http://127.0.0.1:{port}/callback
 *
 * Binds to 127.0.0.1 only — never reachable from outside the machine. Uses
 * the JDK's built-in HttpServer; no extra dependency.
 */
public final class RequestTokenListener implements AutoCloseable {

    public static final String CALLBACK_PATH = "/callback";

    private final HttpServer server;
    private final CompletableFuture<String> token = new CompletableFuture<>();

    public RequestTokenListener(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext(CALLBACK_PATH, exchange -> {
            Optional<String> requestToken =
                    parseRequestToken(exchange.getRequestURI().getRawQuery());
            String body = requestToken.isPresent()
                    ? "<h2>Login captured.</h2><p>You can close this tab and return to the terminal.</p>"
                    : "<h2>No request_token in redirect.</h2><p>Check the Redirect URL registered in the Kite developer console.</p>";
            byte[] bytes = ("<!doctype html><html><body style=\"font-family:sans-serif\">"
                    + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(requestToken.isPresent() ? 200 : 400, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            requestToken.ifPresent(token::complete);
        });
        server.start();
    }

    /**
     * Pure function: extracts request_token from a redirect query string.
     * Zerodha redirects with ...?status=success&request_token=xxx ; a
     * status other than success (user cancelled, login failed) yields empty.
     */
    public static Optional<String> parseRequestToken(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return Optional.empty();
        Map<String, String> params = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                       URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        String status = params.get("status");
        if (status != null && !"success".equalsIgnoreCase(status)) return Optional.empty();
        String requestToken = params.get("request_token");
        return (requestToken == null || requestToken.isBlank())
                ? Optional.empty() : Optional.of(requestToken);
    }

    /** Blocks until the redirect arrives or the timeout elapses. */
    public Optional<String> await(Duration timeout) {
        try {
            return Optional.of(token.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException | ExecutionException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
