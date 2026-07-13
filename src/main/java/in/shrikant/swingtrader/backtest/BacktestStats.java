package in.shrikant.swingtrader.backtest;

import in.shrikant.swingtrader.backtest.Backtester.EquityPoint;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Honest statistics over a backtest result (blueprint §6). Pure functions —
 * unit-tested against hand-computed values.
 *
 * The acceptance-bar fields answer blueprint §5-6 directly: positive
 * expectancy AFTER costs, >= 150 trades, profitable in BOTH halves of the
 * window. Max drawdown and the parameter plateau are judgment calls the
 * report surfaces but cannot decide.
 */
public record BacktestStats(
        int tradeCount,
        double winRate,             // 0..1
        double avgWin,              // ₹, net, winners only
        double avgLoss,             // ₹, net, losers only (negative)
        double expectancy,          // ₹ net per trade
        double expectancyPct,       // mean net return per trade (fraction)
        double profitFactor,        // gross net wins / |gross net losses|
        double totalNetPnl,         // ₹ across completed trades
        double totalCharges,        // ₹ Zerodha charges paid (cost drag)
        double totalReturn,         // fraction, final vs starting equity
        double cagr,                // fraction / year
        double maxDrawdown,         // fraction, worst peak-to-trough
        double firstHalfPnl,        // ₹ net, trades exiting in first half
        double secondHalfPnl,       // ₹ net, trades exiting in second half
        boolean meetsTradeCountBar, // >= 150
        boolean meetsExpectancyBar, // expectancy > 0 after costs
        boolean meetsBothHalvesBar  // both halves net-positive
) {

    public static final int MIN_TRADES = 150;

    public static BacktestStats from(Backtester.Result result) {
        List<Trade> trades = result.trades();
        int n = trades.size();

        double wins = 0, losses = 0, winSum = 0, lossSum = 0;
        double netSum = 0, pctSum = 0, charges = 0;
        for (Trade t : trades) {
            double pnl = t.netPnl();
            netSum += pnl;
            pctSum += t.returnFraction();
            charges += t.entryCharges() + t.exitCharges();
            if (t.isWin()) { wins++; winSum += pnl; } else { losses++; lossSum += pnl; }
        }

        LocalDate midpoint = result.start().plusDays(
                ChronoUnit.DAYS.between(result.start(), result.end()) / 2);
        double firstHalf = trades.stream()
                .filter(t -> !t.exitDate().isAfter(midpoint))
                .mapToDouble(Trade::netPnl).sum();
        double secondHalf = netSum - firstHalf;

        double totalReturn = result.startingCapital() <= 0 ? 0
                : result.finalEquity() / result.startingCapital() - 1;
        long days = Math.max(1, ChronoUnit.DAYS.between(result.start(), result.end()));
        double years = days / 365.25;
        double cagr = years <= 0 || result.finalEquity() <= 0 ? 0
                : Math.pow(result.finalEquity() / result.startingCapital(), 1 / years) - 1;

        return new BacktestStats(
                n,
                n == 0 ? 0 : wins / n,
                wins == 0 ? 0 : winSum / wins,
                losses == 0 ? 0 : lossSum / losses,
                n == 0 ? 0 : netSum / n,
                n == 0 ? 0 : pctSum / n,
                lossSum == 0 ? (winSum > 0 ? Double.POSITIVE_INFINITY : 0)
                             : winSum / -lossSum,
                netSum,
                charges,
                totalReturn,
                cagr,
                maxDrawdown(result.equityCurve()),
                firstHalf,
                secondHalf,
                n >= MIN_TRADES,
                n > 0 && netSum / n > 0,
                n > 0 && firstHalf > 0 && secondHalf > 0);
    }

    /** Worst peak-to-trough fall as a fraction of the peak. */
    static double maxDrawdown(List<EquityPoint> curve) {
        double peak = Double.NEGATIVE_INFINITY;
        double worst = 0;
        for (EquityPoint p : curve) {
            peak = Math.max(peak, p.equity());
            if (peak > 0) worst = Math.max(worst, (peak - p.equity()) / peak);
        }
        return worst;
    }
}
