package com.shrikane.swingtrader.backtest;

import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.MarketSnapshot;
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
import java.util.TreeSet;

/**
 * Replays history day by day through the SAME Strategy and RiskManager used
 * live (blueprint §4 "golden rule", §6).
 *
 * The daily loop, for each trading day D in [start, end]:
 *   1. fill orders queued on the PREVIOUS trading day at D's OPEN (exits
 *      first — they free cash and slots), through the CostModel: slippage in
 *      the fill price, Zerodha charges in cash. Never D's close, never the
 *      signal day's close.
 *   2. mark equity at D's close; update the equity peak and weekly PnL.
 *   3. snapshot = MarketSnapshot up to D (structurally no future bars),
 *      signals = strategy.evaluate(snapshot, portfolio),
 *      orders  = riskManager.approve(...) → queued for D+1's open.
 *
 * A symbol that doesn't trade on D (halt, missing data) simply can't fill:
 * entry orders are dropped (the strategy will re-signal if conditions hold),
 * exit orders stay queued for the next day the symbol trades.
 *
 * Trading days = union of all candle dates in range: the calendar comes from
 * the data itself, so NSE holidays need no special handling here.
 *
 * KILL-SWITCH RESET (decision, July 2026): live, the 6% kill switch halts
 * trading until Shrikant manually reviews and re-enables. A backtest has no
 * human, so the review is simulated as a COOLING-OFF RESET: once equity is
 * ≥6% below its peak, entries stay blocked for {@link #COOLING_OFF_DAYS}
 * trading days, then the peak reference resets to current equity and trading
 * resumes. RiskManager code is untouched — the backtester only controls the
 * equityPeak parameter it passes in, exactly the lever the manual review
 * exercises live. Kill-switch firings are counted in the Result.
 */
public final class Backtester {

    /** Simulated "manual review" length after a kill-switch firing. */
    public static final int COOLING_OFF_DAYS = 10;

    private static final double KILL_SWITCH_DRAWDOWN = 0.06; // mirrors RiskManager

    /** Everything a report needs; stats are computed by {@link BacktestStats}. */
    public record Result(
            String strategyName,
            LocalDate start,
            LocalDate end,
            double startingCapital,
            List<EquityPoint> equityCurve,
            List<Trade> trades,
            List<Position> openAtEnd,
            double finalEquity,
            List<LocalDate> killSwitchFirings
    ) {}

    public record EquityPoint(LocalDate date, double equity) {}

    private final Strategy strategy;
    private final RiskManager riskManager;
    private final CostModel costModel;
    private final double startingCapital;
    /** Dated membership gate for entries; null = every symbol always eligible. */
    private final java.util.function.Function<LocalDate, java.util.Set<String>> membership;

    public Backtester(Strategy strategy, RiskManager riskManager,
                      CostModel costModel, double startingCapital) {
        this(strategy, riskManager, costModel, startingCapital, null);
    }

    /** With a dated-membership gate (survivorship-bias fix): entries are only
     *  allowed in symbols that were index members ON THE SIGNAL DATE. */
    public Backtester(Strategy strategy, RiskManager riskManager,
                      CostModel costModel, double startingCapital,
                      java.util.function.Function<LocalDate, java.util.Set<String>> membership) {
        this.strategy = strategy;
        this.riskManager = riskManager;
        this.costModel = costModel;
        this.startingCapital = startingCapital;
        this.membership = membership;
    }

