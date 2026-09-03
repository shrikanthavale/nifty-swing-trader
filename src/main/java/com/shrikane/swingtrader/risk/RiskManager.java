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

    // ₹1 lakh configuration (blueprint §7)
    private final double riskPerTradeFraction = 0.01;   // 1% of equity
    private final double maxPositionFraction = 0.25;    // 25% of equity
    private final int maxConcurrentPositions = 4;
    private final double killSwitchDrawdown = 0.06;     // 6% off equity peak
    private final double weeklyLossPauseFraction = 0.03; // 3% weekly loss → pause entries

    /** A signal the risk manager has approved and sized. */
    public record SizedOrder(Signal signal, int quantity) {}

    /**
     * @param equity      current total account equity (cash + positions at last close)
     * @param equityPeak  highest equity seen so far (for the kill switch)
     * @param weeklyPnl   realized+unrealized PnL this week (for the pause rule)
     */
    public List<SizedOrder> approve(List<Signal> signals, Portfolio portfolio,
                                    double equity, double equityPeak, double weeklyPnl) {
        List<SizedOrder> approved = new ArrayList<>();

        boolean killSwitch = equity <= equityPeak * (1 - killSwitchDrawdown);
        boolean entriesPaused = weeklyPnl <= -weeklyLossPauseFraction * equity;

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
            int qty = size(s, equity, portfolio.cash());
            if (qty > 0) approved.add(new SizedOrder(s, qty));
        }
        return approved;
    }

    /** Position size = min(risk-based size, max-position cap, available cash). */
    int size(Signal s, double equity, double cash) {
        double stopDistance = s.referencePrice() - s.stopPrice();
        if (stopDistance <= 0) return 0;
        double riskBudget = equity * riskPerTradeFraction;
        int byRisk = (int) Math.floor(riskBudget / stopDistance);
        int byCap = (int) Math.floor((equity * maxPositionFraction) / s.referencePrice());
        int byCash = (int) Math.floor(cash / s.referencePrice());
        return Math.max(0, Math.min(byRisk, Math.min(byCap, byCash)));
    }
}
