package com.shrikane.swingtrader.backtest;

import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.data.EtfUniverse;
import com.shrikane.swingtrader.risk.RiskManager;
import com.shrikane.swingtrader.signal.Strategy;
import com.shrikane.swingtrader.signal.strategies.IndexMeanReversionStrategy;
import com.shrikane.swingtrader.signal.strategies.SectorRotationStrategy;
import com.shrikane.swingtrader.signal.strategies.VolRegimeStrategy;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plumbing smoke test, NOT a performance claim: the three forward-campaign
 * families run end to end through the real Backtester (sleeve profile, ETF
 * costs) on seeded random-walk ETFs, and trade at frequencies consistent
 * with their design — the "pathological behaviour" check of
 * forward-campaign.md §7, done on synthetic data so the one real sanity run
 * isn't spent finding bugs.
 */
class EtfFamiliesSmokeTest {

    private static final LocalDate START = LocalDate.of(2016, 1, 1);
    private static final int BARS = 5 * 252;

    private static Map<String, List<Candle>> randomWalkEtfs(long seed) {
        Random rnd = new Random(seed);
        Map<String, List<Candle>> out = new HashMap<>();
        for (String sym : EtfUniverse.SYMBOLS) {
            List<Candle> candles = new ArrayList<>();
            double close = 100;
            LocalDate d = START;
            for (int i = 0; i < BARS; i++) {
                while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    d = d.plusDays(1);
                }
                // volatility regimes that switch every ~quarter, slight upward drift
                double vol = ((i / 63) % 2 == 0) ? 0.008 : 0.016;
                double open = close * (1 + rnd.nextGaussian() * vol * 0.3);
                close = open * (1 + 0.0003 + rnd.nextGaussian() * vol);
                double hi = Math.max(open, close) * (1 + Math.abs(rnd.nextGaussian()) * vol * 0.5);
                double lo = Math.min(open, close) * (1 - Math.abs(rnd.nextGaussian()) * vol * 0.5);
                candles.add(new Candle(sym, d, open, hi, lo, close, 100_000));
                d = d.plusDays(1);
            }
            out.put(sym, candles);
        }
        return out;
    }

    private static Backtester.Result run(Strategy strategy, Map<String, List<Candle>> candles) {
        List<Candle> any = candles.get(EtfUniverse.NIFTYBEES);
        return new Backtester(strategy, RiskManager.sleeveProfile(), CostModel.etf(), 16_000)
                .run(candles, any.get(0).date(), any.get(any.size() - 1).date());
    }

    @Test
    void allThreeFamiliesTradeAtDesignFrequencies() {
        Map<String, List<Candle>> candles = randomWalkEtfs(42);
        double years = BARS / 252.0;

        var imr = run(new IndexMeanReversionStrategy(), candles);
        var rot = run(new SectorRotationStrategy(), candles);
        var vrs = run(new VolRegimeStrategy(), candles);

        double imrPerYear = imr.trades().size() / years;
        double rotPerYear = rot.trades().size() / years;
        double vrsPerYear = vrs.trades().size() / years;

        assertTrue(imr.trades().size() > 0, "IMR never traded");
        assertTrue(imrPerYear < 40, "IMR trading near-daily: " + imrPerYear);
        assertTrue(rot.trades().size() > 0, "ROT never traded");
        assertTrue(rotPerYear <= 24, "ROT is monthly by design: " + rotPerYear + "/yr");
        assertTrue(vrs.trades().size() > 0, "VRS never traded");
        assertTrue(vrsPerYear < 40, "VRS flipping near-daily: " + vrsPerYear);

        // single-position families: never more than one open position
        for (var result : List.of(imr, rot, vrs)) {
            assertTrue(result.openAtEnd().size() <= 1, result.strategyName());
            for (Trade t : result.trades()) {
                assertTrue(t.exitDate().isAfter(t.entryDate()), "exit after entry: " + t);
            }
        }
        // full-sleeve sizing: a typical position is most of the ₹16,000 sleeve
        double avgValue = rot.trades().stream()
                .mapToDouble(t -> t.quantity() * t.entryFill()).average().orElse(0);
        assertTrue(avgValue > 12_000, "ROT positions should be ~full sleeve, got ₹" + avgValue);
    }
}
