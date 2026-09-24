package com.shrikane.swingtrader.executor;

import com.shrikane.swingtrader.backtest.CostModel;
import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.MarketSnapshot;
import com.shrikane.swingtrader.journal.InMemoryJournal;
import com.shrikane.swingtrader.risk.Portfolio;
import com.shrikane.swingtrader.risk.Position;
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

/** Multi-sleeve evening cycle against in-memory journals. */
class SleeveCycleTest {

    private static final LocalDate FRI = LocalDate.of(2026, 1, 2);
    private static final LocalDate MON = LocalDate.of(2026, 1, 5);
    private static final LocalDate TUE = LocalDate.of(2026, 1, 6);

    private static Candle candle(String s, LocalDate d, double open, double close) {
        return new Candle(s, d, open, Math.max(open, close) + 1, Math.min(open, close) - 1, close, 10_000);
    }

    private static Map<String, List<Candle>> upTo(List<Candle> all, LocalDate upTo) {
        Map<String, List<Candle>> map = new HashMap<>();
        for (Candle c : all) {
            if (!c.date().isAfter(upTo)) map.computeIfAbsent(c.symbol(), s -> new ArrayList<>()).add(c);
        }
        return map;
    }

    /** Emits a fixed signal list on given dates. */
    private static final class Scripted implements Strategy {
        final Map<LocalDate, List<Signal>> byDate = new HashMap<>();

        Scripted on(LocalDate d, Signal.Action action, String sym, double ref, double stop) {
            byDate.computeIfAbsent(d, x -> new ArrayList<>())
                    .add(new Signal(sym, action, ref, stop, 1, "scripted"));
            return this;
        }

        @Override public String name() { return "scripted"; }

        @Override public List<Signal> evaluate(MarketSnapshot snap, Portfolio p) {
            return byDate.getOrDefault(snap.asOf(), List.of());
        }
    }

    private record Fixture(SleeveCycle cycle, InMemoryJournal a, InMemoryJournal b,
                           InMemoryJournal shadow, InMemoryJournal total) {}

    private static Fixture fixture(Strategy sa, Strategy sb, Strategy shadow) {
        InMemoryJournal a = new InMemoryJournal(), b = new InMemoryJournal(),
                sh = new InMemoryJournal(), total = new InMemoryJournal();
        SleeveCycle cycle = new SleeveCycle(List.of(
                SleeveCycle.Sleeve.of("a", sa, RiskManager.sleeveProfile(), CostModel.etf(0), a, 16_000, null, true),
                SleeveCycle.Sleeve.of("b", sb, RiskManager.sleeveProfile(), CostModel.etf(0), b, 16_000, null, true),
                SleeveCycle.Sleeve.of("shadow", shadow, new RiskManager(), new CostModel(0), sh, 50_000, null, false)),
                total, 50_000);
        return new Fixture(cycle, a, b, sh, total);
    }

    @Test
    void twoSleevesHoldTheSameSymbolInSeparateLedgersAndFillTheFullSleeve() {
        List<Candle> all = List.of(
                candle("NIFTYBEES", MON, 279, 280), candle("NIFTYBEES", TUE, 290, 291));
        Fixture f = fixture(
                new Scripted().on(MON, Signal.Action.ENTER, "NIFTYBEES", 280, 262),
                new Scripted().on(MON, Signal.Action.ENTER, "NIFTYBEES", 280, 262),
                new Scripted());

        var mon = f.cycle().run(upTo(all, MON), MON);
        assertFalse(mon.aborted());
        assertEquals(2, mon.fundedOrders().size());
        assertEquals(57, mon.fundedOrders().get(0).quantity());      // floor(16000 / 280): full sleeve
        assertEquals(50_000, mon.totalEquity(), 1e-9);               // 2 × 16k + 18k buffer

        // Tuesday: gap up to 290 — funded sleeves still fill the full 57 (as the broker would)
        var tue = f.cycle().run(upTo(all, TUE), TUE);
        assertEquals(57, f.a().openPositions().get(0).quantity());
        assertEquals(57, f.b().openPositions().get(0).quantity());
        assertTrue(f.a().cash() < 0, "gap-up overdraft is absorbed by the account buffer");
        double expected = 2 * (16_000 - 57 * 290 - CostModel.etf(0).buyCharges(57 * 290) + 57 * 291) + 18_000;
        assertEquals(expected, tue.totalEquity(), 1e-6);
    }

