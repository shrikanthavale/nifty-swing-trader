package com.shrikane.swingtrader.executor;

import com.shrikane.swingtrader.backtest.CostModel;
import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.MarketSnapshot;
import com.shrikane.swingtrader.journal.InMemoryJournal;
import com.shrikane.swingtrader.risk.Portfolio;
import com.shrikane.swingtrader.risk.RiskManager;
import com.shrikane.swingtrader.signal.Signal;
import com.shrikane.swingtrader.signal.Strategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daily paper cycle against an in-memory journal — multi-day scenarios
 * where each "evening" run sees data up to that day, exactly like production.
 */
class PaperTraderTest {

    private static final LocalDate MON = LocalDate.of(2026, 1, 5);
    private static final LocalDate TUE = LocalDate.of(2026, 1, 6);
    private static final LocalDate WED = LocalDate.of(2026, 1, 7);

    private static Candle candle(String s, LocalDate d, double open, double close) {
        return new Candle(s, d, open, Math.max(open, close) + 1,
                Math.min(open, close) - 1, close, 10_000);
    }

    /** Candles up to and including {@code upTo} — what the DB would hold. */
    private static Map<String, List<Candle>> upTo(List<Candle> all, LocalDate upTo) {
        Map<String, List<Candle>> map = new HashMap<>();
        for (Candle c : all) {
            if (!c.date().isAfter(upTo)) {
                map.computeIfAbsent(c.symbol(), s -> new ArrayList<>()).add(c);
            }
        }
        return map;
    }

    private record Step(LocalDate on, Signal sig) {}

    private static final class Scripted implements Strategy {
        final List<Step> steps = new ArrayList<>();
        Scripted enter(LocalDate on, String sym, double ref, double stop) {
            steps.add(new Step(on, new Signal(sym, Signal.Action.ENTER, ref, stop, 1, "t")));
            return this;
        }
        Scripted exit(LocalDate on, String sym, double ref) {
            steps.add(new Step(on, new Signal(sym, Signal.Action.EXIT, ref, 0, 0, "x")));
            return this;
        }
        @Override public String name() { return "scripted"; }
        @Override public List<Signal> evaluate(MarketSnapshot snap, Portfolio p) {
            return steps.stream().filter(s -> s.on().equals(snap.asOf()))
                    .map(Step::sig).toList();
        }
    }

    private static PaperTrader trader(Strategy strategy, InMemoryJournal journal) {
        return new PaperTrader(strategy, new RiskManager(), new CostModel(0),
                journal, 100_000);
    }

    @Test
    void abortsOnStaleData() {
        InMemoryJournal journal = new InMemoryJournal();
        List<Candle> candles = List.of(candle("TCS", MON, 99, 100));
        // asking for TUE but data ends MON
        PaperTrader.DailyResult result =
                trader(new Scripted(), journal).runDaily(upTo(candles, MON), TUE);
        assertTrue(result.aborted());
        assertTrue(result.text().contains("DO NOT TRADE"));
        assertTrue(journal.orders.isEmpty());
    }

    @Test
    void signalQueuesOrderThenFillsNextMorningAndExitsRoundTrip() {
        InMemoryJournal journal = new InMemoryJournal();
        List<Candle> all = List.of(
                candle("TCS", MON, 99, 100),
                candle("TCS", TUE, 104, 105),
                candle("TCS", WED, 106, 107));
        Scripted strategy = new Scripted()
                .enter(MON, "TCS", 100, 95)     // Monday evening signal
                .exit(TUE, "TCS", 105);         // Tuesday evening exit signal
        PaperTrader trader = trader(strategy, journal);

        // Monday evening: nothing to fill, one order queued
        var mon = trader.runDaily(upTo(all, MON), MON);
        assertFalse(mon.aborted());
        assertEquals(1, mon.queued().size());
        assertEquals(1, journal.pendingOrders().size());
        assertEquals(100_000, mon.equity(), 1e-9);
        // signal journaled as approved
        assertEquals(1, journal.signals.size());
        assertTrue(journal.signals.get(0).approved());

        // Tuesday evening: entry filled at TUESDAY'S OPEN 104 (not Monday close)
        var tue = trader.runDaily(upTo(all, TUE), TUE);
        assertEquals(1, tue.fills().size());
        assertTrue(tue.fills().get(0).contains("BOUGHT TCS"));
        assertEquals(1, journal.openPositions().size());
        assertEquals(104, journal.openPositions().get(0).entryPrice(), 1e-9);
        // sized on ₹100k equity: risk 1000 / stop-dist 5 = 200 shares
        assertEquals(200, journal.openPositions().get(0).quantity());
        // equity marked at Tuesday close 105
        assertEquals(1, tue.queued().size()); // the exit order for tomorrow
        assertTrue(tue.equity() > 100_000);   // 200 shares +1 vs fill, minus charges

        // Wednesday evening: exit filled at WEDNESDAY'S OPEN 106
        var wed = trader.runDaily(upTo(all, WED), WED);
        assertEquals(1, wed.fills().size());
        assertTrue(wed.fills().get(0).contains("SOLD TCS"));
        assertTrue(journal.openPositions().isEmpty());
        // full round trip profit: bought 104, sold 106, 200 shares, minus charges
        double charges = journal.orders.values().stream()
                .filter(o -> o.charges() != null).mapToDouble(o -> o.charges()).sum();
        assertEquals(100_000 + 200 * 2 - charges, wed.equity(), 1e-6);
    }

