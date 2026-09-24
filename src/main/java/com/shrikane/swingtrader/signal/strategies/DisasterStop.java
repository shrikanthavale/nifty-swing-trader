package com.shrikane.swingtrader.signal.strategies;

import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.risk.Position;
import com.shrikane.swingtrader.signal.Indicators;

import java.util.List;

/**
 * The forward campaign's shared disaster stop (forward-campaign.md §4):
 * entry − {@code multiple} × ATR(period), checked at every close; a close
 * below it exits at the next open.
 *
 * "Entry" is the actual fill price; the ATR is the one the strategy saw when
 * it signalled, i.e. on the bars strictly before the fill day. Both are
 * recoverable from the position and the snapshot, so the stop stays a pure
 * function. Falls back to the stored stop if that ATR can't be computed.
 */
final class DisasterStop {

    private DisasterStop() {}

    static double level(List<Candle> candles, Position position, double multiple, int atrPeriod) {
        int signalBars = candles.size() - Indicators.barsSince(candles, position.entryDate().minusDays(1));
        double atr = Indicators.atr(candles.subList(0, Math.max(0, signalBars)), atrPeriod);
        return Double.isNaN(atr) ? position.stopPrice() : position.entryPrice() - multiple * atr;
    }

    static boolean breached(List<Candle> candles, Position position, double multiple, int atrPeriod) {
        if (candles.isEmpty()) return false;
        double close = candles.get(candles.size() - 1).close();
        return close < level(candles, position, multiple, atrPeriod);
    }
}
