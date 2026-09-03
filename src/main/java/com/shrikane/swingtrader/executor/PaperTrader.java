package com.shrikane.swingtrader.executor;

import com.shrikane.swingtrader.backtest.CostModel;
import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.MarketSnapshot;
import com.shrikane.swingtrader.journal.Journal;
import com.shrikane.swingtrader.journal.Journal.PendingOrder;
import com.shrikane.swingtrader.risk.Portfolio;
import com.shrikane.swingtrader.risk.Position;
import com.shrikane.swingtrader.risk.RiskManager;
import com.shrikane.swingtrader.risk.RiskManager.SizedOrder;
import com.shrikane.swingtrader.signal.Signal;
import com.shrikane.swingtrader.signal.Strategy;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 3: the full daily cycle with orders written to the journal instead
 * of Zerodha. Same Strategy, RiskManager, CostModel and fill semantics as
 * the Backtester — the milestone is 4+ weeks of paper trades matching what
 * the backtester would have done on the same days (blueprint §8).
 *
 * One evening run (after `download` has succeeded) does:
 *   1. STALE-DATA GUARD: refuse to do anything if the data's newest candle
 *      isn't the expected trading date (blueprint §9).
 *   2. Fill orders queued yesterday at TODAY's open through the CostModel
 *      (exits first; entries shrink on gap-ups; entry with no candle today
 *      is cancelled, exit stays pending).
 *   3. Mark equity at today's close; persist the daily snapshot.
 *   4. Evaluate the strategy on data up to today, risk-approve, journal
 *      every signal (approved or not), queue approved orders for tomorrow.
 *
 * Kill switch: like the backtester's simulated review, but here the reset IS
 * manual — `paper reset-peak` records a marker date and the peak is computed
 * from daily equity since that marker. While equity is ≥6% below the peak,
 * RiskManager blocks entries and the summary shouts about it.
 */
public final class PaperTrader {

    /** Mirrors RiskManager's private thresholds for status reporting. */
    private static final double KILL_SWITCH_DRAWDOWN = 0.06;
    private static final double WEEKLY_PAUSE_FRACTION = 0.03;

    public static final String META_PEAK_RESET = "peak_reset_date";

    private final Strategy strategy;
    private final RiskManager riskManager;
    private final CostModel costModel;
    private final Journal journal;
    private final double startingCapital;

    public PaperTrader(Strategy strategy, RiskManager riskManager, CostModel costModel,
                       Journal journal, double startingCapital) {
        this.strategy = strategy;
        this.riskManager = riskManager;
        this.costModel = costModel;
        this.journal = journal;
        this.startingCapital = startingCapital;
    }

    /** Outcome of one daily cycle; {@link #text} is the human/Telegram summary. */
    public record DailyResult(
            LocalDate date,
            boolean aborted,
            List<String> fills,
            List<String> queued,
            List<String> warnings,
            double equity,
            double cash,
            int openPositions,
            boolean killSwitchActive,
            boolean entriesPaused,
            String text
    ) {}

