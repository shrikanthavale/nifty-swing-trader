package com.shrikane.swingtrader.signal;

import com.shrikane.swingtrader.data.Candle;
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
}
