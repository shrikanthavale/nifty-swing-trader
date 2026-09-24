package com.shrikane.swingtrader.risk;

import com.shrikane.swingtrader.signal.Signal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns raw signals into sized, permitted orders. Owns every hard limit from
 * blueprint §7. Like Strategy, this is pure logic — the SAME class runs in
 * backtests and live.
 */
public class RiskManager {

    private final double riskPerTradeFraction;          // 0 = size not cut by stop risk
    private final double maxPositionFraction;
    private final int maxConcurrentPositions;
    private final double killSwitchDrawdown = 0.06;     // 6% off equity peak
    private final double weeklyLossPauseFraction = 0.03; // 3% weekly loss → pause entries

    /** ₹1 lakh multi-stock configuration (blueprint §7): 1% risk, 25% cap, max 4. */
    public RiskManager() {
        this(0.01, 0.25, 4);
    }

    private RiskManager(double riskPerTradeFraction, double maxPositionFraction,
                        int maxConcurrentPositions) {
        this.riskPerTradeFraction = riskPerTradeFraction;
        this.maxPositionFraction = maxPositionFraction;
        this.maxConcurrentPositions = maxConcurrentPositions;
    }

    /**
     * Forward-campaign sleeve profile (forward-campaign.md Amendment A1):
     * positions are sized to the FULL sleeve (max position 100% of the sizing
     * equity, still limited by cash). The 1%-per-trade rail is measured
     * against the total ₹50,000 account instead, where a 2.5×ATR stop-out on
     * a full sleeve (≈0.8–1.2% of total) was explicitly accepted — so the
     * stop distance does not cut the size here. Kill switch and weekly pause
     * are the same rails as the default profile. The no-arg profile used by
     * the stock strategies is unchanged.
     */
    public static RiskManager sleeveProfile() {
        return new RiskManager(0, 1.0, 4);
    }

    /** A signal the risk manager has approved and sized. */
    public record SizedOrder(Signal signal, int quantity) {}

    /**
     * @param equity      current total account equity (cash + positions at last close)
     * @param equityPeak  highest equity seen so far (for the kill switch)
     * @param weeklyPnl   realized+unrealized PnL this week (for the pause rule)
     */
    public List<SizedOrder> approve(List<Signal> signals, Portfolio portfolio,
                                    double equity, double equityPeak, double weeklyPnl) {
        return approve(signals, portfolio, equity, equity, equityPeak, weeklyPnl);
    }

    /**
     * Multi-sleeve form: sizes positions against {@code sizingEquity} (one
     * sleeve's capital) while the kill switch and weekly pause judge the
     * account the sleeve lives in ({@code railEquity}, {@code railPeak},
     * {@code railWeeklyPnl} — the total live account). With sizingEquity ==
     * railEquity this is exactly the single-account rule above.
     */
    public List<SizedOrder> approve(List<Signal> signals, Portfolio portfolio,
                                    double sizingEquity, double railEquity,
                                    double railPeak, double railWeeklyPnl) {
        List<SizedOrder> approved = new ArrayList<>();

        boolean killSwitch = railEquity <= railPeak * (1 - killSwitchDrawdown);
        boolean entriesPaused = railWeeklyPnl <= -weeklyLossPauseFraction * railEquity;

        // Exits always pass (and are the ONLY thing that passes under the kill switch).
        signals.stream()
                .filter(s -> s.action() == Signal.Action.EXIT)
                .forEach(s -> {
                    portfolio.openPositions().stream()
                            .filter(p -> p.symbol().equals(s.symbol()))
                            .findFirst()
                            .ifPresent(p -> approved.add(new SizedOrder(s, p.quantity())));
                });

        if (killSwitch) {
            // TODO (Phase 3): also emit EXITs for every remaining open position
            // and set a halt flag that requires manual reset (blueprint §7).
            return approved;
        }
        if (entriesPaused) return approved;

        int freeSlots = maxConcurrentPositions - portfolio.openPositions().size();
        List<Signal> entries = signals.stream()
                .filter(s -> s.action() == Signal.Action.ENTER)
                .filter(s -> !portfolio.holds(s.symbol()))
                .sorted(Comparator.comparingDouble(Signal::rank).reversed())
                .limit(Math.max(0, freeSlots))
                .toList();

        for (Signal s : entries) {
            int qty = size(s, sizingEquity, portfolio.cash());
            if (qty > 0) approved.add(new SizedOrder(s, qty));
        }
        return approved;
    }

    /** Position size = min(risk-based size, max-position cap, available cash). */
    int size(Signal s, double equity, double cash) {
        double stopDistance = s.referencePrice() - s.stopPrice();
        if (stopDistance <= 0) return 0;
        int byRisk = riskPerTradeFraction > 0
                ? (int) Math.floor(equity * riskPerTradeFraction / stopDistance)
                : Integer.MAX_VALUE;
        int byCap = (int) Math.floor((equity * maxPositionFraction) / s.referencePrice());
        int byCash = (int) Math.floor(cash / s.referencePrice());
        return Math.max(0, Math.min(byRisk, Math.min(byCap, byCash)));
    }
}
