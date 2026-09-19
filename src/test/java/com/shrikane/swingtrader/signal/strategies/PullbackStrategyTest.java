package com.shrikane.swingtrader.signal.strategies;

import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.MarketSnapshot;
import com.shrikane.swingtrader.risk.Portfolio;
import com.shrikane.swingtrader.risk.Position;
import com.shrikane.swingtrader.signal.Signal;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PullbackStrategyTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);

    /** Uptrending series ending in a sharp 3-day dive: a textbook deep dip. */
    private static List<Candle> dippingUptrend(String symbol) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 207; i++) {
            double close = 100 + i * 0.3;                    // strong uptrend
            candles.add(new Candle(symbol, START.plusDays(i),
                    close, close + 1, close - 1, close, 1000));
        }
        double last = 100 + 206 * 0.3;
        for (int i = 0; i < 3; i++) {                       // sharp dive, still above SMA
            last -= 4;
            candles.add(new Candle(symbol, START.plusDays(207 + i),
                    last + 1, last + 2, last - 1, last, 1000));
        }
        return candles;
    }

    /** Downtrending filler symbols to drag market breadth below the gate. */
    private static List<Candle> downtrend(String symbol) {
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 210; i++) {
            double close = 300 - i * 0.5;
            candles.add(new Candle(symbol, START.plusDays(i),
                    close, close + 1, close - 1, close, 1000));
        }
        return candles;
    }

    private static MarketSnapshot marketWith(int sickSymbols) {
        Map<String, List<Candle>> bars = new HashMap<>();
        bars.put("DIP", dippingUptrend("DIP"));
        for (int i = 0; i < sickSymbols; i++) bars.put("SICK" + i, downtrend("SICK" + i));
        LocalDate asOf = START.plusDays(209);
        return MarketSnapshot.of(bars, asOf);
    }

    @Test
    void v1EntersOnDeepDipRegardlessOfMarket() {
        // 1 healthy dipper + 3 sick symbols; v1 has no regime filter
        List<Signal> signals = new PullbackStrategy(5.0, 7, 1.5)
                .evaluate(marketWith(3), new Portfolio(100_000));
        assertEquals(1, signals.size());
        assertEquals(Signal.Action.ENTER, signals.get(0).action());
        assertEquals("DIP", signals.get(0).symbol());
    }

    @Test
    void v2BlocksEntriesWhenBreadthBelowGate() {
        // breadth = 1/4 = 25% < 50% gate -> same dip, no entry
        List<Signal> signals = new PullbackStrategy(5.0, 7, 1.5, 0.5)
                .evaluate(marketWith(3), new Portfolio(100_000));
        assertTrue(signals.isEmpty());
    }

    @Test
    void v2AllowsEntriesWhenBreadthAboveGate() {
        // breadth = 1/1 = 100% -> gate passes, entry flows
        List<Signal> signals = new PullbackStrategy(5.0, 7, 1.5, 0.5)
                .evaluate(marketWith(0), new Portfolio(100_000));
        assertEquals(1, signals.size());
        assertEquals(Signal.Action.ENTER, signals.get(0).action());
    }

    @Test
    void v2ExitsStillFlowInSickMarkets() {
        // holding DIP through a sick market: stop/exit logic must not be gated
        Portfolio portfolio = new Portfolio(100_000);
        portfolio.applyBuy(new Position("DIP", 10, 160, START.plusDays(208), 158), 1600);
        List<Signal> signals = new PullbackStrategy(5.0, 7, 1.5, 0.5)
                .evaluate(marketWith(3), portfolio);
        assertEquals(1, signals.size());
        assertEquals(Signal.Action.EXIT, signals.get(0).action());
    }

    @Test
    void namesDistinguishVariants() {
        assertEquals("pullback-v1", new PullbackStrategy().name());
        assertEquals("pullback(rsi<5,hold7,atr1.5,b50%)",
                new PullbackStrategy(5.0, 7, 1.5, 0.5).name());
        assertEquals("pullback(rsi<5,hold7,atr1.5)",
                new PullbackStrategy(5.0, 7, 1.5).name());
    }
}
