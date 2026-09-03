package com.shrikane.swingtrader.risk;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** An open position. */
public record Position(
        String symbol,
        int quantity,
        double entryPrice,
        LocalDate entryDate,
        double stopPrice
) {
    /**
     * Calendar-day approximation of trading days held.
     * TODO (Phase 1): replace with a proper trading-calendar count
     * (NSE holidays) once the calendar table exists in the DB.
     */
    public int tradingDaysHeld(LocalDate asOf) {
        long calendarDays = ChronoUnit.DAYS.between(entryDate, asOf);
        return (int) Math.round(calendarDays * 5.0 / 7.0);
    }

    public double marketValue(double lastPrice) {
        return quantity * lastPrice;
    }
}
