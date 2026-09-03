package com.shrikane.swingtrader.signal;

import com.shrikane.swingtrader.data.Candle;

import java.util.List;

/**
 * Minimal indicator math over candle lists (ascending by date).
 * Deliberately hand-rolled and unit-tested rather than pulling a large TA
 * dependency; add indicators as strategies need them.
 */
public final class Indicators {

    private Indicators() {}

    /** Simple moving average of closes over the last {@code period} bars. NaN if insufficient data. */
    public static double sma(List<Candle> candles, int period) {
        if (candles.size() < period) return Double.NaN;
        return candles.subList(candles.size() - period, candles.size()).stream()
                .mapToDouble(Candle::close).average().orElse(Double.NaN);
    }

    /**
     * Wilder-smoothed RSI over closes. NaN if insufficient data.
     * Note RSI(2) — used by the pullback strategy — needs at least ~20 bars
     * of warmup for the smoothing to stabilise.
     */
    public static double rsi(List<Candle> candles, int period) {
        if (candles.size() < period + 1) return Double.NaN;
        double avgGain = 0, avgLoss = 0;
        // seed with the first `period` changes
        for (int i = 1; i <= period; i++) {
            double change = candles.get(i).close() - candles.get(i - 1).close();
            if (change > 0) avgGain += change; else avgLoss -= change;
        }
        avgGain /= period;
        avgLoss /= period;
        // Wilder smoothing over the remainder
        for (int i = period + 1; i < candles.size(); i++) {
            double change = candles.get(i).close() - candles.get(i - 1).close();
            avgGain = (avgGain * (period - 1) + Math.max(change, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-change, 0)) / period;
        }
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /** Average True Range (Wilder). NaN if insufficient data. */
    public static double atr(List<Candle> candles, int period) {
        if (candles.size() < period + 1) return Double.NaN;
        double atr = 0;
        for (int i = 1; i <= period; i++) {
            atr += trueRange(candles.get(i), candles.get(i - 1));
        }
        atr /= period;
        for (int i = period + 1; i < candles.size(); i++) {
            atr = (atr * (period - 1) + trueRange(candles.get(i), candles.get(i - 1))) / period;
        }
        return atr;
    }

    private static double trueRange(Candle today, Candle prev) {
        return Math.max(today.high() - today.low(),
                Math.max(Math.abs(today.high() - prev.close()),
                        Math.abs(today.low() - prev.close())));
    }

    /** Highest close over the last {@code period} bars. NaN if insufficient data. */
    public static double highestClose(List<Candle> candles, int period) {
        if (candles.size() < period) return Double.NaN;
        return candles.subList(candles.size() - period, candles.size()).stream()
                .mapToDouble(Candle::close).max().orElse(Double.NaN);
    }

    /** Highest close among bars dated on/after {@code from}. NaN if none. */
    public static double highestCloseSince(List<Candle> candles, java.time.LocalDate from) {
        return candles.stream()
                .filter(c -> !c.date().isBefore(from))
                .mapToDouble(Candle::close).max().orElse(Double.NaN);
    }

    /** Average volume over the last {@code period} bars. NaN if insufficient data. */
    public static double avgVolume(List<Candle> candles, int period) {
        if (candles.size() < period) return Double.NaN;
        return candles.subList(candles.size() - period, candles.size()).stream()
                .mapToDouble(Candle::volume).average().orElse(Double.NaN);
    }
}
