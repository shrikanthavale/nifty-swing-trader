package com.shrikane.swingtrader.auth;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestTokenListenerTest {

    // --- parseRequestToken: pure function, exhaustive cases ---

    @Test
    void parsesTokenFromTypicalKiteRedirect() {
        Optional<String> token = RequestTokenListener.parseRequestToken(
                "action=login&type=login&status=success&request_token=abc123XYZ");
        assertEquals(Optional.of("abc123XYZ"), token);
    }

    @Test
    void emptyWhenStatusIsNotSuccess() {
        assertTrue(RequestTokenListener.parseRequestToken(
                "status=error&request_token=abc123").isEmpty());
    }

    @Test
    void parsesWhenStatusAbsent() {
        assertEquals(Optional.of("abc"),
                RequestTokenListener.parseRequestToken("request_token=abc"));
    }

    @Test
    void emptyOnMissingTokenNullOrBlankQuery() {
        assertTrue(RequestTokenListener.parseRequestToken("status=success&action=login").isEmpty());
        assertTrue(RequestTokenListener.parseRequestToken(null).isEmpty());
        assertTrue(RequestTokenListener.parseRequestToken("").isEmpty());
        assertTrue(RequestTokenListener.parseRequestToken("request_token=").isEmpty());
    }

    @Test
    void decodesUrlEncodedValues() {
        assertEquals(Optional.of("a b+c"),
                RequestTokenListener.parseRequestToken("request_token=a%20b%2Bc&status=success"));
    }

    @Test
    void ignoresMalformedPairs() {
        assertEquals(Optional.of("abc"),
                RequestTokenListener.parseRequestToken("junk&=x&request_token=abc"));
    }

    // --- end-to-end: real redirect against the local listener ---

    @Test
    void capturesTokenFromLocalHttpRedirect() throws Exception {
        int port = 18473; // unusual port to avoid collisions
        try (RequestTokenListener listener = new RequestTokenListener(port)) {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + port + RequestTokenListener.CALLBACK_PATH
                            + "?action=login&status=success&request_token=e2eToken42"))
                    .GET().build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(Optional.of("e2eToken42"), listener.await(Duration.ofSeconds(5)));
        }
    }

    @Test
    void badRedirectGets400AndNoToken() throws Exception {
        int port = 18474;
        try (RequestTokenListener listener = new RequestTokenListener(port)) {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + port + RequestTokenListener.CALLBACK_PATH
                            + "?status=error")).GET().build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(400, response.statusCode());
            assertTrue(listener.await(Duration.ofMillis(200)).isEmpty());
        }
    }
}
