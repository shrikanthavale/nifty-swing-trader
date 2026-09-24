package com.shrikane.swingtrader.signal;

import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.MarketSnapshot;

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

    /**
     * Market breadth: the fraction of snapshot symbols whose latest close is
     * above their own {@code period}-day SMA — a fever thermometer for the
     * whole market (near 1 in broad uptrends, near 0 in broad sell-offs).
     * Symbols with insufficient history are excluded from the denominator;
     * NaN if none qualify. Used by the v2 regime filter.
     */
    public static double breadthAboveSma(MarketSnapshot snapshot, int period) {
        int eligible = 0, above = 0;
        for (String symbol : snapshot.symbols()) {
            List<Candle> candles = snapshot.candles(symbol);
            if (candles.size() < period) continue;
            double sma = sma(candles, period);
            if (Double.isNaN(sma)) continue;
            eligible++;
            if (candles.get(candles.size() - 1).close() > sma) above++;
        }
        return eligible == 0 ? Double.NaN : (double) above / eligible;
    }

    /** Trading days in a year — the annualization factor for daily volatility. */
    public static final int TRADING_DAYS_PER_YEAR = 252;

    /**
     * Realized volatility: sample standard deviation of the last {@code period}
     * daily log returns, annualized by ×√252. 0.15 means "15% a year" — how
     * violently the price has been moving lately, regardless of direction.
     * NaN if fewer than {@code period + 1} candles.
     */
    public static double realizedVol(List<Candle> candles, int period) {
        double[] series = realizedVolSeries(candles, period, 1);
        return series.length == 0 ? Double.NaN : series[0];
    }

    /**
     * The last {@code count} values of the rolling realized-volatility series
     * (oldest first; the final value is today's). Empty if the history is too
     * short, i.e. fewer than {@code period + count} candles.
     */
    public static double[] realizedVolSeries(List<Candle> candles, int period, int count) {
        int n = candles.size();
        if (period < 2 || count < 1 || n < period + count) return new double[0];
        double[] logReturns = new double[n];            // logReturns[i] = ln(c[i]/c[i-1])
        for (int i = 1; i < n; i++) {
            logReturns[i] = Math.log(candles.get(i).close() / candles.get(i - 1).close());
        }
        double annualize = Math.sqrt(TRADING_DAYS_PER_YEAR);
        double[] out = new double[count];
        for (int k = 0; k < count; k++) {
            int end = n - count + k;                     // bar the value is "as of"
            double mean = 0;
            for (int i = end - period + 1; i <= end; i++) mean += logReturns[i];
            mean /= period;
            double sumSq = 0;
            for (int i = end - period + 1; i <= end; i++) {
                double d = logReturns[i] - mean;
                sumSq += d * d;
            }
            out[k] = Math.sqrt(sumSq / (period - 1)) * annualize;
        }
        return out;
    }

    /** Median of the values (mean of the middle two for an even count). NaN if empty. */
    public static double median(double[] values) {
        if (values.length == 0) return Double.NaN;
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }

    /**
     * Total return over the last {@code period} bars: close today divided by
     * the close {@code period} bars ago, minus 1. NaN if fewer than
     * {@code period + 1} candles.
     */
    public static double totalReturn(List<Candle> candles, int period) {
        int n = candles.size();
        if (n < period + 1) return Double.NaN;
        return candles.get(n - 1).close() / candles.get(n - 1 - period).close() - 1.0;
    }

    /**
     * Bars dated strictly after {@code date} — "trading days held" counted on
     * the data's own calendar (exact, unlike Position's weekday approximation).
     */
    public static int barsSince(List<Candle> candles, java.time.LocalDate date) {
        int count = 0;
        for (int i = candles.size() - 1; i >= 0 && candles.get(i).date().isAfter(date); i--) {
            count++;
        }
        return count;
    }
}
