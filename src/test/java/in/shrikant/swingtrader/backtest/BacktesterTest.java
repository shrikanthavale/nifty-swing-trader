package in.shrikant.swingtrader.backtest;

import in.shrikant.swingtrader.data.Candle;
import in.shrikant.swingtrader.data.MarketSnapshot;
import in.shrikant.swingtrader.risk.Portfolio;
import in.shrikant.swingtrader.risk.RiskManager;
import in.shrikant.swingtrader.signal.Signal;
import in.shrikant.swingtrader.signal.Strategy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backtester mechanics on synthetic data with a scripted strategy — every
 * number below is hand-computable.
 */
class BacktesterTest {

    private static final LocalDate D1 = LocalDate.of(2026, 1, 5); // Mon
    private static final LocalDate D2 = LocalDate.of(2026, 1, 6);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 7);
    private static final LocalDate D4 = LocalDate.of(2026, 1, 8);

    /** Candle with distinct open/close so fill-at-open is distinguishable. */
    private static Candle candle(String symbol, LocalDate date, double open, double close) {
        return new Candle(symbol, date, open, Math.max(open, close) + 1,
                Math.min(open, close) - 1, close, 10_000);
    }

    /** Scripted strategy: emits given signals when asOf matches. */
    private static final class Scripted implements Strategy {
        record Step(LocalDate on, Signal signal) {}
        final List<Step> steps = new ArrayList<>();

        Scripted enter(LocalDate on, String symbol, double ref, double stop) {
            steps.add(new Step(on, new Signal(symbol, Signal.Action.ENTER, ref, stop, 1, "t")));
            return this;
        }
        Scripted exit(LocalDate on, String symbol, double ref) {
            steps.add(new Step(on, new Signal(symbol, Signal.Action.EXIT, ref, 0, 0, "t-exit")));
            return this;
        }
        @Override public String name() { return "scripted"; }
        @Override public List<Signal> evaluate(MarketSnapshot snapshot, Portfolio portfolio) {
            return steps.stream().filter(s -> s.on().equals(snapshot.asOf()))
                    .map(Scripted.Step::signal).toList();
        }
    }

    @Test
    void entrySignalFillsAtNextDayOpenNotSignalClose() {
        // TCS: D1 close 100 (signal day), D2 open 104 (fill day)
        Map<String, List<Candle>> candles = Map.of("TCS", List.of(
                candle("TCS", D1, 99, 100),
                candle("TCS", D2, 104, 105),
                candle("TCS", D3, 106, 107)));
        Scripted strategy = new Scripted()
                .enter(D1, "TCS", 100, 90)
                .exit(D2, "TCS", 105);

        CostModel zeroSlippage = new CostModel(0);
        Backtester.Result result = new Backtester(
                strategy, new RiskManager(), zeroSlippage, 100_000).run(candles, D1, D3);

        assertEquals(1, result.trades().size());
        Trade trade = result.trades().get(0);
        assertEquals(104, trade.entryFill(), 1e-9);   // D2 OPEN — not 100, not 105
        assertEquals(D2, trade.entryDate());
        assertEquals(106, trade.exitFill(), 1e-9);    // exit signal D2 → D3 open
        assertEquals(D3, trade.exitDate());
    }

    @Test
    void slippageMakesFillsAdverseAndChargesAreDeducted() {
        Map<String, List<Candle>> candles = Map.of("TCS", List.of(
                candle("TCS", D1, 99, 100),
                candle("TCS", D2, 100, 100),
                candle("TCS", D3, 100, 100),
                candle("TCS", D4, 100, 100)));
        Scripted strategy = new Scripted()
                .enter(D1, "TCS", 100, 90)
                .exit(D3, "TCS", 100);

        CostModel cm = new CostModel(0.001); // 0.1% per side
        Backtester.Result result = new Backtester(
                strategy, new RiskManager(), cm, 100_000).run(candles, D1, D4);

        Trade trade = result.trades().get(0);
        assertEquals(100.1, trade.entryFill(), 1e-9); // adverse buy
        assertEquals(99.9, trade.exitFill(), 1e-9);   // adverse sell
        assertTrue(trade.entryCharges() > 0);
        assertTrue(trade.exitCharges() > trade.entryCharges()); // DP charge on sell
        // flat prices + slippage + charges → guaranteed net loss
        assertTrue(trade.netPnl() < 0);
        // and equity reflects it exactly
        assertEquals(100_000 + trade.netPnl(), result.finalEquity(), 1e-6);
    }

    @Test
    void positionSizeRespectsOnePercentRisk() {
        Map<String, List<Candle>> candles = Map.of("TCS", List.of(
                candle("TCS", D1, 99, 100),
                candle("TCS", D2, 100, 100),
                candle("TCS", D3, 100, 100)));
        // stop 95 → risk ₹5/share; 1% of ₹100k = ₹1000 → 200 shares...
        // but 25% position cap: ₹25k / ₹100 = 250 → risk binds: 200 shares
        Scripted strategy = new Scripted().enter(D1, "TCS", 100, 95);

        Backtester.Result result = new Backtester(
                strategy, new RiskManager(), new CostModel(0), 100_000).run(candles, D1, D3);

        assertEquals(1, result.openAtEnd().size());
        assertEquals(200, result.openAtEnd().get(0).quantity());
    }

    @Test
    void haltedSymbolExitStaysQueuedUntilItTradesAgain() {
        // TCS trades D1, D2, then is halted on D3 (no candle), returns D4
        Map<String, List<Candle>> candles = Map.of(
                "TCS", List.of(
                        candle("TCS", D1, 99, 100),
                        candle("TCS", D2, 100, 101),
                        candle("TCS", D4, 108, 109)),
                "CAL", List.of( // keeps D3 a trading day
                        candle("CAL", D1, 1, 1), candle("CAL", D2, 1, 1),
                        candle("CAL", D3, 1, 1), candle("CAL", D4, 1, 1)));
        Scripted strategy = new Scripted()
                .enter(D1, "TCS", 100, 90)
                .exit(D2, "TCS", 101); // would fill D3 — but TCS is halted then

        Backtester.Result result = new Backtester(
                strategy, new RiskManager(), new CostModel(0), 100_000).run(candles, D1, D4);

        assertEquals(1, result.trades().size());
        assertEquals(D4, result.trades().get(0).exitDate()); // filled when trading resumed
        assertEquals(108, result.trades().get(0).exitFill(), 1e-9);
    }

    @Test
    void equityCurveHasOnePointPerTradingDay() {
        Map<String, List<Candle>> candles = Map.of("TCS", List.of(
                candle("TCS", D1, 99, 100),
                candle("TCS", D2, 100, 101),
                candle("TCS", D3, 101, 102)));
        Backtester.Result result = new Backtester(
                new Scripted(), new RiskManager(), new CostModel(0), 100_000)
                .run(candles, D1, D3);

        assertEquals(3, result.equityCurve().size());
        // no trades → equity flat at starting capital
        result.equityCurve().forEach(p -> assertEquals(100_000, p.equity(), 1e-9));
    }

    @Test
    void killSwitchBlocksEntriesThenCoolingOffResetResumes() {
        // 25 consecutive weekdays
        List<LocalDate> days = new ArrayList<>();
        LocalDate d = D1;
        while (days.size() < 25) {
            if (d.getDayOfWeek().getValue() <= 5) days.add(d);
            d = d.plusDays(1);
        }
        // CRASH: 100 → 74 on day 2 (a -6.5% equity hit on a 25%-cap position)
        List<Candle> crash = new ArrayList<>();
        List<Candle> other = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            double px = i < 2 ? 100 : 74;
            crash.add(candle("CRASH", days.get(i), px, px));
            other.add(candle("OTHER", days.get(i), 100, 100));
        }
        Scripted strategy = new Scripted()
                .enter(days.get(0), "CRASH", 100, 96)  // sized to the 25% cap: 250 shares
                .exit(days.get(3), "CRASH", 74)
                .enter(days.get(5), "OTHER", 100, 95)  // during cooling-off → blocked
                .enter(days.get(15), "OTHER", 100, 95); // after reset → trades

        Backtester.Result result = new Backtester(
                strategy, new RiskManager(), new CostModel(0), 100_000)
                .run(Map.of("CRASH", crash, "OTHER", other), days.get(0), days.get(24));

        assertEquals(1, result.killSwitchFirings().size());
        assertEquals(days.get(2), result.killSwitchFirings().get(0));
        assertEquals(1, result.trades().size());               // only CRASH round trip
        assertEquals("CRASH", result.trades().get(0).symbol());
        assertEquals(1, result.openAtEnd().size());            // OTHER entered late
        assertEquals("OTHER", result.openAtEnd().get(0).symbol());
        assertEquals(days.get(16), result.openAtEnd().get(0).entryDate()); // NOT day 6
    }

    @Test
    void presortedSnapshotAgreesWithCanonicalFactory() {
        List<Candle> bars = List.of(
                candle("TCS", D1, 99, 100),
                candle("TCS", D2, 100, 101),
                candle("TCS", D3, 101, 102));
        Map<String, List<Candle>> map = Map.of("TCS", bars);

        MarketSnapshot canonical = MarketSnapshot.of(map, D2);
        MarketSnapshot fast = MarketSnapshot.ofPresorted(map, D2);

        assertEquals(canonical.candles("TCS"), fast.candles("TCS"));
        assertEquals(2, fast.candles("TCS").size());
        assertTrue(fast.candles("TCS").stream().noneMatch(c -> c.date().isAfter(D2)));
    }
}
