package com.shrikane.swingtrader.signal;

import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.MarketSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorsTest {

    private static final LocalDate D = LocalDate.of(2026, 1, 1);

    private static Candle candle(int day, double close, long volume) {
        return new Candle("X", D.plusDays(day), close, close + 1, close - 1, close, volume);
    }

    @Test
    void avgVolumeOverLastPeriod() {
        List<Candle> candles = List.of(
                candle(0, 100, 1000), candle(1, 100, 2000), candle(2, 100, 3000));
        assertEquals(2500, Indicators.avgVolume(candles, 2), 1e-9); // last two
        assertEquals(2000, Indicators.avgVolume(candles, 3), 1e-9);
        assertTrue(Double.isNaN(Indicators.avgVolume(candles, 4)));
    }

    @Test
    void highestCloseSinceDate() {
        List<Candle> candles = List.of(
                candle(0, 120, 1000), // before the cutoff — must be ignored
                candle(5, 100, 1000), candle(6, 108, 1000), candle(7, 104, 1000));
        assertEquals(108, Indicators.highestCloseSince(candles, D.plusDays(5)), 1e-9);
        assertEquals(104, Indicators.highestCloseSince(candles, D.plusDays(7)), 1e-9);
        assertTrue(Double.isNaN(Indicators.highestCloseSince(candles, D.plusDays(8))));
    }

    @Test
    void highestCloseOverWindow() {
        List<Candle> candles = List.of(
                candle(0, 120, 1000), candle(1, 100, 1000), candle(2, 110, 1000));
        assertEquals(110, Indicators.highestClose(candles, 2), 1e-9);
        assertEquals(120, Indicators.highestClose(candles, 3), 1e-9);
    }

    @Test
    void breadthCountsSymbolsAboveTheirSma() {
        // A: last close 30 > sma(3)=20  |  B: last close 10 < sma(3)=20
        java.util.Map<String, java.util.List<Candle>> bars = java.util.Map.of(
                "A", List.of(candle(0, 10, 1), candle(1, 20, 1), candle(2, 30, 1)),
                "B", List.of(candle(0, 30, 1), candle(1, 20, 1), candle(2, 10, 1)));
        MarketSnapshot snap = MarketSnapshot.of(bars, D.plusDays(2));
        assertEquals(0.5, Indicators.breadthAboveSma(snap, 3), 1e-9);
    }

    @Test
    void breadthExcludesShortHistoriesAndIsNaNWhenNoneQualify() {
        java.util.Map<String, java.util.List<Candle>> bars = java.util.Map.of(
                "A", List.of(candle(0, 10, 1), candle(1, 20, 1), candle(2, 30, 1)),
                "SHORT", List.of(candle(2, 100, 1)));
        MarketSnapshot snap = MarketSnapshot.of(bars, D.plusDays(2));
        assertEquals(1.0, Indicators.breadthAboveSma(snap, 3), 1e-9); // SHORT excluded
        assertTrue(Double.isNaN(Indicators.breadthAboveSma(snap, 10)));
    }

    /** Closes whose daily log returns alternate +a, -a, +a, ... */
    private static List<Candle> alternating(int bars, double a) {
        List<Candle> out = new java.util.ArrayList<>();
        double logPrice = Math.log(100);
        for (int i = 0; i < bars; i++) {
            if (i > 0) logPrice += (i % 2 == 1) ? a : -a;
            out.add(candle(i, Math.exp(logPrice), 1000));
        }
        return out;
    }

    @Test
    void realizedVolIsAnnualizedSampleStdevOfLogReturns() {
        List<Candle> candles = alternating(21, 0.01);    // 20 returns: +1%, -1%, ...
        double expected = Math.sqrt(20 * 0.01 * 0.01 / 19) * Math.sqrt(252);
        assertEquals(expected, Indicators.realizedVol(candles, 20), 1e-12);
        assertTrue(Double.isNaN(Indicators.realizedVol(candles.subList(0, 20), 20)));
    }

    @Test
    void realizedVolSeriesEndsWithTodaysValue() {
        List<Candle> candles = alternating(30, 0.01);
        double[] series = Indicators.realizedVolSeries(candles, 20, 10); // needs 30 bars
        assertEquals(10, series.length);
        assertEquals(Indicators.realizedVol(candles, 20), series[9], 1e-12);
        assertEquals(Indicators.realizedVol(candles.subList(0, 21), 20), series[0], 1e-12);
        assertEquals(0, Indicators.realizedVolSeries(candles, 20, 11).length);
    }

    @Test
    void medianOddEvenAndEmpty() {
        assertEquals(2, Indicators.median(new double[]{3, 1, 2}), 1e-12);
        assertEquals(2.5, Indicators.median(new double[]{4, 1, 3, 2}), 1e-12);
        assertTrue(Double.isNaN(Indicators.median(new double[0])));
    }

    @Test
    void totalReturnOverPeriod() {
        List<Candle> candles = List.of(candle(0, 100, 0), candle(1, 50, 0), candle(2, 110, 0));
        assertEquals(0.10, Indicators.totalReturn(candles, 2), 1e-12);
        assertEquals(1.20, Indicators.totalReturn(candles, 1), 1e-12);
        assertTrue(Double.isNaN(Indicators.totalReturn(candles, 3)));
    }

    @Test
    void barsSinceCountsBarsStrictlyAfterTheDate() {
        List<Candle> candles = List.of(candle(0, 1, 0), candle(3, 1, 0), candle(4, 1, 0), candle(7, 1, 0));
        assertEquals(2, Indicators.barsSince(candles, D.plusDays(3)));   // days 4 and 7
        assertEquals(4, Indicators.barsSince(candles, D.minusDays(1)));
        assertEquals(0, Indicators.barsSince(candles, D.plusDays(7)));
    }
}
