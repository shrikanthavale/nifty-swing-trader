package in.shrikant.swingtrader.backtest;

/**
 * Replays history day by day through the SAME Strategy and RiskManager used
 * live (blueprint §4 "golden rule", §6).
 *
 * TODO (Phase 1) — the daily loop:
 *   for each trading day D in [start, end]:
 *     1. snapshot = MarketSnapshot.of(allCandles, D)          // no future bars
 *     2. signals  = strategy.evaluate(snapshot, portfolio)
 *     3. orders   = riskManager.approve(signals, portfolio, equity, peak, weeklyPnl)
 *     4. fill orders at day D+1's OPEN via CostModel            // never D's close!
 *     5. record equity snapshot, trades, and rejected signals to the report
 *
 * Outputs a BacktestReport: equity curve, win rate, avg win/loss, expectancy,
 * max drawdown, total cost drag, trade count.
 *
 * Acceptance bar before anything goes live (blueprint §5-6): positive
 * expectancy after costs, >= 150 trades, profitable in both halves of the
 * test window, survivable max drawdown, parameter plateau (not spike).
 */
public class Backtester {
    // Intentionally a skeleton — implemented in Phase 1.
}
