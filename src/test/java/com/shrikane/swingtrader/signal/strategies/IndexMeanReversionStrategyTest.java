package com.shrikane.swingtrader.signal.strategies;

import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.MarketSnapshot;
import com.shrikane.swingtrader.risk.Portfolio;
import com.shrikane.swingtrader.risk.Position;
import com.shrikane.swingtrader.signal.Signal;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexMeanReversionStrategyTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final String SYM = "NIFTYBEES";

    private static Candle bar(int i, double close) {
        return new Candle(SYM, START.plusDays(i), close, close + 0.5, close - 0.5, close, 1000);
    }

    /** {@code bars} rising closes (+0.1/bar, ATR ≈ 1); optionally the last one dips by {@code dip}. */
    private static List<Candle> risingThenDip(int bars, double dip) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            double close = 100 + i * 0.1 - (i == bars - 1 ? dip : 0);
            candles.add(bar(i, close));
        }
        return candles;
    }

    private static MarketSnapshot snapshot(List<Candle> candles) {
        return MarketSnapshot.of(Map.of(SYM, candles), candles.get(candles.size() - 1).date());
    }

    private static Portfolio holding(List<Candle> candles, int entryIndex, double entryPrice) {
        Portfolio p = new Portfolio(16_000);
        p.applyBuy(new Position(SYM, 50, entryPrice, candles.get(entryIndex).date(), entryPrice - 3), 0);
        return p;
    }

    @Test
    void entersOnRsi2DipAboveSma200() {
        // one 1.0 drop after a steady climb: RSI(2) ≈ 9.1 < 10, close far above SMA200
        List<Candle> candles = risingThenDip(250, 1.0);
        List<Signal> signals = new IndexMeanReversionStrategy()
                .evaluate(snapshot(candles), new Portfolio(16_000));
        assertEquals(1, signals.size());
        Signal s = signals.get(0);
        assertEquals(Signal.Action.ENTER, s.action());
        assertEquals(SYM, s.symbol());
        double close = candles.get(249).close();
        assertEquals(close, s.referencePrice(), 1e-9);
        assertTrue(s.stopPrice() < close - 2, "stop = close - 2.5 x ATR(≈1)");
    }

    @Test
    void noEntryWithoutADip() {
        assertTrue(new IndexMeanReversionStrategy()
                .evaluate(snapshot(risingThenDip(250, 0)), new Portfolio(16_000)).isEmpty());
    }

    @Test
    void sma200FilterBlocksDipsInADowntrend() {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 250; i++) candles.add(bar(i, 200 - i * 0.2 - (i == 249 ? 1.0 : 0)));
        assertTrue(new IndexMeanReversionStrategy()
                .evaluate(snapshot(candles), new Portfolio(16_000)).isEmpty());
    }

    @Test
    void silentWithInsufficientHistory() {
        assertTrue(new IndexMeanReversionStrategy()
                .evaluate(snapshot(risingThenDip(200, 1.0)), new Portfolio(16_000)).isEmpty());
    }

    @Test
    void onePositionMaxNoEntryWhileHolding() {
        List<Candle> candles = risingThenDip(250, 1.0);
        Portfolio p = holding(candles, 247, candles.get(247).close());
        assertTrue(new IndexMeanReversionStrategy().evaluate(snapshot(candles), p).stream()
                .noneMatch(s -> s.action() == Signal.Action.ENTER));
    }

    @Test
    void exitsWhenRsi2ClimbsAbove65() {
        List<Candle> candles = risingThenDip(250, 0);          // steady climb → RSI(2) = 100
        Portfolio p = holding(candles, 246, candles.get(246).close());
        List<Signal> signals = new IndexMeanReversionStrategy().evaluate(snapshot(candles), p);
        assertEquals(1, signals.size());
        assertEquals(Signal.Action.EXIT, signals.get(0).action());
        assertTrue(signals.get(0).reason().contains("RSI"), signals.get(0).reason());
    }

    @Test
    void exitsAfterTenTradingDaysHeld() {
        // slow drift down: RSI(2) = 0 (no RSI exit), too gentle for the stop
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 250; i++) candles.add(bar(i, 150 - i * 0.01));
        Portfolio p = holding(candles, 239, candles.get(239).close()); // 10 bars after entry day
        List<Signal> signals = new IndexMeanReversionStrategy().evaluate(snapshot(candles), p);
        assertEquals(1, signals.size());
        assertTrue(signals.get(0).reason().contains("timeout"), signals.get(0).reason());

        Portfolio younger = holding(candles, 240, candles.get(240).close()); // 9 bars
        assertTrue(new IndexMeanReversionStrategy().evaluate(snapshot(candles), younger).isEmpty());
    }

    @Test
    void exitsOnDisasterStop() {
        List<Candle> candles = risingThenDip(250, 0);
        double entry = candles.get(247).close();
        candles.set(249, bar(249, entry - 5));                 // ATR ≈ 1 → stop ≈ entry − 2.5
        Portfolio p = holding(candles, 247, entry);
        List<Signal> signals = new IndexMeanReversionStrategy().evaluate(snapshot(candles), p);
        assertEquals(1, signals.size());
        assertTrue(signals.get(0).reason().contains("disaster stop"), signals.get(0).reason());
    }
}
