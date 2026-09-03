package com.shrikane.swingtrader.backtest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostModelTest {

    /**
     * Worked example from blueprint §3: buy ₹20,000 / sell ₹20,200 delivery
     * round trip on Zerodha ≈ ₹60.04 total charges (~0.30% of position).
     */
    @Test
    void roundTripMatchesBlueprintWorkedExample() {
        CostModel cm = new CostModel(0); // no slippage; charges only
        double total = cm.buyCharges(20_000) + cm.sellCharges(20_200);
        assertEquals(60.04, total, 0.05);
        assertTrue(total / 20_000 < 0.0035, "round trip should be ~0.30% of position");
    }

    @Test
    void dpChargeMakesSmallPositionsRelativelyExpensive() {
        CostModel cm = new CostModel(0);
        double smallPct = (cm.buyCharges(8_000) + cm.sellCharges(8_000)) / 8_000;
        double bigPct = (cm.buyCharges(25_000) + cm.sellCharges(25_000)) / 25_000;
        assertTrue(smallPct > bigPct, "fixed DP charge should penalise small positions");
    }

    @Test
    void slippageMovesFillsAdversely() {
        CostModel cm = new CostModel(0.0005);
        assertTrue(cm.buyFillPrice(100) > 100);
        assertTrue(cm.sellFillPrice(100) < 100);
    }
}
