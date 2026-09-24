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
 *   2. Fill orders queued on an EARLIER evening at TODAY's open through the
 *      CostModel (exits first; entries shrink on gap-ups; entry with no
 *      candle today is cancelled, exit stays pending).
 *   3. Mark equity at today's close; persist the daily snapshot.
 *   4. Evaluate the strategy on data up to today, risk-approve, journal
 *      every signal (approved or not), queue approved orders for tomorrow.
 *
 * Kill switch: like the backtester's simulated review, but here the reset IS
 * manual — `paper reset-peak` records a marker date and the peak is computed
 * from daily equity since that marker. While equity is ≥6% below the peak,
 * RiskManager blocks entries and the summary shouts about it.
 *
 * Steps 2–4 are also exposed individually so {@link SleeveCycle} can run
 * several sleeves side by side under one set of account-wide rails.
 */
public final class PaperTrader {

    /** Mirrors RiskManager's private thresholds for status reporting. */
    static final double KILL_SWITCH_DRAWDOWN = 0.06;
    static final double WEEKLY_PAUSE_FRACTION = 0.03;

    public static final String META_PEAK_RESET = "peak_reset_date";

    private final Strategy strategy;
    private final RiskManager riskManager;
    private final CostModel costModel;
    private final Journal journal;
    private final double startingCapital;
    /** Dated membership for entry gating; null = all symbols eligible. */
    private final java.util.function.Function<LocalDate, java.util.Set<String>> membership;
    /**
     * false = an entry always fills its full ordered quantity, even if a gap
     * up overdraws the ledger's cash slightly. Used by live-account sleeves:
     * an AMO market order buys the full quantity at the broker, and the
     * ledger must hold what the broker holds (the account's cash buffer
     * absorbs the overdraft). true (default) = shrink like the Backtester.
     */
    private final boolean shrinkEntriesToCash;

    public PaperTrader(Strategy strategy, RiskManager riskManager, CostModel costModel,
                       Journal journal, double startingCapital) {
        this(strategy, riskManager, costModel, journal, startingCapital, null);
    }

    public PaperTrader(Strategy strategy, RiskManager riskManager, CostModel costModel,
                       Journal journal, double startingCapital,
                       java.util.function.Function<LocalDate, java.util.Set<String>> membership) {
        this(strategy, riskManager, costModel, journal, startingCapital, membership, true);
    }

