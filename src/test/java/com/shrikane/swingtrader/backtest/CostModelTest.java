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

    @Test
    void etfProfileChargesSellSideSttOnlyAndCostsFarLessThanEquity() {
        CostModel etf = CostModel.etf(0);
        CostModel eq = new CostModel(0);
        // buy: no STT at all → only txn + SEBI + GST + stamp ≈ 0.0187%
        assertEquals(16_000 * (0.0000307 + 1e-6) * 1.18 + 16_000 * 0.00015,
                etf.buyCharges(16_000), 1e-9);
        // sell: 0.001% STT + txn + SEBI + GST + ₹15.34 DP
        assertEquals(16_000 * 0.00001 + 16_000 * (0.0000307 + 1e-6) * 1.18 + 15.34,
                etf.sellCharges(16_000), 1e-9);
        double etfRoundTrip = etf.buyCharges(16_000) + etf.sellCharges(16_000);
        double eqRoundTrip = eq.buyCharges(16_000) + eq.sellCharges(16_000);
        assertTrue(etfRoundTrip / 16_000 < 0.0015, "ETF round trip ~0.13% on ₹16k: " + etfRoundTrip);
        assertTrue(etfRoundTrip < eqRoundTrip / 2, "ETF costs should be well under half of equity");
    }

    @Test
    void etfProfileKeepsTheSameSlippageAssumption() {
        assertEquals(new CostModel().buyFillPrice(100), CostModel.etf().buyFillPrice(100), 1e-12);
        assertEquals(new CostModel().sellFillPrice(100), CostModel.etf().sellFillPrice(100), 1e-12);
    }
}
