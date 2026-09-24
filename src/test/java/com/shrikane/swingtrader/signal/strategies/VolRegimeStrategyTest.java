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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolRegimeStrategyTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 1);
    private static final String SYM = "NIFTYBEES";

    /**
     * Log-price path: each bar adds {@code drift} plus ±amplitude (alternating),
     * where amplitude[i] comes from the supplied per-bar array. High/low hug
     * the close by ±0.5%.
     */
    private static List<Candle> path(double drift, double[] amplitude) {
        List<Candle> out = new ArrayList<>();
        double logPrice = Math.log(250);
        for (int i = 0; i < amplitude.length; i++) {
            if (i > 0) logPrice += drift + (i % 2 == 1 ? amplitude[i] : -amplitude[i]);
            double c = Math.exp(logPrice);
            out.add(new Candle(SYM, START.plusDays(i), c, c * 1.005, c * 0.995, c, 1000));
        }
        return out;
    }

    /** 150 stormy bars (±2%), then 150 calm bars whose swings shrink 0.5% → 0.1%. */
    private static double[] stormThenCalm() {
        double[] a = new double[300];
        for (int i = 0; i < 150; i++) a[i] = 0.02;
        for (int i = 150; i < 300; i++) a[i] = 0.005 - 0.004 * (i - 150) / 149.0;
        return a;
    }

    /** 300 calm bars (±0.3%), then 25 stormy ones (±3%). */
    private static double[] calmThenStorm() {
        double[] a = new double[325];
        for (int i = 0; i < 300; i++) a[i] = 0.003;
        for (int i = 300; i < 325; i++) a[i] = 0.03;
        return a;
    }

    private static MarketSnapshot snapshot(List<Candle> candles) {
        return MarketSnapshot.of(Map.of(SYM, candles), candles.get(candles.size() - 1).date());
    }

    @Test
    void entersWhenCalmAndAboveSma200() {
        List<Candle> candles = path(0.002, stormThenCalm());
        List<Signal> signals = new VolRegimeStrategy().evaluate(snapshot(candles), new Portfolio(16_000));
        assertEquals(1, signals.size());
        assertEquals(Signal.Action.ENTER, signals.get(0).action());
        assertEquals(SYM, signals.get(0).symbol());
        assertTrue(signals.get(0).stopPrice() < signals.get(0).referencePrice());
    }

    @Test
    void trendFilterBlocksCalmDowntrends() {
        List<Candle> candles = path(-0.002, stormThenCalm());
        VolRegimeStrategy.Regime regime = VolRegimeStrategy.regime(candles);
        assertNotNull(regime);
        assertTrue(regime.vol20() < regime.median252(), "calm, so only the trend filter blocks");
        assertTrue(new VolRegimeStrategy().evaluate(snapshot(candles), new Portfolio(16_000)).isEmpty());
    }

    @Test
    void exitsWhenVolatilityRisesAboveItsMedian() {
        List<Candle> candles = path(0.001, calmThenStorm());
        int n = candles.size();
        Portfolio p = new Portfolio(16_000);
        double entry = candles.get(n - 2).close();
        p.applyBuy(new Position(SYM, 50, entry, candles.get(n - 2).date(), entry * 0.8), 0);

        List<Signal> signals = new VolRegimeStrategy().evaluate(snapshot(candles), p);
        assertEquals(1, signals.size());
        assertEquals(Signal.Action.EXIT, signals.get(0).action());
        assertTrue(signals.get(0).reason().contains("regime off"), signals.get(0).reason());
    }

    @Test
    void holdsWhileTheRegimeStaysOn() {
        List<Candle> candles = path(0.002, stormThenCalm());
        int n = candles.size();
        Portfolio p = new Portfolio(16_000);
        double entry = candles.get(n - 5).close();
        p.applyBuy(new Position(SYM, 50, entry, candles.get(n - 5).date(), entry * 0.9), 0);
        assertTrue(new VolRegimeStrategy().evaluate(snapshot(candles), p).isEmpty());
    }

    @Test
    void silentUntil272Candles() {
        List<Candle> all = path(0.002, stormThenCalm());
        assertNull(VolRegimeStrategy.regime(all.subList(0, 271)));
        assertNotNull(VolRegimeStrategy.regime(all.subList(0, 272)));
        assertTrue(new VolRegimeStrategy()
                .evaluate(snapshot(all.subList(0, 271)), new Portfolio(16_000)).isEmpty());
    }
}
