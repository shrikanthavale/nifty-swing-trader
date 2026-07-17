package in.shrikant.swingtrader.signal.strategies;

import in.shrikant.swingtrader.data.Candle;
import in.shrikant.swingtrader.data.MarketSnapshot;
import in.shrikant.swingtrader.risk.Portfolio;
import in.shrikant.swingtrader.signal.Indicators;
import in.shrikant.swingtrader.signal.Signal;
import in.shrikant.swingtrader.signal.Strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Strategy B (blueprint §5): momentum breakout with volume confirmation.
 *
 * Entry:  today's close is the highest close of the last {@code breakoutDays}
 *         bars AND volume > {@code volumeMultiple} × the previous 20-day
 *         average volume AND close above the 200-day SMA.
 * Rank:   volume surge (today's volume / 20-day average) — the confirmation
 *         strength; higher wins when slots are scarce.
 * Exit:   close below the trailing stop — {@code trailAtrMultiple} × ATR(14)
 *         under the highest close since entry — or {@code maxHoldDays}
 *         trading days elapsed. Checked at close, filled next open.
 *
 * Contrast with Strategy A by design: lower win rate, larger winners.
 * Parameters are constructor arguments for the sweep; no-arg = v1 defaults.
 */
public class BreakoutStrategy implements Strategy {

    private final int breakoutDays;
    private final double volumeMultiple;
    private final int volumeAvgPeriod = 20;
    private final int trendSmaPeriod = 200;
    private final double trailAtrMultiple;
    private final int atrPeriod = 14;
    private final int maxHoldDays;
    private final String name;

    /** The v1 defaults (blueprint §5, Strategy B). */
    public BreakoutStrategy() {
        this(50, 1.5, 2.5, 10);
    }

    /** Sweepable knobs; everything else held at v1 values. */
    public BreakoutStrategy(int breakoutDays, double volumeMultiple,
                            double trailAtrMultiple, int maxHoldDays) {
        this.breakoutDays = breakoutDays;
        this.volumeMultiple = volumeMultiple;
        this.trailAtrMultiple = trailAtrMultiple;
        this.maxHoldDays = maxHoldDays;
        boolean isDefault = breakoutDays == 50 && volumeMultiple == 1.5
                && trailAtrMultiple == 2.5 && maxHoldDays == 10;
        this.name = isDefault ? "breakout-v1"
                : String.format(Locale.ROOT, "breakout(%dd,vol%.2f,atr%.1f,hold%d)",
                        breakoutDays, volumeMultiple, trailAtrMultiple, maxHoldDays);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Signal> evaluate(MarketSnapshot snapshot, Portfolio portfolio) {
        List<Signal> signals = new ArrayList<>();

        // --- Exits: trailing ATR stop from highest close since entry, or timeout ---
        for (var position : portfolio.openPositions()) {
            List<Candle> candles = snapshot.candles(position.symbol());
            if (candles.isEmpty()) continue;
            Candle today = candles.get(candles.size() - 1);
            double atr = Indicators.atr(candles, atrPeriod);
            double highestSinceEntry =
                    Indicators.highestCloseSince(candles, position.entryDate());
            if (Double.isNaN(highestSinceEntry)) highestSinceEntry = position.entryPrice();
            double trailingStop = Double.isNaN(atr)
                    ? position.stopPrice()
                    : highestSinceEntry - trailAtrMultiple * atr;

            boolean exit = today.close() < trailingStop
                    || position.tradingDaysHeld(snapshot.asOf()) >= maxHoldDays;
            if (exit) {
                signals.add(new Signal(position.symbol(), Signal.Action.EXIT,
                        today.close(), 0, 0,
                        today.close() < trailingStop ? "breakout trail stop" : "breakout timeout"));
            }
        }

        // --- Entries: N-day closing high + volume confirmation + uptrend filter ---
        for (String symbol : snapshot.symbols()) {
            if (portfolio.holds(symbol)) continue;
            List<Candle> candles = snapshot.candles(symbol);
            if (candles.size() < trendSmaPeriod + 1) continue;

            Candle today = candles.get(candles.size() - 1);
            double highestClose = Indicators.highestClose(candles, breakoutDays);
            if (Double.isNaN(highestClose) || today.close() < highestClose) continue;

            // average volume over the 20 bars BEFORE today — today's surge
            // must not dilute its own benchmark
            List<Candle> beforeToday = candles.subList(0, candles.size() - 1);
            double avgVolume = Indicators.avgVolume(beforeToday, volumeAvgPeriod);
            if (Double.isNaN(avgVolume) || avgVolume <= 0) continue;
            double volumeRatio = today.volume() / avgVolume;
            if (volumeRatio <= volumeMultiple) continue;

            double sma200 = Indicators.sma(candles, trendSmaPeriod);
            if (Double.isNaN(sma200) || today.close() <= sma200) continue;

            double atr = Indicators.atr(candles, atrPeriod);
            if (Double.isNaN(atr) || atr <= 0) continue;
            double stop = today.close() - trailAtrMultiple * atr;

            signals.add(new Signal(symbol, Signal.Action.ENTER,
                    today.close(), stop, volumeRatio,
                    String.format(Locale.ROOT, "%d-day high, vol %.1fx above SMA200",
                            breakoutDays, volumeRatio)));
        }
        return signals;
    }
}
