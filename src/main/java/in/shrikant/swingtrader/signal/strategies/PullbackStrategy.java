package in.shrikant.swingtrader.signal.strategies;

import in.shrikant.swingtrader.data.Candle;
import in.shrikant.swingtrader.data.MarketSnapshot;
import in.shrikant.swingtrader.risk.Portfolio;
import in.shrikant.swingtrader.signal.Indicators;
import in.shrikant.swingtrader.signal.Signal;
import in.shrikant.swingtrader.signal.Strategy;

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
 * Parameters are fields (not magic numbers) so the backtester can sweep them
 * for the sensitivity analysis in blueprint §6.5.
 */
public class PullbackStrategy implements Strategy {

    private final int trendSmaPeriod = 200;
    private final int rsiPeriod = 2;
    private final double rsiEntryBelow = 10.0;
    private final double rsiExitAbove = 70.0;
    private final int maxHoldDays = 7;
    private final double atrStopMultiple = 1.5;
    private final int atrPeriod = 14;
    private final int momentumLookback = 126; // ~6 months

    @Override
    public String name() {
        return "pullback-v1";
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