    @Test
    void accountKillSwitchBlocksFundedEntriesButNotExitsOrTheShadow() {
        List<Candle> all = List.of(
                candle("NIFTYBEES", FRI, 280, 280), candle("NIFTYBEES", MON, 221, 220),
                candle("TCS", FRI, 100, 100), candle("TCS", MON, 100, 101));
        Fixture f = fixture(
                new Scripted().on(MON, Signal.Action.EXIT, "NIFTYBEES", 220, 0),
                new Scripted().on(MON, Signal.Action.ENTER, "NIFTYBEES", 220, 200),
                new Scripted().on(MON, Signal.Action.ENTER, "TCS", 101, 96));
        // sleeve a is fully invested in NIFTYBEES; the account peaked at ₹50,000 on Friday
        f.a().setCash(40);
        f.a().addPosition(new Position("NIFTYBEES", 57, 280, FRI, 262));
        f.total().saveEquity(FRI, 50_000, 34_040, 1);

        var mon = f.cycle().run(upTo(all, MON), MON);
        // 40 + 57×220 + 16,000 + 18,000 = 46,580 → 6.8% below peak
        assertEquals(46_580, mon.totalEquity(), 1e-9);
        assertTrue(mon.killSwitch());
        assertTrue(mon.entriesBlocked());
        assertEquals(1, mon.fundedOrders().size(), "only sleeve a's exit");
        assertEquals(Signal.Action.EXIT, mon.fundedOrders().get(0).action());
        assertTrue(f.b().signals.get(0).note().contains("kill switch"));
        assertEquals(1, f.shadow().pendingOrders().size(), "the paper shadow has its own rails");
        assertTrue(mon.text().contains("KILL SWITCH"));
    }

    @Test
    void secondRunOnTheSameDayReEvaluatesNothing() {
        List<Candle> all = List.of(candle("NIFTYBEES", MON, 279, 280));
        Fixture f = fixture(new Scripted().on(MON, Signal.Action.ENTER, "NIFTYBEES", 280, 262),
                new Scripted(), new Scripted());
        f.cycle().run(upTo(all, MON), MON);
        var again = f.cycle().run(upTo(all, MON), MON);
        assertTrue(again.alreadyRan());
        assertEquals(1, f.a().orders.size(), "no duplicate order");
        assertEquals(1, f.a().signals.size(), "no duplicate journal rows");
        assertEquals(1, again.fundedOrders().size(), "pending orders still listed for (re)submission");
    }

    @Test
    void staleEtfDataAbortsTheWholeCycle() {
        List<Candle> all = List.of(candle("NIFTYBEES", FRI, 280, 280), candle("TCS", MON, 100, 101));
        Fixture f = fixture(new Scripted(), new Scripted(), new Scripted());
        var mon = f.cycle().run(upTo(all, MON), MON);
        assertTrue(mon.aborted());
        assertTrue(mon.text().contains("NIFTYBEES") && mon.text().contains("DO NOT TRADE"));
        assertTrue(f.total().equity.isEmpty());
    }

    @Test
    void fundedSleeveNeverSizesAboveItsCapitalAfterGains() {
        List<Candle> all = List.of(candle("NIFTYBEES", MON, 279, 280));
        Fixture f = fixture(new Scripted().on(MON, Signal.Action.ENTER, "NIFTYBEES", 280, 262),
                new Scripted(), new Scripted());
        f.a().setCash(20_000);                                        // sleeve grew to ₹20k
        var mon = f.cycle().run(upTo(all, MON), MON);
        assertEquals(57, mon.fundedOrders().get(0).quantity());       // still ₹16k worth
    }
}