    public DailyResult runDaily(Map<String, List<Candle>> candles, LocalDate today) {
        List<String> fills = new ArrayList<>();
        List<String> queued = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // ---- 0. first run bootstraps cash ----
        if (journal.cash() < 0) journal.setCash(startingCapital);

        // ---- 1. stale-data guard ----
        Map<String, Candle> todayBySymbol = new HashMap<>();
        LocalDate newest = null;
        for (Map.Entry<String, List<Candle>> e : candles.entrySet()) {
            List<Candle> list = e.getValue();
            if (list.isEmpty()) continue;
            Candle last = list.get(list.size() - 1);
            if (newest == null || last.date().isAfter(newest)) newest = last.date();
            if (last.date().equals(today)) todayBySymbol.put(e.getKey(), last);
        }
        if (newest == null || !newest.equals(today)) {
            String text = "PAPER " + today + ": ABORTED — stale data (newest candle "
                    + newest + ", expected " + today + "). DO NOT TRADE. Run `download` "
                    + "first; if today is an NSE holiday this is expected.";
            return new DailyResult(today, true, fills, queued, List.of(text),
                    0, journal.cash(), journal.openPositions().size(), false, false, text);
        }
        for (Position p : journal.openPositions()) {
            if (!todayBySymbol.containsKey(p.symbol())) {
                warnings.add("held symbol " + p.symbol() + " has no candle for " + today
                        + " (halted?) — marked at last known close");
            }
        }

        // ---- 2. fill yesterday's pending orders at today's open ----
        List<PendingOrder> pending = journal.pendingOrders();
        for (PendingOrder order : pending) {          // exits first
            if (order.action() == Signal.Action.EXIT) fillExit(order, todayBySymbol, today, fills);
        }
        for (PendingOrder order : pending) {
            if (order.action() == Signal.Action.ENTER) fillEntry(order, todayBySymbol, today, fills, warnings);
        }

        // ---- 3. mark equity at today's close ----
        Map<String, List<Candle>> candlesUpToToday = candles; // download guard ensures cutoff
        List<Position> positions = journal.openPositions();
        double cash = journal.cash();
        double equity = cash;
        for (Position p : positions) {
            equity += p.quantity() * lastClose(candles.get(p.symbol()), today, p.entryPrice());
        }
        journal.saveEquity(today, equity, cash, positions.size());

        LocalDate peakSince = journal.meta(META_PEAK_RESET)
                .map(LocalDate::parse).orElse(LocalDate.of(1970, 1, 1));
        double peak = Math.max(equity, journal.equityPeakSince(peakSince).orElse(equity));
        double weekStart = weekStartEquity(today).orElse(equity);
        double weeklyPnl = equity - weekStart;
        boolean killSwitch = equity <= peak * (1 - KILL_SWITCH_DRAWDOWN);
        boolean paused = weeklyPnl <= -WEEKLY_PAUSE_FRACTION * equity;

        // ---- 4. evaluate, approve, journal, queue ----
        MarketSnapshot snapshot = MarketSnapshot.ofPresorted(candlesUpToToday, today);
        Portfolio portfolio = new Portfolio(cash);
        positions.forEach(p -> portfolio.applyBuy(p, 0));

        List<Signal> signals = strategy.evaluate(snapshot, portfolio);
        List<SizedOrder> approved = riskManager.approve(signals, portfolio, equity, peak, weeklyPnl);

        for (Signal signal : signals) {
            SizedOrder match = approved.stream()
                    .filter(o -> o.signal() == signal).findFirst().orElse(null);
            String note = match != null ? ""
                    : killSwitch ? "kill switch active"
                    : paused ? "weekly loss pause"
                    : "not selected (slots/rank/size)";
            journal.recordSignal(today, signal, match != null, note);
            if (match != null) {
                journal.createOrder(today, signal.symbol(), signal.action(),
                        match.quantity(), signal.referencePrice(), signal.stopPrice());
                queued.add(signal.action() + " " + signal.symbol() + " x" + match.quantity()
                        + " @ next open (ref " + fmt(signal.referencePrice()) + ")");
            }
        }

        if (killSwitch) {
            warnings.add(String.format(Locale.ROOT,
                    "KILL SWITCH: equity ₹%,.0f is %.1f%% below peak ₹%,.0f — entries blocked. "
                            + "Review, then run `paper reset-peak` to re-enable.",
                    equity, (1 - equity / peak) * 100, peak));
        } else if (paused) {
            warnings.add(String.format(Locale.ROOT,
                    "Weekly loss pause: ₹%,.0f down this week — entries paused until next week.",
                    -weeklyPnl));
        }

        String text = summaryText(today, fills, queued, warnings, equity, cash,
                journal.openPositions(), candles);
        return new DailyResult(today, false, fills, queued, warnings,
                equity, cash, journal.openPositions().size(), killSwitch, paused, text);
    }

    // ---- fills (same semantics as Backtester) ----

    private void fillExit(PendingOrder order, Map<String, Candle> todayBySymbol,
                          LocalDate today, List<String> fills) {
        Candle candle = todayBySymbol.get(order.symbol());
        if (candle == null) return;                    // halted → stays PENDING
        Position position = journal.openPositions().stream()
                .filter(p -> p.symbol().equals(order.symbol())).findFirst().orElse(null);
        if (position == null) {
            journal.cancelOrder(order.id(), "no matching open position");
            return;
        }
        double fill = costModel.sellFillPrice(candle.open());
        double value = fill * position.quantity();
        double charges = costModel.sellCharges(value);
        journal.removePosition(order.symbol());
        journal.setCash(journal.cash() + value - charges);
        journal.fillOrder(order.id(), today, fill, charges);
        double pnl = (fill - position.entryPrice()) * position.quantity() - charges;
        fills.add(String.format(Locale.ROOT, "SOLD %s x%d @ %s (≈₹%,.0f pnl before entry charges)",
                order.symbol(), position.quantity(), fmt(fill), pnl));
    }

