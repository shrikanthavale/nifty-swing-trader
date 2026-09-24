package com.shrikane.swingtrader.signal.strategies;

import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.MarketSnapshot;
import com.shrikane.swingtrader.risk.Portfolio;
import com.shrikane.swingtrader.risk.Position;
import com.shrikane.swingtrader.signal.Signal;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectorRotationStrategyTest {

    // Daily bars from Jan 1: index 90 = Apr 1 (a review day), 91 = Apr 2, 92 = Apr 3
    private static final LocalDate JAN1 = LocalDate.of(2025, 1, 1);
    private static final int APR1 = 90;

    /** Geometric series with the given daily growth, {@code bars} long, starting at bar {@code from}. */
    private static List<Candle> series(String sym, int from, int bars, double dailyGrowth) {
        List<Candle> out = new ArrayList<>();
        double close = 100;
        for (int i = from; i < from + bars; i++) {
            out.add(new Candle(sym, JAN1.plusDays(i), close, close * 1.005, close * 0.995, close, 1000));
            close *= 1 + dailyGrowth;
        }
        return out;
    }

    /** Market up to bar {@code lastIndex}: BANKBEES +0.5%/day, ITBEES +0.1%/day. */
    private static Map<String, List<Candle>> market(double bankGrowth, double itGrowth) {
        Map<String, List<Candle>> m = new HashMap<>();
        m.put("BANKBEES", series("BANKBEES", 0, 100, bankGrowth));
        m.put("ITBEES", series("ITBEES", 0, 100, itGrowth));
        return m;
    }

    private static MarketSnapshot at(Map<String, List<Candle>> m, int index) {
        return MarketSnapshot.of(m, JAN1.plusDays(index));
    }

    private static Portfolio holding(String sym, Map<String, List<Candle>> m, int entryIndex) {
        Portfolio p = new Portfolio(16_000);
        double entry = m.get(sym).get(entryIndex).close();
        p.applyBuy(new Position(sym, 10, entry, JAN1.plusDays(entryIndex), entry * 0.9), 0);
        return p;
    }

    @Test
    void entersTheLeaderOnTheReviewDayWhenFlat() {
        List<Signal> signals = new SectorRotationStrategy()
                .evaluate(at(market(0.005, 0.001), APR1), new Portfolio(16_000));
        assertEquals(1, signals.size());
        assertEquals(Signal.Action.ENTER, signals.get(0).action());
        assertEquals("BANKBEES", signals.get(0).symbol());
    }

    @Test
    void doesNothingBetweenReviews() {
        assertTrue(new SectorRotationStrategy()
                .evaluate(at(market(0.005, 0.001), APR1 + 2), new Portfolio(16_000)).isEmpty());
    }

    @Test
    void holdsWhileTheHoldingIsStillTheLeader() {
        var m = market(0.005, 0.001);
        assertTrue(new SectorRotationStrategy()
                .evaluate(at(m, APR1), holding("BANKBEES", m, 70)).isEmpty());
    }

    @Test
    void rotationExitsOnReviewDayThenEntersTheNewLeaderNextDay() {
        var m = market(0.005, 0.001);
        List<Signal> review = new SectorRotationStrategy().evaluate(at(m, APR1), holding("ITBEES", m, 70));
        assertEquals(1, review.size(), "exit only — cash is still tied up in ITBEES");
        assertEquals(Signal.Action.EXIT, review.get(0).action());
        assertEquals("ITBEES", review.get(0).symbol());
        assertTrue(review.get(0).reason().contains("BANKBEES"));

        // next evening the exit has filled: flat → enter the review-day leader
        List<Signal> nextDay = new SectorRotationStrategy()
                .evaluate(at(m, APR1 + 1), new Portfolio(16_000));
        assertEquals(1, nextDay.size());
        assertEquals(Signal.Action.ENTER, nextDay.get(0).action());
        assertEquals("BANKBEES", nextDay.get(0).symbol());
        assertTrue(nextDay.get(0).reason().contains("rotation"));
    }

    @Test
    void goesToCashWhenNoEtfHasAPositive63DayReturn() {
        var m = market(-0.001, -0.002);
        List<Signal> signals = new SectorRotationStrategy().evaluate(at(m, APR1), holding("BANKBEES", m, 70));
        assertEquals(1, signals.size());
        assertEquals(Signal.Action.EXIT, signals.get(0).action());
        assertTrue(signals.get(0).reason().contains("cash"));
        assertTrue(new SectorRotationStrategy().evaluate(at(m, APR1), new Portfolio(16_000)).isEmpty());
    }

    @Test
    void symbolWithFewerThan64CandlesIsNotRanked() {
        var m = market(0.002, 0.001);
        m.put("PHARMABEES", series("PHARMABEES", 40, 60, 0.05)); // huge return, 51 bars by Apr 1
        List<Signal> signals = new SectorRotationStrategy()
                .evaluate(at(m, APR1), new Portfolio(16_000));
        assertEquals(1, signals.size());
        assertEquals("BANKBEES", signals.get(0).symbol());
    }

    @Test
    void disasterStopIsCheckedBetweenReviews() {
        var m = market(0.005, 0.001);
        Portfolio p = holding("BANKBEES", m, APR1);
        List<Candle> bank = new ArrayList<>(m.get("BANKBEES"));
        Candle crash = bank.get(APR1 + 3);
        double close = bank.get(APR1).close() * 0.9;                  // −10% ≫ 2.5 × ATR(≈1%)
        bank.set(APR1 + 3, new Candle("BANKBEES", crash.date(), close, close, close, close, 1000));
        m.put("BANKBEES", bank);
        List<Signal> signals = new SectorRotationStrategy().evaluate(at(m, APR1 + 3), p);
        assertEquals(1, signals.size());
        assertTrue(signals.get(0).reason().contains("disaster stop"));
    }

    @Test
    void silentWithoutEnoughHistoryToRankAnything() {
        Map<String, List<Candle>> m = Map.of("BANKBEES", series("BANKBEES", 30, 70, 0.005)); // 61 bars by Apr 1
        assertTrue(new SectorRotationStrategy().evaluate(at(m, APR1), new Portfolio(16_000)).isEmpty());
    }
}
