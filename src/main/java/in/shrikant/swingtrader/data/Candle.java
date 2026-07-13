package in.shrikant.swingtrader.data;

import java.time.LocalDate;

/** One end-of-day OHLCV bar. Immutable. */
public record Candle(
        String symbol,
        LocalDate date,
        double open,
        double high,
        double low,
        double close,
        long volume
) {}
