package in.shrikant.swingtrader.data;

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

    private MarketSnapshot(Map<String, List<Candle>> candlesBySymbol, LocalDate asOf) {
        this.candlesBySymbol = candlesBySymbol;
        this.asOf = asOf;
    }

    public static MarketSnapshot of(Map<String, List<Candle>> raw, LocalDate asOf) {
        Map<String, List<Candle>> filtered = raw.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .filter(c -> !c.date().isAfter(asOf))
                                .sorted((a, b) -> a.date().compareTo(b.date()))
                                .toList()));
        return new MarketSnapshot(filtered, asOf);
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

    public java.util.Set<String> symbols() {
        return candlesBySymbol.keySet();
    }
}
