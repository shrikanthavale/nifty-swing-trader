package com.shrikane.swingtrader.executor;

import com.shrikane.swingtrader.backtest.CostModel;
import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.EtfUniverse;
import com.shrikane.swingtrader.journal.Journal;
import com.shrikane.swingtrader.journal.Journal.PendingOrder;
import com.shrikane.swingtrader.risk.Position;
import com.shrikane.swingtrader.risk.RiskManager;
import com.shrikane.swingtrader.signal.Signal;
import com.shrikane.swingtrader.signal.Strategy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The forward campaign's evening cycle (forward-campaign.md §3, §5, §10):
 * several SLEEVES — each an independent virtual account with its own
 * strategy, Portfolio and journal namespace — run side by side through the
 * same PaperTrader steps, fill model and RiskManager.
 *
 * FUNDED sleeves (IMR, ROT, VRS) together form the live ₹50,000 account:
 * they size positions against their own sleeve capital, but the kill switch
 * (6% off peak) and the weekly-loss pause (3%) judge the TOTAL account —
 * funded sleeves' equity plus the unallocated cash buffer — recorded under
 * the journal namespace {@value #TOTAL}. The kill switch halts entries in
 * every funded sleeve; exits are always processed; only a manual
 * `live reset-peak` re-enables. UNFUNDED sleeves (the breakout shadow) are
 * paper-only virtual accounts judged on their own equity.
 *
 * Sleeves may hold the same symbol: the ledger is keyed (sleeve, symbol);
 * the broker simply holds the merged quantity.
 *
 * One cycle per trading day: a second run on the same date re-evaluates
 * nothing and just reports the funded sleeves' pending orders (so `live`
 * can re-submit them — idempotent tags make that safe).
 */
public final class SleeveCycle {

    /** Journal namespace for the live account's total equity and its rails. */
    public static final String TOTAL = "TOTAL";
    static final String META_LAST_CYCLE = "last_cycle_date";

    /** One sleeve: a strategy with its own ledger and capital. */
    public record Sleeve(String id, PaperTrader trader, double capital, boolean funded) {

        /**
         * Funded sleeves fill entries at the full ordered quantity (the AMO
         * market order buys it all at the broker; the ledger must match).
         */
        public static Sleeve of(String id, Strategy strategy, RiskManager risk, CostModel costs,
                                Journal journal, double capital,
                                Function<LocalDate, Set<String>> membership, boolean funded) {
            return new Sleeve(id, new PaperTrader(strategy, risk, costs, journal, capital,
                    membership, !funded), capital, funded);
        }

        String strategyName() {
            return trader.strategy().name();
        }

        Journal journal() {
            return trader.journal();
        }
    }

    /** An order waiting in a sleeve's ledger for tomorrow's open. */
    public record QueuedOrder(String sleeve, long ledgerOrderId, LocalDate createdDate,
                              String symbol, Signal.Action action, int quantity, double refPrice) {}

    public record SleeveStatus(String id, String strategyName, boolean funded, double capital,
                               double equity, double cash, double investedAtCost,
                               List<Position> positions, List<String> fills, List<String> queued) {}

    public record Result(LocalDate date, boolean aborted, boolean alreadyRan,
                         double totalEquity, double totalPeak, double weeklyPnl,
                         boolean killSwitch, boolean entriesPaused,
                         List<SleeveStatus> sleeves, List<QueuedOrder> fundedOrders,
                         List<String> warnings, String text) {

        /** Kill switch or weekly pause on the live account: no new live entries. */
        public boolean entriesBlocked() {
            return killSwitch || entriesPaused;
        }
    }

    private final List<Sleeve> sleeves;
    private final Journal total;
    private final double totalCapital;

    public SleeveCycle(List<Sleeve> sleeves, Journal total, double totalCapital) {
        this.sleeves = List.copyOf(sleeves);
        this.total = total;
        this.totalCapital = totalCapital;
    }

    public List<Sleeve> sleeves() {
        return sleeves;
    }

