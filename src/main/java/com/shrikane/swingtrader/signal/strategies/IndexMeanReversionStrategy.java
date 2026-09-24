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
 * IMR-v1 — index mean-reversion (forward-campaign.md §4A): the Connors RSI-2
 * template, unmodified, on the whole market via NIFTYBEES.
 *
 * Entry:  close > SMA(200) AND RSI(2) < 10 → buy next open. One position.
 * Exit:   RSI(2) > 65 at close, or 10 trading days held (counted on the
 *         data's own bars), or close below the disaster stop
 *         entry − 2.5 × ATR(14). All exits fill at the next open.
 *
 * FROZEN: these parameters are pre-registered. A change is a new family
 * (IMR-v2) with its own pre-registration entry — never an edit here.
 */
public final class IndexMeanReversionStrategy implements Strategy {

    static final String SYMBOL = EtfUniverse.NIFTYBEES;
    private static final int TREND_SMA = 200;
    private static final int RSI_PERIOD = 2;
    private static final double RSI_ENTRY_BELOW = 10;
    private static final double RSI_EXIT_ABOVE = 65;
    private static final int MAX_HOLD_DAYS = 10;
    private static final int ATR_PERIOD = 14;
    private static final double STOP_ATR_MULTIPLE = 2.5;

    @Override
    public String name() {
        return "IMR-v1";
    }

    @Override
    public List<Signal> evaluate(MarketSnapshot snapshot, Portfolio portfolio) {
        List<Signal> signals = new ArrayList<>();

        for (Position position : portfolio.openPositions()) {
            List<Candle> candles = snapshot.candles(position.symbol());
            if (candles.isEmpty()) continue;
            double close = candles.get(candles.size() - 1).close();
            double rsi = Indicators.rsi(candles, RSI_PERIOD);
            String reason = null;
            if (DisasterStop.breached(candles, position, STOP_ATR_MULTIPLE, ATR_PERIOD)) {
                reason = "IMR disaster stop";
            } else if (rsi > RSI_EXIT_ABOVE) {
                reason = String.format(Locale.ROOT, "IMR RSI(2) %.1f > %.0f", rsi, RSI_EXIT_ABOVE);
            } else if (Indicators.barsSince(candles, position.entryDate()) >= MAX_HOLD_DAYS) {
                reason = "IMR " + MAX_HOLD_DAYS + "-day timeout";
            }
            if (reason != null) {
                signals.add(new Signal(position.symbol(), Signal.Action.EXIT, close, 0, 0, reason));
            }
        }

        if (!portfolio.openPositions().isEmpty()) return signals; // one position max

        List<Candle> candles = snapshot.candles(SYMBOL);
        if (candles.size() < TREND_SMA + 1) return signals;
        Candle today = candles.get(candles.size() - 1);
        if (!today.date().equals(snapshot.asOf())) return signals; // no bar today: don't act on stale data

        double sma = Indicators.sma(candles, TREND_SMA);
        double rsi = Indicators.rsi(candles, RSI_PERIOD);
        double atr = Indicators.atr(candles, ATR_PERIOD);
        if (Double.isNaN(sma) || Double.isNaN(rsi) || Double.isNaN(atr) || atr <= 0) return signals;

        if (today.close() > sma && rsi < RSI_ENTRY_BELOW) {
            signals.add(new Signal(SYMBOL, Signal.Action.ENTER, today.close(),
                    today.close() - STOP_ATR_MULTIPLE * atr, RSI_ENTRY_BELOW - rsi,
                    String.format(Locale.ROOT, "IMR RSI(2) %.1f < %.0f above SMA200",
                            rsi, RSI_ENTRY_BELOW)));
        }
        return signals;
    }
}
