package in.shrikant.swingtrader.backtest;

import java.time.LocalDate;

/** One completed round trip, with all costs accounted. */
public record Trade(
        String symbol,
        int quantity,
        LocalDate entryDate,     // fill date (the open after the signal)
        double entryFill,        // per share, slippage included
        LocalDate exitDate,
        double exitFill,         // per share, slippage included
        double entryCharges,     // Zerodha buy-side charges (₹)
        double exitCharges,      // Zerodha sell-side charges (₹)
        String exitReason
) {
    /** PnL from prices alone (slippage already inside the fills). */
    public double grossPnl() {
        return (exitFill - entryFill) * quantity;
    }

    /** What actually lands in the account. */
    public double netPnl() {
        return grossPnl() - entryCharges - exitCharges;
    }

    /** Net PnL as a fraction of capital deployed. */
    public double returnFraction() {
        double deployed = entryFill * quantity;
        return deployed <= 0 ? 0 : netPnl() / deployed;
    }

    public boolean isWin() {
        return netPnl() > 0;
    }
}