    public Result run(Map<String, List<Candle>> candles, LocalDate today) {
        List<String> warnings = new ArrayList<>();
        sleeves.forEach(s -> s.trader().bootstrapCash());

        if (total.meta(META_LAST_CYCLE).map(today.toString()::equals).orElse(false)) {
            return alreadyRan(candles, today);
        }

        // ---- 1. stale-data guard: all data, and every ETF that has data at all ----
        Optional<String> stale = PaperTrader.staleDataProblem(candles, today);
        if (stale.isEmpty()) stale = staleEtf(candles, today, warnings);
        if (stale.isPresent()) {
            String text = "CYCLE " + today + ": ABORTED — " + stale.get();
            return new Result(today, true, false, 0, 0, 0, false, false,
                    List.of(), List.of(), List.of(text), text);
        }
        Map<String, Candle> todayBySymbol = PaperTrader.todaysCandles(candles, today);

        // ---- 2. fills at today's open, 3. mark every sleeve ----
        List<List<String>> fills = new ArrayList<>();
        List<Double> equities = new ArrayList<>();
        for (Sleeve sleeve : sleeves) {
            List<String> sleeveFills = new ArrayList<>();
            List<String> sleeveWarnings = new ArrayList<>();
            sleeve.trader().fillPending(todayBySymbol, today, sleeveFills, sleeveWarnings);
            sleeveWarnings.forEach(w -> warnings.add(sleeve.id() + ": " + w));
            fills.add(sleeveFills);
            equities.add(sleeve.trader().markToMarket(candles, today));
        }

        // ---- live-account rails on the TOTAL ----
        double fundedEquity = 0, fundedCash = 0, fundedCapital = 0;
        int fundedPositions = 0;
        for (int i = 0; i < sleeves.size(); i++) {
            Sleeve s = sleeves.get(i);
            if (!s.funded()) continue;
            fundedEquity += equities.get(i);
            fundedCash += s.journal().cash();
            fundedCapital += s.capital();
            fundedPositions += s.journal().openPositions().size();
        }
        double buffer = totalCapital - fundedCapital;          // unallocated cash (₹2,000)
        double totalEquity = fundedEquity + buffer;
        total.saveEquity(today, totalEquity, fundedCash + buffer, fundedPositions);
        double peak = peakSinceReset(total, totalEquity);
        double weeklyPnl = totalEquity - PaperTrader.weekStartEquity(total, today).orElse(totalEquity);
        boolean killSwitch = totalEquity <= peak * (1 - PaperTrader.KILL_SWITCH_DRAWDOWN);
        boolean paused = weeklyPnl <= -PaperTrader.WEEKLY_PAUSE_FRACTION * totalEquity;

        // ---- 4. evaluate + approve + queue, per sleeve ----
        List<List<String>> queued = new ArrayList<>();
        for (int i = 0; i < sleeves.size(); i++) {
            Sleeve s = sleeves.get(i);
            double equity = equities.get(i);
            List<String> sleeveQueued = new ArrayList<>();
            if (s.funded()) {
                // size against the sleeve (never above its capital), rails on the account
                s.trader().evaluateAndQueue(candles, today, Math.min(equity, s.capital()),
                        totalEquity, peak, weeklyPnl, sleeveQueued);
            } else {
                double ownPeak = peakSinceReset(s.journal(), equity);
                double ownWeekly = equity - PaperTrader.weekStartEquity(s.journal(), today).orElse(equity);
                s.trader().evaluateAndQueue(candles, today, equity, equity, ownPeak, ownWeekly, sleeveQueued);
                if (equity <= ownPeak * (1 - PaperTrader.KILL_SWITCH_DRAWDOWN)) {
                    warnings.add(String.format(Locale.ROOT, "%s (paper): kill switch — ₹%,.0f is %.1f%% "
                                    + "below its peak; `live reset-peak %s` after review.",
                            s.id(), equity, (1 - equity / ownPeak) * 100, s.id()));
                }
            }
            queued.add(sleeveQueued);
        }
        total.setMeta(META_LAST_CYCLE, today.toString());

        if (killSwitch) {
            warnings.add(String.format(Locale.ROOT,
                    "KILL SWITCH (live account): ₹%,.0f is %.1f%% below peak ₹%,.0f — no new "
                            + "entries in any funded sleeve; exits still run. Review, then "
                            + "`live reset-peak`.",
                    totalEquity, (1 - totalEquity / peak) * 100, peak));
        } else if (paused) {
            warnings.add(String.format(Locale.ROOT,
                    "Weekly loss pause (live account): ₹%,.0f down this week — no new entries "
                            + "until next week.", -weeklyPnl));
        }

        List<SleeveStatus> statuses = new ArrayList<>();
        for (int i = 0; i < sleeves.size(); i++) {
            statuses.add(status(sleeves.get(i), equities.get(i), fills.get(i), queued.get(i)));
        }
        String text = summary(today, false, totalEquity, peak, weeklyPnl, statuses, warnings, candles);
        return new Result(today, false, false, totalEquity, peak, weeklyPnl, killSwitch, paused,
                statuses, fundedOrders(), warnings, text);
    }

    /** Positions at entry cost per funded sleeve — the executor's exposure base. */
    public Map<String, Double> fundedInvestedAtCost() {
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        for (Sleeve s : sleeves) {
            if (s.funded()) out.put(s.id(), investedAtCost(s.journal().openPositions()));
        }
        return out;
    }

    public List<QueuedOrder> fundedOrders() {
        List<QueuedOrder> out = new ArrayList<>();
        for (Sleeve s : sleeves) {
            if (!s.funded()) continue;
            for (PendingOrder o : s.journal().pendingOrders()) {
                out.add(new QueuedOrder(s.id(), o.id(), o.createdDate(), o.symbol(), o.action(),
                        o.quantity(), o.refPrice()));
            }
        }
        return out;
    }

