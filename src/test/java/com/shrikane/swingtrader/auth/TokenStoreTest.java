package com.shrikane.swingtrader.auth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenStoreTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 13);
    private static final String API_KEY = "test_api_key";

    private TokenStore newStore() throws IOException {
        Path dir = Files.createTempDirectory("tokenstore-test");
        return new TokenStore(dir.resolve("access_token.properties"));
    }

    @Test
    void roundTripSameDayIsFresh() throws IOException {
        TokenStore store = newStore();
        store.save("tok123", "pub456", TODAY, API_KEY);

        Optional<String> fresh = store.freshAccessToken(TODAY, API_KEY);
        assertTrue(fresh.isPresent());
        assertEquals("tok123", fresh.get());

        TokenStore.StoredToken stored = store.load().orElseThrow();
        assertEquals("pub456", stored.publicToken());
        assertEquals(TODAY, stored.obtainedOn());
    }

    @Test
    void yesterdaysTokenIsStale() throws IOException {
        TokenStore store = newStore();
        store.save("tok123", "pub456", TODAY.minusDays(1), API_KEY);
        assertTrue(store.freshAccessToken(TODAY, API_KEY).isEmpty());
        // the raw record is still loadable — only freshness fails
        assertTrue(store.load().isPresent());
    }

    @Test
    void differentApiKeyIsNotFresh() throws IOException {
        TokenStore store = newStore();
        store.save("tok123", "pub456", TODAY, "other_key");
        assertTrue(store.freshAccessToken(TODAY, API_KEY).isEmpty());
    }

    @Test
    void missingFileIsEmpty() throws IOException {
        TokenStore store = newStore();
        assertTrue(store.load().isEmpty());
        assertTrue(store.freshAccessToken(TODAY, API_KEY).isEmpty());
    }

    @Test
    void corruptDateIsEmpty() throws IOException {
        Path dir = Files.createTempDirectory("tokenstore-test");
        Path file = dir.resolve("access_token.properties");
        Files.writeString(file,
                "access_token=tok\nobtained_on=not-a-date\napi_key=" + API_KEY + "\n");
        assertTrue(new TokenStore(file).load().isEmpty());
    }

    @Test
    void clearRemovesToken() throws IOException {
        TokenStore store = newStore();
        store.save("tok123", "", TODAY, API_KEY);
        store.clear();
        assertTrue(store.load().isEmpty());
    }

    @Test
    void freshnessRuleIsPure() {
        TokenStore.StoredToken token =
                new TokenStore.StoredToken("t", "p", TODAY, API_KEY);
        assertTrue(token.isFresh(TODAY, API_KEY));
        assertFalse(token.isFresh(TODAY.plusDays(1), API_KEY));
        assertFalse(token.isFresh(TODAY, "someone_else"));
    }
}
