package com.shrikane.swingtrader.signal.strategies;

import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.EtfUniverse;
import com.shrikane.swingtrader.data.MarketSnapshot;
import com.shrikane.swingtrader.risk.Portfolio;
import com.shrikane.swingtrader.risk.Position;
import com.shrikane.swingtrader.signal.Indicators;
import com.shrikane.swingtrader.signal.Signal;
import com.shrikane.swingtrader.signal.Strategy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ROT-v1 — sector rotation, "follow the leader, monthly"
 * (forward-campaign.md §4B): classic 3-month relative momentum, unmodified.
 *
 * Review day: the first trading day of each month = the first bar whose month
 * differs from the previous bar's month (the data's own calendar — no holiday
 * table). The decision uses closes up to and including that day (the
 * snapshot's asOf); orders fill at the next open.
 *
 * Rank:   the six frozen ETFs by 63-trading-day total return. A symbol with
 *         fewer than 64 candles is simply not ranked. The leader is held iff
 *         its return is > 0; otherwise the sleeve sits in cash for the month.
 * Exit:   at a review whose leader differs from the holding (or → cash), or
 *         at any close below the disaster stop entry − 2.5 × ATR(14).
 *
 * Rotation mechanics: the exit and the new entry cannot be sized on the same
 * evening — the sleeve's cash is still tied up until the exit fills. So a
 * rotation completes one day later: on the bar right after a review day, a
 * FLAT sleeve enters the leader chosen on the review day (ranked on the
 * review day's closes, not re-ranked). The same window re-tries an entry
 * that didn't fill. A disaster stop later in the month leaves the sleeve in
 * cash until the next review.
 *
 * FROZEN: a change is a new family (ROT-v2), never an edit here.
 */
public final class SectorRotationStrategy implements Strategy {

    static final List<String> UNIVERSE = EtfUniverse.SYMBOLS;
    private static final int LOOKBACK = 63;
    private static final int ATR_PERIOD = 14;
    private static final double STOP_ATR_MULTIPLE = 2.5;

    /** The review-day leader: symbol + its 63-day return. */
    record Leader(String symbol, double return63) {}

    @Override
    public String name() {
        return "ROT-v1";
    }

    @Override
    public List<Signal> evaluate(MarketSnapshot snapshot, Portfolio portfolio) {
        List<Signal> signals = new ArrayList<>();
        List<Candle> calendar = calendar(snapshot);
        if (calendar.isEmpty()) return signals;

        int n = calendar.size();
        boolean barToday = calendar.get(n - 1).date().equals(snapshot.asOf());
        boolean reviewToday = barToday && isFirstOfMonth(calendar, n - 1);
        boolean reviewYesterday = barToday && !reviewToday && isFirstOfMonth(calendar, n - 2);

        Leader leaderToday = reviewToday ? leader(snapshot, snapshot.asOf()) : null;

        // --- exits: disaster stop daily; leadership change at the review ---
        for (Position position : portfolio.openPositions()) {
            List<Candle> candles = snapshot.candles(position.symbol());
            if (candles.isEmpty()) continue;
            double close = candles.get(candles.size() - 1).close();
            String reason = null;
            if (DisasterStop.breached(candles, position, STOP_ATR_MULTIPLE, ATR_PERIOD)) {
                reason = "ROT disaster stop";
            } else if (reviewToday && (leaderToday == null
                    || !leaderToday.symbol().equals(position.symbol()))) {
                reason = leaderToday == null
                        ? "ROT review: no ETF with positive 63d return → cash"
                        : "ROT review: leader is now " + leaderToday.symbol();
            }
            if (reason != null) {
                signals.add(new Signal(position.symbol(), Signal.Action.EXIT, close, 0, 0, reason));
            }
        }

        // --- entries: only when flat, only on a review day or the day after ---
        if (!portfolio.openPositions().isEmpty()) return signals;
        Leader target = reviewToday ? leaderToday
                : reviewYesterday ? leader(snapshot, calendar.get(n - 2).date())
                : null;
        if (target == null) return signals;

        List<Candle> candles = snapshot.candles(target.symbol());
        if (candles.isEmpty() || !candles.get(candles.size() - 1).date().equals(snapshot.asOf())) {
            return signals;                                  // no bar today: can't price the entry
        }
        double close = candles.get(candles.size() - 1).close();
        double atr = Indicators.atr(candles, ATR_PERIOD);
        if (Double.isNaN(atr) || atr <= 0) return signals;
        signals.add(new Signal(target.symbol(), Signal.Action.ENTER, close,
                close - STOP_ATR_MULTIPLE * atr, target.return63(),
                String.format(Locale.ROOT, "ROT leader %s, 63d return %+.1f%%%s",
                        target.symbol(), target.return63() * 100,
                        reviewToday ? "" : " (rotation, reviewed yesterday)")));
        return signals;
    }

    /**
     * The top ETF by 63-day return on closes up to {@code cutoff}, or null
     * when none is ranked or the best return is not positive. Ties go to the
     * earlier symbol in the frozen list.
     */
    static Leader leader(MarketSnapshot snapshot, LocalDate cutoff) {
        Leader best = null;
        for (String symbol : UNIVERSE) {
            List<Candle> candles = upTo(snapshot.candles(symbol), cutoff);
            if (candles.size() < LOOKBACK + 1) continue;      // insufficient history: not ranked
            double r = Indicators.totalReturn(candles, LOOKBACK);
            if (Double.isNaN(r)) continue;
            if (best == null || r > best.return63()) best = new Leader(symbol, r);
        }
        return best != null && best.return63() > 0 ? best : null;
    }

    /** The universe member with the longest history — its bars are the review calendar. */
    private static List<Candle> calendar(MarketSnapshot snapshot) {
        List<Candle> longest = List.of();
        for (String symbol : UNIVERSE) {
            List<Candle> candles = snapshot.candles(symbol);
            if (candles.size() > longest.size()) longest = candles;
        }
        return longest;
    }

    private static boolean isFirstOfMonth(List<Candle> calendar, int i) {
        return i >= 1 && !YearMonth.from(calendar.get(i).date())
                .equals(YearMonth.from(calendar.get(i - 1).date()));
    }

    private static List<Candle> upTo(List<Candle> candles, LocalDate cutoff) {
        int end = candles.size();
        while (end > 0 && candles.get(end - 1).date().isAfter(cutoff)) end--;
        return candles.subList(0, end);
    }
}
