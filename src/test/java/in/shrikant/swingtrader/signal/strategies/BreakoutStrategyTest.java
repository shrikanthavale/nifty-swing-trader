package in.shrikant.swingtrader.signal.strategies;

import in.shrikant.swingtrader.data.Candle;
import in.shrikant.swingtrader.data.MarketSnapshot;
import in.shrikant.swingtrader.risk.Portfolio;
import in.shrikant.swingtrader.risk.Position;
import in.shrikant.swingtrader.signal.Signal;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakoutStrategyTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);

    /** Rising series: every close is a new high; ATR ≈ 2 (high-low band of 2). */
    private static List<Candle> risingSeries(int bars, long lastVolume) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < bars; i++) {
            double close = 100 + i * 0.1;
            long volume = (i == bars - 1) ? lastVolume : 1000;
            candles.add(new Candle("SYM", START.plusDays(i),
                    close, close + 1, close - 1, close, volume));
        }
        return candles;
    }

    private static MarketSnapshot snapshot(List<Candle> candles) {
        return MarketSnapshot.of(Map.of("SYM", candles),
                candles.get(candles.size() - 1).date());
    }

    @Test
    void entersOnNewHighWithVolumeSurgeAboveSma() {
        List<Candle> candles = risingSeries(210, 2000); // 2x the 1000 average
        List<Signal> signals = new BreakoutStrategy()
                .evaluate(snapshot(candles), new Portfolio(100_000));

        assertEquals(1, signals.size());
        Signal s = signals.get(0);
        assertEquals(Signal.Action.ENTER, s.action());
        assertTrue(s.stopPrice() < s.referencePrice());
        assertEquals(2.0, s.rank(), 0.1); // rank = volume ratio
    }

    @Test
    void noEntryWithoutVolumeConfirmation() {
        List<Candle> candles = risingSeries(210, 1000); // no surge
        assertTrue(new BreakoutStrategy()
                .evaluate(snapshot(candles), new Portfolio(100_000)).isEmpty());
    }

    @Test
    void noEntryBelowTrendSma() {
        // falling series: last close is far below the 200-day SMA (and no 50d high)
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 210; i++) {
            double close = 300 - i * 0.5;
            candles.add(new Candle("SYM", START.plusDays(i),
                    close, close + 1, close - 1, close, i == 209 ? 5000 : 1000));
        }
        assertTrue(new BreakoutStrategy()
                .evaluate(snapshot(candles), new Portfolio(100_000)).isEmpty());
    }

    @Test
    void noEntryWhenAlreadyHolding() {
        List<Candle> candles = risingSeries(210, 2000);
        Portfolio portfolio = new Portfolio(100_000);
        portfolio.applyBuy(new Position("SYM", 10, 100,
                candles.get(205).date(), 90), 1000);
        List<Signal> signals = new BreakoutStrategy().evaluate(snapshot(candles), portfolio);
        assertTrue(signals.stream().noneMatch(s -> s.action() == Signal.Action.ENTER));
    }

    @Test
    void exitsWhenCloseFallsBelowTrailingStop() {
        // rise to a peak of 110, then drop to 104: trail = 110 - 2.5*ATR(2) = 105
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            double close = 100 + i * 0.05; // ends near 110
            candles.add(new Candle("SYM", START.plusDays(i),
                    close, close + 1, close - 1, close, 1000));
        }
        candles.add(new Candle("SYM", START.plusDays(200), 105, 106, 103, 104, 1000));

        Portfolio portfolio = new Portfolio(100_000);
        LocalDate entryDate = START.plusDays(195); // held ~5 calendar days → no timeout
        portfolio.applyBuy(new Position("SYM", 10, 109, entryDate, 100), 1090);

        List<Signal> signals = new BreakoutStrategy().evaluate(snapshot(candles), portfolio);
        assertEquals(1, signals.size());
        assertEquals(Signal.Action.EXIT, signals.get(0).action());
        assertTrue(signals.get(0).reason().contains("trail"));
    }

    @Test
    void holdsWhileAboveTrailAndUnderTimeout() {
        // same shape but only dips to 106 — above the 105 trail
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            double close = 100 + i * 0.05;
            candles.add(new Candle("SYM", START.plusDays(i),
                    close, close + 1, close - 1, close, 1000));
        }
        candles.add(new Candle("SYM", START.plusDays(200), 107, 108, 105, 106, 1000));

        Portfolio portfolio = new Portfolio(100_000);
        portfolio.applyBuy(new Position("SYM", 10, 109, START.plusDays(195), 100), 1090);

        assertTrue(new BreakoutStrategy()
                .evaluate(snapshot(candles), portfolio).isEmpty());
    }

    @Test
    void exitsOnTimeoutEvenAboveTrail() {
        List<Candle> candles = risingSeries(210, 1000);
        Portfolio portfolio = new Portfolio(100_000);
        // held 20 calendar days ≈ 14 trading days ≥ 10 → timeout
        portfolio.applyBuy(new Position("SYM", 10, 100,
                candles.get(209).date().minusDays(20), 90), 1000);

        List<Signal> signals = new BreakoutStrategy().evaluate(snapshot(candles), portfolio);
        assertEquals(1, signals.size());
        assertEquals(Signal.Action.EXIT, signals.get(0).action());
        assertTrue(signals.get(0).reason().contains("timeout"));
    }
}