    /** Current kill-switch / pause state of the live account, from the journal. */
    public boolean liveEntriesBlocked(LocalDate today) {
        Optional<Double> last = total.lastEquityBefore(today.plusDays(1));
        if (last.isEmpty()) return false;
        double equity = last.get();
        double peak = peakSinceReset(total, equity);
        double weekly = equity - PaperTrader.weekStartEquity(total, today).orElse(equity);
        return equity <= peak * (1 - PaperTrader.KILL_SWITCH_DRAWDOWN)
                || weekly <= -PaperTrader.WEEKLY_PAUSE_FRACTION * equity;
    }

    // ---- helpers ----

    private Result alreadyRan(Map<String, List<Candle>> candles, LocalDate today) {
        List<SleeveStatus> statuses = new ArrayList<>();
        for (Sleeve s : sleeves) {
            double equity = s.journal().lastEquityBefore(today.plusDays(1)).orElse(s.capital());
            statuses.add(status(s, equity, List.of(), List.of()));
        }
        double totalEquity = total.lastEquityBefore(today.plusDays(1)).orElse(totalCapital);
        double peak = peakSinceReset(total, totalEquity);
        double weekly = totalEquity - PaperTrader.weekStartEquity(total, today).orElse(totalEquity);
        boolean blocked = liveEntriesBlocked(today);
        List<String> warnings = List.of("cycle already ran for " + today
                + " — nothing re-evaluated; pending orders listed for (re)submission.");
        String text = summary(today, true, totalEquity, peak, weekly, statuses, warnings, candles);
        boolean killSwitch = totalEquity <= peak * (1 - PaperTrader.KILL_SWITCH_DRAWDOWN);
        return new Result(today, false, true, totalEquity, peak, weekly, killSwitch,
                blocked && !killSwitch, statuses, fundedOrders(), warnings, text);
    }

    /** An ETF that has data but not today's bar: the ETF sleeves would trade on stale prices. */
    private static Optional<String> staleEtf(Map<String, List<Candle>> candles, LocalDate today,
                                             List<String> warnings) {
        for (String etf : EtfUniverse.SYMBOLS) {
            List<Candle> list = candles.get(etf);
            if (list == null || list.isEmpty()) {
                warnings.add("ETF " + etf + " has no data at all — not ranked / not traded.");
                continue;
            }
            LocalDate last = list.get(list.size() - 1).date();
            if (!last.equals(today)) {
                return Optional.of("stale ETF data: " + etf + " newest candle " + last
                        + ", expected " + today + ". DO NOT TRADE. Run `download` first.");
            }
        }
        return Optional.empty();
    }

    private static double peakSinceReset(Journal journal, double equity) {
        LocalDate since = journal.meta(PaperTrader.META_PEAK_RESET)
                .map(LocalDate::parse).orElse(LocalDate.of(1970, 1, 1));
        return Math.max(equity, journal.equityPeakSince(since).orElse(equity));
    }

    private static double investedAtCost(List<Position> positions) {
        return positions.stream().mapToDouble(p -> p.quantity() * p.entryPrice()).sum();
    }

    private static SleeveStatus status(Sleeve s, double equity, List<String> fills, List<String> queued) {
        List<Position> positions = s.journal().openPositions();
        return new SleeveStatus(s.id(), s.strategyName(), s.funded(), s.capital(), equity,
                s.journal().cash(), investedAtCost(positions), positions, fills, queued);
    }

    private String summary(LocalDate today, boolean alreadyRan, double totalEquity, double peak,
                           double weeklyPnl, List<SleeveStatus> statuses, List<String> warnings,
                           Map<String, List<Candle>> candles) {
        StringBuilder sb = new StringBuilder();
        sb.append("CYCLE ").append(today).append(alreadyRan ? " (already ran)" : "").append('\n');
        sb.append(String.format(Locale.ROOT,
                "live account ₹%,.0f (%+.1f%% vs ₹%,.0f) · peak ₹%,.0f · week %+,.0f%n",
                totalEquity, (totalEquity / totalCapital - 1) * 100, totalCapital, peak, weeklyPnl));
        for (SleeveStatus s : statuses) {
            sb.append(String.format(Locale.ROOT, "%s %s: ₹%,.0f (%+.1f%%) · cash ₹%,.0f%n",
                    s.funded() ? "●" : "○ paper", s.strategyName(), s.equity(),
                    (s.equity() / s.capital() - 1) * 100, s.cash()));
            StringBuilder positions = new StringBuilder();
            PaperTrader.appendPositions(positions, s.positions(), candles, today);
            sb.append(positions);
            s.fills().forEach(f -> sb.append("  filled: ").append(f).append('\n'));
            s.queued().forEach(q -> sb.append("  queued: ").append(q).append('\n'));
        }
        if (!warnings.isEmpty()) {
            sb.append("⚠ warnings:\n");
            warnings.forEach(w -> sb.append("  ").append(w).append('\n'));
        }
        return sb.toString().stripTrailing();
    }
}
