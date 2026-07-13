package in.shrikant.swingtrader.data;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * A source of historical EOD candles. The production implementation talks to
 * Kite ({@link KiteHistoricalSource}); tests use an in-memory fake, so the
 * downloader's incremental/stale/discontinuity logic is testable offline.
 */
public interface HistoricalSource {

    /**
     * Daily candles for one instrument over [from, to], both inclusive,
     * oldest first. Non-trading days simply don't appear.
     */
    List<Candle> fetchDaily(String symbol, long instrumentToken,
                            LocalDate from, LocalDate to) throws IOException;
}
