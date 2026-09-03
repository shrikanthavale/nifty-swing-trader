package com.shrikane.swingtrader.signal;

/** A strategy's desired action. The RiskManager decides whether it actually trades. */
public record Signal(
        String symbol,
        Action action,
        double referencePrice,  // usually the signal day's close
        double stopPrice,       // initial stop for ENTER signals (0 for EXIT)
        double rank,            // higher = preferred when slots are scarce
        String reason           // human-readable, goes to the journal
) {
    public enum Action { ENTER, EXIT }
}
