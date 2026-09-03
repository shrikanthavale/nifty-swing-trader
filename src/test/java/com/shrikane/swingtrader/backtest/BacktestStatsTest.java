package com.shrikane.swingtrader.backtest;

import com.shrikane.swingtrader.backtest.Backtester.EquityPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestStatsTest {

    private static Trade trade(LocalDate exit, double entryFill, double exitFill, int qty) {
        return new Trade("X", qty, exit.minusDays(5), entryFill, exit, exitFill,
                10, 20, "test");
    }

    @Test
    void maxDrawdownHandComputed() {
        List<EquityPoint> curve = List.of(
                new EquityPoint(LocalDate.of(2026, 1, 1), 100_000),
                new EquityPoint(LocalDate.of(2026, 1, 2), 110_000),  // peak
                new EquityPoint(LocalDate.of(2026, 1, 3), 99_000),   // -10% from peak
                new EquityPoint(LocalDate.of(2026, 1, 4), 120_000),  // new peak
                new EquityPoint(LocalDate.of(2026, 1, 5), 114_000)); // -5%
        assertEquals(0.10, BacktestStats.maxDrawdown(curve), 1e-9);
    }

    @Test
    void monotonicCurveHasZeroDrawdown() {
        List<EquityPoint> curve = List.of(
                new EquityPoint(LocalDate.of(2026, 1, 1), 100_000),
                new EquityPoint(LocalDate.of(2026, 1, 2), 101_000));
        assertEquals(0, BacktestStats.maxDrawdown(curve), 1e-9);
    }

    @Test
    void statsFromKnownTrades() {
        // Trade 1: (110-100)*10 = +100 gross, -30 charges = +70 net  (first half)
        // Trade 2: (95-100)*10  = -50 gross, -30 charges = -80 net   (second half)
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        List<Trade> trades = List.of(
                trade(LocalDate.of(2026, 3, 1), 100, 110, 10),
                trade(LocalDate.of(2026, 10, 1), 100, 95, 10));
        Backtester.Result result = new Backtester.Result("test", start, end,
                100_000,
                List.of(new EquityPoint(start, 100_000), new EquityPoint(end, 99_990)),
                trades, List.of(), 99_990, List.of());

        BacktestStats stats = BacktestStats.from(result);
        assertEquals(2, stats.tradeCount());
        assertEquals(0.5, stats.winRate(), 1e-9);
        assertEquals(70, stats.avgWin(), 1e-9);
        assertEquals(-80, stats.avgLoss(), 1e-9);
        assertEquals(-5, stats.expectancy(), 1e-9);       // (70-80)/2
        assertEquals(60, stats.totalCharges(), 1e-9);     // 30+30
        assertEquals(70, stats.firstHalfPnl(), 1e-9);
        assertEquals(-80, stats.secondHalfPnl(), 1e-9);
        assertEquals(70.0 / 80.0, stats.profitFactor(), 1e-9);
        assertFalse(stats.meetsExpectancyBar());
        assertFalse(stats.meetsTradeCountBar());
        assertFalse(stats.meetsBothHalvesBar());
    }

    @Test
    void emptyResultProducesZerosNotNaNs() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        Backtester.Result result = new Backtester.Result("test", start,
                start.plusDays(10), 100_000,
                List.of(new EquityPoint(start, 100_000)), List.of(), List.of(), 100_000,
                List.of());
        BacktestStats stats = BacktestStats.from(result);
        assertEquals(0, stats.tradeCount());
        assertEquals(0, stats.expectancy(), 1e-9);
        assertEquals(0, stats.maxDrawdown(), 1e-9);
        assertFalse(stats.meetsExpectancyBar());
    }

    @Test
    void niceTicksAreCleanAndSpanTheRange() {
        double[] ticks = HtmlReport.niceTicks(97_432, 143_210, 5);
        assertTrue(ticks[0] <= 97_432);
        assertTrue(ticks[ticks.length - 1] >= 143_210);
        double step = ticks[1] - ticks[0];
        for (int i = 2; i < ticks.length; i++) {
            assertEquals(step, ticks[i] - ticks[i - 1], 1e-6); // uniform
        }
    }

    @Test
    void htmlReportContainsTheEssentials() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        Backtester.Result result = new Backtester.Result("pullback-v1", start,
                start.plusDays(100), 100_000,
                List.of(new EquityPoint(start, 100_000),
                        new EquityPoint(start.plusDays(100), 105_000)),
                List.of(trade(start.plusDays(50), 100, 110, 10)),
                List.of(), 105_000, List.of(start.plusDays(30)));
        String html = HtmlReport.render(result, BacktestStats.from(result));
        assertTrue(html.contains("<!doctype html"));
        assertTrue(html.contains("pullback-v1"));
        assertTrue(html.contains("Equity curve"));
        assertTrue(html.contains("Drawdown"));
        assertTrue(html.contains("Acceptance bar"));
        assertTrue(html.contains("<svg"));
        assertTrue(html.contains("prefers-color-scheme: dark"));
    }
}