    PaperTrader(Strategy strategy, RiskManager riskManager, CostModel costModel,
                Journal journal, double startingCapital,
                java.util.function.Function<LocalDate, java.util.Set<String>> membership,
                boolean shrinkEntriesToCash) {
        this.strategy = strategy;
        this.riskManager = riskManager;
        this.costModel = costModel;
        this.journal = journal;
        this.startingCapital = startingCapital;
        this.membership = membership;
        this.shrinkEntriesToCash = shrinkEntriesToCash;
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
        bootstrapCash();

        // ---- 1. stale-data guard ----
        Optional<String> stale = staleDataProblem(candles, today);
        if (stale.isPresent()) {
            String text = "PAPER " + today + ": ABORTED — " + stale.get();
            return new DailyResult(today, true, fills, queued, List.of(text),
                    0, journal.cash(), journal.openPositions().size(), false, false, text);
        }
        Map<String, Candle> todayBySymbol = todaysCandles(candles, today);

        // ---- 2. fill earlier evenings' pending orders at today's open ----
        fillPending(todayBySymbol, today, fills, warnings);

        // ---- 3. mark equity at today's close ----
        double equity = markToMarket(candles, today);
        double cash = journal.cash();

        LocalDate peakSince = journal.meta(META_PEAK_RESET)
                .map(LocalDate::parse).orElse(LocalDate.of(1970, 1, 1));
        double peak = Math.max(equity, journal.equityPeakSince(peakSince).orElse(equity));
        double weekStart = weekStartEquity(journal, today).orElse(equity);
        double weeklyPnl = equity - weekStart;
        boolean killSwitch = equity <= peak * (1 - KILL_SWITCH_DRAWDOWN);
        boolean paused = weeklyPnl <= -WEEKLY_PAUSE_FRACTION * equity;

        // ---- 4. evaluate, approve, journal, queue ----
        evaluateAndQueue(candles, today, equity, equity, peak, weeklyPnl, queued);

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

    // ---- the steps, reusable by SleeveCycle ----

    Strategy strategy() {
        return strategy;
    }

    Journal journal() {
        return journal;
    }

    void bootstrapCash() {
        if (journal.cash() < 0) journal.setCash(startingCapital);
    }

    /** Why today's data can't be traded on, or empty if it can. */
    static Optional<String> staleDataProblem(Map<String, List<Candle>> candles, LocalDate today) {
        LocalDate newest = null;
        for (List<Candle> list : candles.values()) {
            if (list.isEmpty()) continue;
            LocalDate last = list.get(list.size() - 1).date();
            if (newest == null || last.isAfter(newest)) newest = last;
        }
        if (newest != null && newest.equals(today)) return Optional.empty();
        return Optional.of("stale data (newest candle " + newest + ", expected " + today
                + "). DO NOT TRADE. Run `download` first; if today is an NSE holiday this is expected.");
    }

    static Map<String, Candle> todaysCandles(Map<String, List<Candle>> candles, LocalDate today) {
        Map<String, Candle> todayBySymbol = new HashMap<>();
        for (Map.Entry<String, List<Candle>> e : candles.entrySet()) {
            List<Candle> list = e.getValue();
            if (list.isEmpty()) continue;
            Candle last = list.get(list.size() - 1);
            if (last.date().equals(today)) todayBySymbol.put(e.getKey(), last);
        }
        return todayBySymbol;
    }

    /**
     * Fills orders created on an EARLIER evening at today's open (an order
     * queued tonight must never fill at this morning's open, e.g. on a rerun).
     */
    void fillPending(Map<String, Candle> todayBySymbol, LocalDate today,
                     List<String> fills, List<String> warnings) {
        for (Position p : journal.openPositions()) {
            if (!todayBySymbol.containsKey(p.symbol())) {
                warnings.add("held symbol " + p.symbol() + " has no candle for " + today
                        + " (halted?) — marked at last known close");
            }
        }
        List<PendingOrder> pending = journal.pendingOrders().stream()
                .filter(o -> o.createdDate().isBefore(today)).toList();
        for (PendingOrder order : pending) {          // exits first
            if (order.action() == Signal.Action.EXIT) fillExit(order, todayBySymbol, today, fills);
        }
        for (PendingOrder order : pending) {
            if (order.action() == Signal.Action.ENTER) fillEntry(order, todayBySymbol, today, fills, warnings);
        }
    }

    /** Equity at today's close (cash + positions); persisted as today's snapshot. */
    double markToMarket(Map<String, List<Candle>> candles, LocalDate today) {
        List<Position> positions = journal.openPositions();
        double cash = journal.cash();
        double equity = cash;
        for (Position p : positions) {
            equity += p.quantity() * lastClose(candles.get(p.symbol()), today, p.entryPrice());
        }
        journal.saveEquity(today, equity, cash, positions.size());
        return equity;
    }

    /**
     * Evaluates the strategy on data up to today, risk-approves (sizing on
     * {@code sizingEquity}, rails judged on the rail* arguments), journals
     * every signal and queues approved orders for the next open. An order
     * identical to one still pending (e.g. a halted exit) is not queued twice.
     */
    void evaluateAndQueue(Map<String, List<Candle>> candles, LocalDate today,
                          double sizingEquity, double railEquity, double railPeak,
                          double railWeeklyPnl, List<String> queued) {
        boolean killSwitch = railEquity <= railPeak * (1 - KILL_SWITCH_DRAWDOWN);
        boolean paused = railWeeklyPnl <= -WEEKLY_PAUSE_FRACTION * railEquity;

        MarketSnapshot snapshot = MarketSnapshot.ofPresorted(candles, today,
                membership == null ? null : membership.apply(today));
        Portfolio portfolio = new Portfolio(journal.cash());
        journal.openPositions().forEach(p -> portfolio.applyBuy(p, 0));

        List<Signal> signals = strategy.evaluate(snapshot, portfolio);
        List<SizedOrder> approved = riskManager.approve(signals, portfolio,
                sizingEquity, railEquity, railPeak, railWeeklyPnl);
        List<PendingOrder> alreadyPending = journal.pendingOrders();

        for (Signal signal : signals) {
            SizedOrder match = approved.stream()
                    .filter(o -> o.signal() == signal).findFirst().orElse(null);
            String note = match != null ? ""
                    : killSwitch ? "kill switch active"
                    : paused ? "weekly loss pause"
                    : "not selected (slots/rank/size)";
            journal.recordSignal(today, signal, match != null, note);
            if (match == null) continue;
            boolean duplicate = alreadyPending.stream().anyMatch(o ->
                    o.symbol().equals(signal.symbol()) && o.action() == signal.action());
            if (duplicate) continue;
            journal.createOrder(today, signal.symbol(), signal.action(),
                    match.quantity(), signal.referencePrice(), signal.stopPrice());
            queued.add(signal.action() + " " + signal.symbol() + " x" + match.quantity()
                    + " @ next open (ref " + fmt(signal.referencePrice()) + ")");
        }
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
        while (shrinkEntriesToCash && qty > 0 && fill * qty + costModel.buyCharges(fill * qty) > cash) {
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

    static Optional<Double> weekStartEquity(Journal journal, LocalDate today) {
        WeekFields wf = WeekFields.ISO;
        LocalDate monday = today.with(wf.dayOfWeek(), 1);
        return journal.lastEquityBefore(monday);
    }

    static double lastClose(List<Candle> candles, LocalDate upTo, double fallback) {
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
        appendPositions(sb, positions, candles, today);
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

    static void appendPositions(StringBuilder sb, List<Position> positions,
                                Map<String, List<Candle>> candles, LocalDate today) {
        for (Position p : positions) {
            double last = lastClose(candles.get(p.symbol()), today, p.entryPrice());
            sb.append(String.format(Locale.ROOT, "  %s x%d @ %s → %s (%+.1f%%)%n",
                    p.symbol(), p.quantity(), fmt(p.entryPrice()), fmt(last),
                    (last / p.entryPrice() - 1) * 100));
        }
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