    public Result run(Map<String, List<Candle>> candlesBySymbol,
                      LocalDate start, LocalDate end) {
        // per-symbol date index for O(1) "did SYMBOL trade on D, at what open?"
        Map<String, Map<LocalDate, Candle>> byDate = new HashMap<>();
        TreeSet<LocalDate> tradingDays = new TreeSet<>();
        for (Map.Entry<String, List<Candle>> e : candlesBySymbol.entrySet()) {
            Map<LocalDate, Candle> index = new HashMap<>();
            for (Candle c : e.getValue()) {
                index.put(c.date(), c);
                if (!c.date().isBefore(start) && !c.date().isAfter(end)) {
                    tradingDays.add(c.date());
                }
            }
            byDate.put(e.getKey(), index);
        }

        Portfolio portfolio = new Portfolio(startingCapital);
        Map<String, Double> entryChargesBySymbol = new HashMap<>();
        Map<String, Double> lastKnownClose = new HashMap<>();
        List<SizedOrder> pending = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        List<EquityPoint> equityCurve = new ArrayList<>();

        double equity = startingCapital;
        double equityPeak = startingCapital;
        double weekStartEquity = startingCapital;
        int currentWeek = -1;
        int coolingOffRemaining = 0;              // >0 = kill switch review period
        List<LocalDate> killSwitchFirings = new ArrayList<>();

        WeekFields weekFields = WeekFields.ISO;

        for (LocalDate day : tradingDays) {
            // ---- 1. fill yesterday's orders at today's open ----
            List<SizedOrder> stillPending = new ArrayList<>();

            for (SizedOrder order : pending) {           // exits first
                if (order.signal().action() != Signal.Action.EXIT) continue;
                Candle today = byDate.get(order.signal().symbol()).get(day);
                if (today == null) {                     // halted → try again tomorrow
                    stillPending.add(order);
                    continue;
                }
                fillExit(order, today, portfolio, entryChargesBySymbol, trades, day);
            }
            for (SizedOrder order : pending) {           // then entries
                if (order.signal().action() != Signal.Action.ENTER) continue;
                Candle today = byDate.get(order.signal().symbol()).get(day);
                if (today == null) continue;             // dropped; strategy may re-signal
                fillEntry(order, today, portfolio, entryChargesBySymbol, day);
            }
            pending = stillPending;

            // ---- 2. mark to market at today's close ----
            for (Position p : portfolio.openPositions()) {
                Candle today = byDate.get(p.symbol()).get(day);
                if (today != null) lastKnownClose.put(p.symbol(), today.close());
            }
            equity = portfolio.cash();
            for (Position p : portfolio.openPositions()) {
                equity += p.quantity() * lastKnownClose.getOrDefault(p.symbol(), p.entryPrice());
            }
            equityCurve.add(new EquityPoint(day, equity));
            equityPeak = Math.max(equityPeak, equity);

            // kill-switch bookkeeping: fire → cooling-off countdown → peak reset
            if (coolingOffRemaining > 0) {
                coolingOffRemaining--;
                if (coolingOffRemaining == 0) {
                    equityPeak = equity;          // the simulated manual re-enable
                }
            } else if (equity <= equityPeak * (1 - KILL_SWITCH_DRAWDOWN)) {
                killSwitchFirings.add(day);
                coolingOffRemaining = COOLING_OFF_DAYS;
            }

            int week = day.get(weekFields.weekOfWeekBasedYear())
                    + 100 * day.get(weekFields.weekBasedYear());
            if (week != currentWeek) {
                currentWeek = week;
                weekStartEquity = equity;
            }
            double weeklyPnl = equity - weekStartEquity;

            // ---- 3. evaluate strategy on data up to today; queue for tomorrow ----
            MarketSnapshot snapshot = MarketSnapshot.ofPresorted(candlesBySymbol, day,
                    membership == null ? null : membership.apply(day));
            List<Signal> signals = strategy.evaluate(snapshot, portfolio);
            List<SizedOrder> approved =
                    riskManager.approve(signals, portfolio, equity, equityPeak, weeklyPnl);
            // de-dup: an exit already queued (halted symbol) must not queue twice
            for (SizedOrder order : approved) {
                boolean duplicate = pending.stream().anyMatch(q ->
                        q.signal().symbol().equals(order.signal().symbol())
                                && q.signal().action() == order.signal().action());
                if (!duplicate) pending.add(order);
            }
        }

        return new Result(strategy.name(), start, end, startingCapital,
                equityCurve, trades, List.copyOf(portfolio.openPositions()), equity,
                killSwitchFirings);
    }

    private void fillExit(SizedOrder order, Candle today, Portfolio portfolio,
                          Map<String, Double> entryChargesBySymbol,
                          List<Trade> trades, LocalDate day) {
        String symbol = order.signal().symbol();
        Position position = portfolio.openPositions().stream()
                .filter(p -> p.symbol().equals(symbol))
                .findFirst().orElse(null);
        if (position == null) return;                    // already closed

        double fill = costModel.sellFillPrice(today.open());
        double value = fill * position.quantity();
        double charges = costModel.sellCharges(value);
        portfolio.applySell(symbol, value - charges);
        trades.add(new Trade(symbol, position.quantity(),
                position.entryDate(), position.entryPrice(), day, fill,
                entryChargesBySymbol.getOrDefault(symbol, 0.0), charges,
                order.signal().reason()));
        entryChargesBySymbol.remove(symbol);
    }

    private void fillEntry(SizedOrder order, Candle today, Portfolio portfolio,
                           Map<String, Double> entryChargesBySymbol, LocalDate day) {
        String symbol = order.signal().symbol();
        if (portfolio.holds(symbol)) return;

        double fill = costModel.buyFillPrice(today.open());
        int qty = order.quantity();
        // sized on yesterday's close — a gap up may make it unaffordable; shrink
        while (qty > 0 && fill * qty + costModel.buyCharges(fill * qty) > portfolio.cash()) {
            qty = Math.min(qty - 1, (int) Math.floor(portfolio.cash() / fill));
        }
        if (qty <= 0) return;

        double value = fill * qty;
        double charges = costModel.buyCharges(value);
        portfolio.applyBuy(new Position(symbol, qty, fill, day, order.signal().stopPrice()),
                value + charges);
        entryChargesBySymbol.put(symbol, charges);
    }

    /** Console one-liner used by the CLI after a run. */
    public static String summaryLine(Result result) {
        return String.format(Locale.ROOT,
                "%s %s→%s: %d trades, final equity ₹%.0f (%+.1f%%)",
                result.strategyName(), result.start(), result.end(),
                result.trades().size(), result.finalEquity(),
                (result.finalEquity() / result.startingCapital() - 1) * 100);
    }
}
