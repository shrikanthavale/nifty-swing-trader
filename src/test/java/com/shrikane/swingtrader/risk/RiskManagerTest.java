package com.shrikane.swingtrader.risk;

import com.shrikane.swingtrader.signal.Signal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskManagerTest {

    private static Signal enter(String sym, double ref, double stop) {
        return new Signal(sym, Signal.Action.ENTER, ref, stop, 1, "t");
    }

    @Test
    void defaultProfileIsTheSmallerOfOnePercentRiskAndTwentyFivePercentCap() {
        RiskManager rm = new RiskManager();
        // risk: 1000 / 5 = 200 shares; cap: 25_000 / 100 = 250 → 200
        assertEquals(200, rm.approve(List.of(enter("A", 100, 95)), new Portfolio(100_000),
                100_000, 100_000, 0).get(0).quantity());
        // wide stop: risk 1000 / 1 = 1000; cap 250 wins
        assertEquals(250, rm.approve(List.of(enter("A", 100, 99)), new Portfolio(100_000),
                100_000, 100_000, 0).get(0).quantity());
    }

    @Test
    void sleeveProfileSizesToTheFullSleeveRegardlessOfStopDistance() {
        RiskManager rm = RiskManager.sleeveProfile();
        // ₹16,000 sleeve, NIFTYBEES at 280: floor(16000/280) = 57, even with a 2.5-ATR stop
        var orders = rm.approve(List.of(enter("NIFTYBEES", 280, 262)), new Portfolio(16_000),
                16_000, 16_000, 0);
        assertEquals(57, orders.get(0).quantity());
        // cash still binds
        orders = rm.approve(List.of(enter("NIFTYBEES", 280, 262)), new Portfolio(10_000),
                16_000, 16_000, 0);
        assertEquals(35, orders.get(0).quantity());
    }

    @Test
    void railsJudgeTheAccountWhileSizingUsesTheSleeve() {
        RiskManager rm = RiskManager.sleeveProfile();
        List<Signal> signals = List.of(enter("NIFTYBEES", 280, 262));
        // sleeve healthy, account 7% off its peak → kill switch
        assertTrue(rm.approve(signals, new Portfolio(16_000), 16_000, 46_500, 50_000, 0).isEmpty());
        // account down 3.2% this week → paused
        assertTrue(rm.approve(signals, new Portfolio(16_000), 16_000, 50_000, 50_000, -1_600).isEmpty());
        // account fine → sized on the sleeve, not the ₹50k account
        assertEquals(57, rm.approve(signals, new Portfolio(16_000), 16_000, 50_000, 50_000, 0)
                .get(0).quantity());
    }

    @Test
    void exitsPassEvenUnderTheKillSwitch() {
        Portfolio p = new Portfolio(0);
        p.applyBuy(new Position("NIFTYBEES", 57, 280, java.time.LocalDate.of(2026, 1, 5), 262), 0);
        var orders = RiskManager.sleeveProfile().approve(
                List.of(new Signal("NIFTYBEES", Signal.Action.EXIT, 250, 0, 0, "x")),
                p, 14_250, 40_000, 50_000, -5_000);
        assertEquals(1, orders.size());
        assertEquals(57, orders.get(0).quantity());
    }
}
