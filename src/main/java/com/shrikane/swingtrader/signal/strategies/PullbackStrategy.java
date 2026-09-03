package com.shrikane.swingtrader.signal.strategies;

import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.MarketSnapshot;
import com.shrikane.swingtrader.risk.Portfolio;
import com.shrikane.swingtrader.signal.Indicators;
import com.shrikane.swingtrader.signal.Signal;
import com.shrikane.swingtrader.signal.Strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Strategy A (blueprint §5): buy sharp short-term dips in long-term uptrends.
 *
 * Entry:  close above 200-day SMA AND RSI(2) < 10.
 * Rank:   6-month momentum (higher preferred).
 * Exit:   close > yesterday's high, or RSI(2) > 70, or 7 trading days in trade.
 * Stop:   1.5 * ATR(14) below entry (checked at close, exit next open).
 *
 * Parameters are constructor arguments so the sweep command can grid them
 * for the sensitivity analysis in blueprint §6.5; the no-arg constructor is
 * the v1 default configuration.
 */
public class PullbackStrategy implements Strategy {

    private final int trendSmaPeriod;
    private final int rsiPeriod;
    private final double rsiEntryBelow;
    private final double rsiExitAbove;
    private final int maxHoldDays;
    private final double atrStopMultiple;
    private final int atrPeriod;
    private final int momentumLookback;
    private final String name;

    /** The v1 defaults (blueprint §5, Strategy A). */
    public PullbackStrategy() {
        this(10.0, 7, 1.5);
        // name stays "pullback-v1" via the check below
    }

    /** Sweepable knobs; everything else held at v1 values. */
    public PullbackStrategy(double rsiEntryBelow, int maxHoldDays, double atrStopMultiple) {
        this.trendSmaPeriod = 200;
        this.rsiPeriod = 2;
        this.rsiEntryBelow = rsiEntryBelow;
        this.rsiExitAbove = 70.0;
        this.maxHoldDays = maxHoldDays;
        this.atrStopMultiple = atrStopMultiple;
        this.atrPeriod = 14;
        this.momentumLookback = 126; // ~6 months
        boolean isDefault = rsiEntryBelow == 10.0 && maxHoldDays == 7 && atrStopMultiple == 1.5;
        this.name = isDefault ? "pullback-v1"
                : String.format(java.util.Locale.ROOT, "pullback(rsi<%.0f,hold%d,atr%.1f)",
                        rsiEntryBelow, maxHoldDays, atrStopMultiple);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Signal> evaluate(MarketSnapshot snapshot, Portfolio portfolio) {
        List<Signal> signals = new ArrayList<>();

        // --- Exits for open positions ---
        for (var position : portfolio.openPositions()) {
            List<Candle> candles = snapshot.candles(position.symbol());
            if (candles.size() < 2) continue;
            Candle today = candles.get(candles.size() - 1);
            Candle yesterday = candles.get(candles.size() - 2);
            double rsi = Indicators.rsi(candles, rsiPeriod);

            boolean exit = today.close() > yesterday.high()
                    || rsi > rsiExitAbove
                    || position.tradingDaysHeld(snapshot.asOf()) >= maxHoldDays
                    || today.close() <= position.stopPrice();

            if (exit) {
                signals.add(new Signal(position.symbol(), Signal.Action.EXIT,
                        today.close(), 0, 0, "pullback exit"));
            }
        }

        // --- Entries ---
        for (String symbol : snapshot.symbols()) {
            if (portfolio.holds(symbol)) continue;
            List<Candle> candles = snapshot.candles(symbol);
            if (candles.size() < trendSmaPeriod + 1) continue;

            Candle today = candles.get(candles.size() - 1);
            double sma200 = Indicators.sma(candles, trendSmaPeriod);
            double rsi = Indicators.rsi(candles, rsiPeriod);
            if (Double.isNaN(sma200) || Double.isNaN(rsi)) continue;

            if (today.close() > sma200 && rsi < rsiEntryBelow) {
                double atr = Indicators.atr(candles, atrPeriod);
                if (Double.isNaN(atr) || atr <= 0) continue;
                double stop = today.close() - atrStopMultiple * atr;
                double momentum = momentum(candles);
                signals.add(new Signal(symbol, Signal.Action.ENTER,
                        today.close(), stop, momentum,
                        String.format("RSI2=%.1f above SMA200", rsi)));
            }
        }
        return signals;
    }

    private double momentum(List<Candle> candles) {
        if (candles.size() < momentumLookback + 1) return 0;
        double then = candles.get(candles.size() - 1 - momentumLookback).close();
        double now = candles.get(candles.size() - 1).close();
        return (now - then) / then;
    }
}