    @Test
    void haltedSymbolEntryCancelledButExitStaysPending() {
        InMemoryJournal journal = new InMemoryJournal();
        // OTHER trades every day; TCS missing on TUE
        List<Candle> all = List.of(
                candle("TCS", MON, 99, 100),
                candle("OTHER", MON, 50, 50),
                candle("OTHER", TUE, 50, 50));
        Scripted strategy = new Scripted().enter(MON, "TCS", 100, 95);
        PaperTrader trader = trader(strategy, journal);

        trader.runDaily(upTo(all, MON), MON);
        var tue = trader.runDaily(upTo(all, TUE), TUE);

        assertTrue(journal.openPositions().isEmpty());
        assertTrue(journal.orders.values().stream()
                .anyMatch(o -> o.status().equals("CANCELLED")));
        assertTrue(tue.warnings().stream().anyMatch(w -> w.contains("cancelled")));
    }

    @Test
    void killSwitchBlocksEntriesUntilManualPeakReset() {
        InMemoryJournal journal = new InMemoryJournal();
        journal.setCash(93_000);                       // bootstrap: already drawn down
        journal.saveEquity(MON.minusDays(7), 100_000, 100_000, 0); // prior-week peak
        journal.saveEquity(MON.minusDays(3), 93_000, 93_000, 0);   // last Friday: already down
        // (drawdown happened LAST week — otherwise the weekly-loss pause also
        // fires this week and correctly keeps blocking entries after the reset)
        List<Candle> all = List.of(
                candle("TCS", MON, 99, 100),
                candle("TCS", TUE, 100, 100));
        Scripted strategy = new Scripted()
                .enter(MON, "TCS", 100, 95)
                .enter(TUE, "TCS", 100, 95);
        PaperTrader trader = trader(strategy, journal);

        // Monday: equity 93k vs peak 100k = -7% → kill switch
        var mon = trader.runDaily(upTo(all, MON), MON);
        assertTrue(mon.killSwitchActive());
        assertTrue(mon.queued().isEmpty());
        assertEquals(1, journal.signals.size());
        assertFalse(journal.signals.get(0).approved());
        assertTrue(journal.signals.get(0).note().contains("kill switch"));
        assertTrue(mon.warnings().stream().anyMatch(w -> w.contains("reset-peak")));

        // manual review: reset the peak marker (what `paper reset-peak` does)
        journal.setMeta(PaperTrader.META_PEAK_RESET, TUE.toString());

        // Tuesday: peak now computed since TUE → entries flow again
        var tue = trader.runDaily(upTo(all, TUE), TUE);
        assertFalse(tue.killSwitchActive());
        assertEquals(1, tue.queued().size());
    }

    @Test
    void firstRunBootstrapsStartingCapital() {
        InMemoryJournal journal = new InMemoryJournal();
        List<Candle> all = List.of(candle("TCS", MON, 99, 100));
        trader(new Scripted(), journal).runDaily(upTo(all, MON), MON);
        assertEquals(100_000, journal.cash(), 1e-9);
        assertEquals(100_000, journal.equity.get(MON)[0], 1e-9);
    }
}
