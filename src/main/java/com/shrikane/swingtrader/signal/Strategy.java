package com.shrikane.swingtrader.signal;

import com.shrikane.swingtrader.data.MarketSnapshot;
import com.shrikane.swingtrader.risk.Portfolio;

import java.util.List;

/**
 * A trading strategy is a pure function over (market history, current portfolio).
 * The SAME implementation runs in backtests and live — this is non-negotiable
 * (blueprint §4, "golden rule"). No I/O, no clocks, no randomness in here.
 */
public interface Strategy {
    String name();
    List<Signal> evaluate(MarketSnapshot snapshot, Portfolio portfolio);
}
