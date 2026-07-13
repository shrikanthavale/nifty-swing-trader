package in.shrikant.swingtrader.data;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * Fetches the current NIFTY 100 constituents list. Two sources:
 *
 *  1. {@link #download()} — NSE's official CSV. NSE fronts its archives with
 *     bot protection that changes moods, so we send browser-ish headers and
 *     treat any non-200 / non-CSV response as a hard failure (never silently
 *     trade on a wrong universe).
 *  2. {@link #fromFile(Path)} — a manually downloaded CSV, the dependable
 *     fallback: grab ind_nifty100list.csv from niftyindices.com in a browser
 *     and point the command at it.
 *
 * Membership changes are rare (scheduled index rebalances, ad-hoc events like
 * mergers/delistings) — NSE announces them in advance and applies them at
 * month-end/quarter-end, so refreshing around the turn of each month is
 * plenty for a personal system.
 */
public final class ConstituentsDownloader {

    public static final String NIFTY100_CSV_URL =
            "https://archives.nseindia.com/content/indices/ind_nifty100list.csv";

    private final HttpClient client;

    public ConstituentsDownloader() {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** Downloads and parses the current NIFTY 100 symbol set from NSE. */
    public Set<String> download() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(NIFTY100_CSV_URL))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "text/csv,*/*")
                .GET()
                .build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("NSE constituents download failed: HTTP "
                    + response.statusCode() + " from " + NIFTY100_CSV_URL
                    + " — download ind_nifty100list.csv manually and use `universe <path>`.");
        }
        try {
            return ConstituentsCsv.parseSymbols(response.body());
        } catch (IllegalArgumentException e) {
            throw new IOException("NSE response was not a constituents CSV ("
                    + e.getMessage() + ") — likely bot protection; download the file "
                    + "manually and use `universe <path>`.", e);
        }
    }

    /** Parses a locally saved constituents CSV. */
    public static Set<String> fromFile(Path csvPath) throws IOException {
        return ConstituentsCsv.parseSymbols(Files.readString(csvPath, StandardCharsets.UTF_8));
    }
}
