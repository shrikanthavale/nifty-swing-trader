package in.shrikant.swingtrader.signal.strategies;

import in.shrikant.swingtrader.data.MarketSnapshot;
import in.shrikant.swingtrader.risk.Portfolio;
import in.shrikant.swingtrader.signal.Signal;
import in.shrikant.swingtrader.signal.Strategy;

import java.util.List;

/**
 * Strategy B (blueprint §5): 50-day closing-high breakout with volume
 * confirmation, trailing ATR stop.
 *
 * TODO (Phase 2): implement after the backtester runs PullbackStrategy
 * end-to-end. Rules:
 *   Entry: close == 50-day closing high AND volume > 1.5 * 20-day avg volume
 *          AND close > 200-day SMA.
 *   Exit:  trailing stop 2.5 * ATR(14) below highest close since entry,
 *          or 10 trading days elapsed.
 */
public class BreakoutStrategy implements Strategy {

    @Override
    public String name() {
        return "breakout-v1";
    }

    @Override
    public List<Signal> evaluate(MarketSnapshot snapshot, Portfolio portfolio) {
        throw new UnsupportedOperationException("Phase 2 — see class javadoc");
    }
}
