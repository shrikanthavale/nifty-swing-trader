package com.shrikane.swingtrader.signal.strategies;

import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.EtfUniverse;
import com.shrikane.swingtrader.data.MarketSnapshot;
import com.shrikane.swingtrader.risk.Portfolio;
import com.shrikane.swingtrader.risk.Position;
import com.shrikane.swingtrader.signal.Indicators;
import com.shrikane.swingtrader.signal.Signal;
import com.shrikane.swingtrader.signal.Strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VRS-v1 — volatility regime switch, "in when calm, out when stormy"
 * (forward-campaign.md §4C), on NIFTYBEES.
 *
 * Regime: 20-day realized volatility (annualized stdev of daily log returns,
 *         ×√252) vs the median of that same series over the trailing 252
 *         days (today included). Invested iff vol20 < median252 AND
 *         close > SMA(200); otherwise cash. Needs 272 candles (20 + 252)
 *         before it says anything at all.
 * Orders: the day the regime flips, at close → next open. Being flat while
 *         the regime is "on" (first run, or after a stop) enters too — the
 *         rule is a state, "invested iff".
 * Stop:   close below entry − 2.5 × ATR(14) exits at the next open.
 *
 * FROZEN: a change is a new family (VRS-v2), never an edit here.
 */
public final class VolRegimeStrategy implements Strategy {

    static final String SYMBOL = EtfUniverse.NIFTYBEES;
    private static final int VOL_PERIOD = 20;
    private static final int MEDIAN_WINDOW = 252;
    private static final int TREND_SMA = 200;
    private static final int ATR_PERIOD = 14;
    private static final double STOP_ATR_MULTIPLE = 2.5;

    /** Minimum history before the regime is defined. */
    static final int MIN_CANDLES = VOL_PERIOD + MEDIAN_WINDOW;

    @Override
    public String name() {
        return "VRS-v1";
    }

    @Override
    public List<Signal> evaluate(MarketSnapshot snapshot, Portfolio portfolio) {
        List<Signal> signals = new ArrayList<>();
        List<Candle> candles = snapshot.candles(SYMBOL);
        Regime regime = regime(candles);

        for (Position position : portfolio.openPositions()) {
            List<Candle> held = snapshot.candles(position.symbol());
            if (held.isEmpty()) continue;
            double close = held.get(held.size() - 1).close();
            String reason = null;
            if (DisasterStop.breached(held, position, STOP_ATR_MULTIPLE, ATR_PERIOD)) {
                reason = "VRS disaster stop";
            } else if (regime != null && !regime.on()) {
                reason = "VRS regime off: " + regime;
            }
            if (reason != null) {
                signals.add(new Signal(position.symbol(), Signal.Action.EXIT, close, 0, 0, reason));
            }
        }

        if (!portfolio.openPositions().isEmpty() || regime == null || !regime.on()) return signals;
        Candle today = candles.get(candles.size() - 1);
        if (!today.date().equals(snapshot.asOf())) return signals; // no bar today: don't act on stale data
        double atr = Indicators.atr(candles, ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return signals;
        signals.add(new Signal(SYMBOL, Signal.Action.ENTER, today.close(),
                today.close() - STOP_ATR_MULTIPLE * atr, 1, "VRS regime on: " + regime));
        return signals;
    }

    /** Today's regime inputs; {@link #on()} is the invested-iff rule. */
    record Regime(double vol20, double median252, double close, double sma200) {
        boolean on() {
            return vol20 < median252 && close > sma200;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "vol20 %.1f%% vs median %.1f%%, close %s SMA200",
                    vol20 * 100, median252 * 100, close > sma200 ? ">" : "<=");
        }
    }

    /** Null while history is insufficient (fewer than {@link #MIN_CANDLES} bars). */
    static Regime regime(List<Candle> candles) {
        if (candles.size() < MIN_CANDLES) return null;
        double[] vols = Indicators.realizedVolSeries(candles, VOL_PERIOD, MEDIAN_WINDOW);
        double sma = Indicators.sma(candles, TREND_SMA);
        if (vols.length == 0 || Double.isNaN(sma)) return null;
        return new Regime(vols[vols.length - 1], Indicators.median(vols),
                candles.get(candles.size() - 1).close(), sma);
    }
}
