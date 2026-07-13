package in.shrikant.swingtrader.backtest;

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
 */
public class CostModel {

    private static final double STT = 0.001;
    private static final double NSE_TXN = 0.0000307;
    private static final double SEBI_PER_CRORE = 10.0;
    private static final double GST = 0.18;
    private static final double STAMP_BUY = 0.00015;
    private static final double DP_CHARGE_SELL = 15.34;

    private final double slippagePerSide;

    public CostModel() {
        this(0.0005);
    }

    public CostModel(double slippagePerSide) {
        this.slippagePerSide = slippagePerSide;
    }

    /** Total charges on a buy of {@code value} rupees (excluding slippage). */
    public double buyCharges(double value) {
        double txn = value * NSE_TXN;
        double sebi = value * SEBI_PER_CRORE / 1e7;
        double gst = GST * (txn + sebi);
        double stamp = value * STAMP_BUY;
        double stt = value * STT;
        return stt + txn + sebi + gst + stamp;
    }

    /** Total charges on a sell of {@code value} rupees (excluding slippage). */
    public double sellCharges(double value) {
        double txn = value * NSE_TXN;
        double sebi = value * SEBI_PER_CRORE / 1e7;
        double gst = GST * (txn + sebi);
        double stt = value * STT;
        return stt + txn + sebi + gst + DP_CHARGE_SELL;
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
