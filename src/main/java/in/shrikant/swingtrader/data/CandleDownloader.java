package in.shrikant.swingtrader.data;

/**
 * Pulls end-of-day OHLCV candles for the trading universe via the Kite Connect
 * historical data API and persists them to the local database.
 *
 * TODO (Phase 1):
 *  - Load instrument tokens from the Kite instruments dump (CSV) and cache the
 *    symbol -> instrument_token mapping in the DB.
 *  - For each symbol in the universe, fetch missing days since the last stored
 *    candle (kiteConnect.getHistoricalData(...), interval "day").
 *  - Validate: the newest candle's date must equal the expected trading date;
 *    if not, raise an alert and mark the day's data as stale (see blueprint §9,
 *    "Stale data day").
 *  - Watch for corporate-action discontinuities (>20% overnight moves with no
 *    news) and log them for manual review.
 */
public class CandleDownloader {
    // Intentionally a skeleton — implemented in Phase 1.
}
