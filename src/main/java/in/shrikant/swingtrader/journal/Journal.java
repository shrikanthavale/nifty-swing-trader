package in.shrikant.swingtrader.journal;

/**
 * Append-only record of everything the system thinks and does: every signal
 * (including rejected ones), order, fill, and a daily equity snapshot.
 * Doubles as the tax record (blueprint §4) and the strategy-decay monitor:
 * rolling 30-trade expectancy vs. backtest expectancy (§9).
 *
 * TODO (Phase 3): SQLite tables — signals, orders, fills, equity_daily —
 * plus a weekly report generator.
 */
public class Journal {
    // Intentionally a skeleton — implemented in Phase 3.
}
