package in.shrikant.swingtrader.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Properties;

/**
 * Persists the daily Kite Connect access token to a small properties file
 * (default: config/access_token.properties — matched by the gitignored
 * "access_token*" pattern; NEVER commit it).
 *
 * Kite invalidates access tokens every morning (~7:30 AM IST flush), so the
 * freshness rule is deliberately conservative: a stored token counts as fresh
 * only if it was obtained on the SAME calendar day (IST) for the SAME api_key.
 * A token from yesterday evening is stale this morning even if Kite hasn't
 * flushed it yet — re-authenticating is cheap, trading on a dead token is not
 * (blueprint §9 "token expiry mid-morning").
 */
public final class TokenStore {

    private final Path path;

    public TokenStore(Path path) {
        this.path = path;
    }

    /** What we persist alongside the token, so freshness is checkable later. */
    public record StoredToken(String accessToken, String publicToken,
                              LocalDate obtainedOn, String apiKey) {

        /** Pure freshness rule: same IST calendar day, same API key. */
        public boolean isFresh(LocalDate today, String forApiKey) {
            return obtainedOn.equals(today) && apiKey.equals(forApiKey);
        }
    }

    /** Loads whatever is stored, valid or not. Corrupt/missing file → empty. */
    public Optional<StoredToken> load() {
        if (!Files.isRegularFile(path)) return Optional.empty();
        Properties p = new Properties();
        try (var in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            return Optional.empty();
        }
        String token = p.getProperty("access_token");
        String date = p.getProperty("obtained_on");
        String apiKey = p.getProperty("api_key");
        if (token == null || token.isBlank() || date == null || apiKey == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredToken(
                    token, p.getProperty("public_token", ""),
                    LocalDate.parse(date), apiKey));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** The stored access token, but only if fresh for {@code today}/{@code apiKey}. */
    public Optional<String> freshAccessToken(LocalDate today, String apiKey) {
        return load().filter(t -> t.isFresh(today, apiKey))
                     .map(StoredToken::accessToken);
    }

    public void save(String accessToken, String publicToken,
                     LocalDate obtainedOn, String apiKey) throws IOException {
        Properties p = new Properties();
        p.setProperty("access_token", accessToken);
        p.setProperty("public_token", publicToken == null ? "" : publicToken);
        p.setProperty("obtained_on", obtainedOn.toString());
        p.setProperty("api_key", apiKey);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (var out = Files.newOutputStream(path)) {
            p.store(out, "Kite access token — expires daily. Gitignored; NEVER commit.");
        }
    }

    /** Deletes the stored token (e.g. after Kite rejects it). */
    public void clear() throws IOException {
        Files.deleteIfExists(path);
    }
}