    private void fillEntry(PendingOrder order, Map<String, Candle> todayBySymbol,
                           LocalDate today, List<String> fills, List<String> warnings) {
        Candle candle = todayBySymbol.get(order.symbol());
        if (candle == null) {
            journal.cancelOrder(order.id(), "no candle on fill day (halted?)");
            warnings.add("entry " + order.symbol() + " cancelled — no candle today");
            return;
        }
        if (journal.openPositions().stream().anyMatch(p -> p.symbol().equals(order.symbol()))) {
            journal.cancelOrder(order.id(), "already holding");
            return;
        }
        double fill = costModel.buyFillPrice(candle.open());
        int qty = order.quantity();
        double cash = journal.cash();
        while (qty > 0 && fill * qty + costModel.buyCharges(fill * qty) > cash) {
            qty = Math.min(qty - 1, (int) Math.floor(cash / fill));
        }
        if (qty <= 0) {
            journal.cancelOrder(order.id(), "insufficient cash after gap");
            warnings.add("entry " + order.symbol() + " cancelled — gap made it unaffordable");
            return;
        }
        if (qty < order.quantity()) {
            warnings.add("entry " + order.symbol() + " shrunk " + order.quantity()
                    + " → " + qty + " (gap up)");
        }
        double value = fill * qty;
        double charges = costModel.buyCharges(value);
        journal.addPosition(new Position(order.symbol(), qty, fill, today, order.stopPrice()));
        journal.setCash(cash - value - charges);
        journal.fillOrder(order.id(), today, fill, charges);
        fills.add(String.format(Locale.ROOT, "BOUGHT %s x%d @ %s", order.symbol(), qty, fmt(fill)));
    }

    // ---- helpers ----

    private Optional<Double> weekStartEquity(LocalDate today) {
        WeekFields wf = WeekFields.ISO;
        LocalDate monday = today.with(wf.dayOfWeek(), 1);
        return journal.lastEquityBefore(monday);
    }

    private static double lastClose(List<Candle> candles, LocalDate upTo, double fallback) {
        if (candles == null) return fallback;
        for (int i = candles.size() - 1; i >= 0; i--) {
            if (!candles.get(i).date().isAfter(upTo)) return candles.get(i).close();
        }
        return fallback;
    }

    private String summaryText(LocalDate today, List<String> fills, List<String> queued,
                               List<String> warnings, double equity, double cash,
                               List<Position> positions,
                               Map<String, List<Candle>> candles) {
        StringBuilder sb = new StringBuilder();
        sb.append("PAPER ").append(today).append(" · ").append(strategy.name()).append('\n');
        sb.append(String.format(Locale.ROOT, "equity ₹%,.0f · cash ₹%,.0f · %d position(s)%n",
                equity, cash, positions.size()));
        for (Position p : positions) {
            double last = lastClose(candles.get(p.symbol()), today, p.entryPrice());
            sb.append(String.format(Locale.ROOT, "  %s x%d @ %s → %s (%+.1f%%)%n",
                    p.symbol(), p.quantity(), fmt(p.entryPrice()), fmt(last),
                    (last / p.entryPrice() - 1) * 100));
        }
        if (!fills.isEmpty()) {
            sb.append("fills today:\n");
            fills.forEach(f -> sb.append("  ").append(f).append('\n'));
        }
        if (!queued.isEmpty()) {
            sb.append("queued for tomorrow's open:\n");
            queued.forEach(q -> sb.append("  ").append(q).append('\n'));
        }
        if (fills.isEmpty() && queued.isEmpty()) sb.append("no trades today\n");
        if (!warnings.isEmpty()) {
            sb.append("⚠ warnings:\n");
            warnings.forEach(w -> sb.append("  ").append(w).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
