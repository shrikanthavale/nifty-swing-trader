package in.shrikant.swingtrader.data;

import java.time.LocalDate;
import java.util.List;

/**
 * The tradeable universe (NIFTY 100 constituents), *dated* to avoid
 * survivorship bias in backtests: membership on 2018-05-01 must be the 2018
 * list, not today's.
 *
 * TODO (Phase 1): load from a constituents table (symbol, from_date, to_date)
 * seeded from NSE index change announcements. For a first cut it is acceptable
 * to use today's list and note the bias (blueprint §6.3).
 */
public interface Universe {
    List<String> membersOn(LocalDate date);
}
