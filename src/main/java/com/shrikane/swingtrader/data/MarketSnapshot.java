package com.shrikane.swingtrader.data;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A view of market history up to and including {@code asOf} — and structurally
 * nothing later. This is the core anti-lookahead-bias guarantee: strategies
 * receive a MarketSnapshot and cannot see the future, in backtests or live.
 *
 * Construct via {@link #of(Map, LocalDate)} which filters out any bar after asOf.
 */
public final class MarketSnapshot {

    private final Map<String, List<Candle>> candlesBySymbol; // ascending by date
    private final LocalDate asOf;
    private final java.util.Set<String> tradeable; // symbols eligible for NEW entries

    private MarketSnapshot(Map<String, List<Candle>> candlesBySymbol, LocalDate asOf,
                           java.util.Set<String> tradeable) {
        this.candlesBySymbol = candlesBySymbol;
        this.asOf = asOf;
        this.tradeable = tradeable;
    }

    public static MarketSnapshot of(Map<String, List<Candle>> raw, LocalDate asOf) {
        Map<String, List<Candle>> filtered = raw.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .filter(c -> !c.date().isAfter(asOf))
                                .sorted((a, b) -> a.date().compareTo(b.date()))
                                .toList()));
        return new MarketSnapshot(filtered, asOf, null);
    }

    /**
     * Fast path for the backtester, which builds thousands of snapshots over
     * the SAME candle lists: input must already be ascending by date, and the
     * cutoff is applied via binary search + subList views (no copying).
     * Semantics are identical to {@link #of} — verified by a unit test.
     */
    public static MarketSnapshot ofPresorted(Map<String, List<Candle>> sortedBySymbol,
                                             LocalDate asOf) {
        return ofPresorted(sortedBySymbol, asOf, null);
    }

    /**
     * As above, additionally restricting {@link #symbols()} to the given
     * eligible set (dated index membership — the survivorship-bias gate).
     * {@link #candles(String)} stays unrestricted so EXITS of positions in
     * symbols that have since left the index keep working. null = no gate.
     */
    public static MarketSnapshot ofPresorted(Map<String, List<Candle>> sortedBySymbol,
                                             LocalDate asOf, java.util.Set<String> eligible) {
        Map<String, List<Candle>> filtered = new java.util.HashMap<>();
        for (Map.Entry<String, List<Candle>> e : sortedBySymbol.entrySet()) {
            filtered.put(e.getKey(), headUpTo(e.getValue(), asOf));
        }
        return new MarketSnapshot(Collections.unmodifiableMap(filtered), asOf, eligible);
    }

    /** The prefix of {@code sorted} with date <= asOf (binary search, view). */
    private static List<Candle> headUpTo(List<Candle> sorted, LocalDate asOf) {
        int lo = 0, hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid).date().isAfter(asOf)) hi = mid;
            else lo = mid + 1;
        }
        return sorted.subList(0, lo);
    }

    public LocalDate asOf() {
        return asOf;
    }

    /** Candles for a symbol, ascending by date, ending at asOf. Empty list if unknown. */
    public List<Candle> candles(String symbol) {
        return candlesBySymbol.getOrDefault(symbol, Collections.emptyList());
    }

    /** The most recent {@code n} candles for a symbol (or fewer if history is short). */
    public List<Candle> lastCandles(String symbol, int n) {
        List<Candle> all = candles(symbol);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    /** Symbols a strategy may consider for NEW entries (with data, and in the
     *  eligible membership set when one was supplied). */
    public java.util.Set<String> symbols() {
        if (tradeable == null) return candlesBySymbol.keySet();
        java.util.Set<String> out = new java.util.HashSet<>(tradeable);
        out.retainAll(candlesBySymbol.keySet());
        return out;
    }
}
