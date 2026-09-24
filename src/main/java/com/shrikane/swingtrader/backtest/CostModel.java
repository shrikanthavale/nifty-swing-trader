package com.shrikane.swingtrader.backtest;

/**
 * Zerodha CNC (delivery) cost model, verified against zerodha.com/charges
 * as of July 2026 (blueprint §3). Every backtest fill goes through this —
 * a backtest without costs is fiction.
 *
 *  - Brokerage: zero (delivery)
 *  - STT: 0.1% on buy AND sell
 *  - NSE transaction charge: 0.00307%
 *  - SEBI charges: ₹10 per crore
 *  - GST: 18% on (transaction + SEBI charges)
 *  - Stamp duty: 0.015% on buy side
 *  - DP charge: ₹15.34 per scrip on sell
 *  - Slippage: configurable, default 0.05% per side (NIFTY 100 at open)
 *
 * ETF profile ({@link #etf()}, forward-campaign.md §4 note): exchange-traded
 * funds pay STT only on the SELL side and only 0.001% (vs 0.1% each way for
 * delivery equity). Exchange txn, SEBI, GST and stamp duty keep the same
 * structure; DP is the same ₹15.34 per scrip per sell day as equity
 * (forward-campaign.md Amendment A2); slippage assumption is the same. Profile selection is by
 * the frozen symbol list ({@link com.shrikane.swingtrader.data.EtfUniverse}),
 * not the instruments table (Kite files ETFs under instrument_type "EQ"):
 * the three sleeves whose strategies trade only that list use {@link #etf()},
 * the stock strategies keep the default.
 */
public class CostModel {

    private static final double STT = 0.001;
    private static final double NSE_TXN = 0.0000307;
    private static final double SEBI_PER_CRORE = 10.0;
    private static final double GST = 0.18;
    private static final double STAMP_BUY = 0.00015;
    private static final double DP_CHARGE_SELL = 15.34;

    private static final double ETF_STT_SELL = 0.00001;    // 0.001%, sell side only
    private static final double ETF_DP_CHARGE_SELL = DP_CHARGE_SELL; // same DP as equity (Amendment A2)

    private final double slippagePerSide;
    private final double sttBuy;
    private final double sttSell;
    private final double dpChargeSell;

    public CostModel() {
        this(0.0005);
    }

    public CostModel(double slippagePerSide) {
        this(slippagePerSide, STT, STT, DP_CHARGE_SELL);
    }

    private CostModel(double slippagePerSide, double sttBuy, double sttSell, double dpChargeSell) {
        this.slippagePerSide = slippagePerSide;
        this.sttBuy = sttBuy;
        this.sttSell = sttSell;
        this.dpChargeSell = dpChargeSell;
    }

    /** ETF delivery profile with the default slippage assumption. */
    public static CostModel etf() {
        return etf(0.0005);
    }

    public static CostModel etf(double slippagePerSide) {
        return new CostModel(slippagePerSide, 0.0, ETF_STT_SELL, ETF_DP_CHARGE_SELL);
    }

    /** Total charges on a buy of {@code value} rupees (excluding slippage). */
    public double buyCharges(double value) {
        double txn = value * NSE_TXN;
        double sebi = value * SEBI_PER_CRORE / 1e7;
        double gst = GST * (txn + sebi);
        double stamp = value * STAMP_BUY;
        double stt = value * sttBuy;
        return stt + txn + sebi + gst + stamp;
    }

    /** Total charges on a sell of {@code value} rupees (excluding slippage). */
    public double sellCharges(double value) {
        double txn = value * NSE_TXN;
        double sebi = value * SEBI_PER_CRORE / 1e7;
        double gst = GST * (txn + sebi);
        double stt = value * sttSell;
        return stt + txn + sebi + gst + dpChargeSell;
    }

    /** Effective buy fill price after adverse slippage. */
    public double buyFillPrice(double referencePrice) {
        return referencePrice * (1 + slippagePerSide);
    }

    /** Effective sell fill price after adverse slippage. */
    public double sellFillPrice(double referencePrice) {
        return referencePrice * (1 - slippagePerSide);
    }
}
