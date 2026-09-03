package com.shrikane.swingtrader.journal;

import com.shrikane.swingtrader.risk.Position;
import com.shrikane.swingtrader.signal.Signal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Append-only record of everything the system thinks and does — every signal
 * (including rejected ones), order, fill, daily equity snapshot — plus the
 * durable paper-trading state (cash, open positions, peak-reset marker).
 * Doubles as the tax record (blueprint §4) and the strategy-decay monitor
 * input (§9).
 *
 * Interface so the decision logic in PaperTrader is testable without a
 * database; {@link SqliteJournal} is the real implementation.
 */
public interface Journal {

    /** An order waiting for its fill morning. */
    record PendingOrder(long id, LocalDate createdDate, String symbol,
                        Signal.Action action, int quantity,
                        double refPrice, double stopPrice) {}

    // ---- signals ----

    /** Record a signal and whether risk management let it through. */
    void recordSignal(LocalDate date, Signal signal, boolean approved, String note);

    // ---- orders ----

    long createOrder(LocalDate createdDate, String symbol, Signal.Action action,
                     int quantity, double refPrice, double stopPrice);

    List<PendingOrder> pendingOrders();

    void fillOrder(long orderId, LocalDate fillDate, double fillPrice, double charges);

    void cancelOrder(long orderId, String reason);

    // ---- paper portfolio state ----

    double cash();

    void setCash(double cash);

    List<Position> openPositions();

    void addPosition(Position position);

    void removePosition(String symbol);

    // ---- equity history ----

    void saveEquity(LocalDate date, double equity, double cash, int openPositions);

    /** Highest recorded daily equity on/after {@code since} (empty if none). */
    Optional<Double> equityPeakSince(LocalDate since);

    /** Most recent recorded equity strictly before {@code date} (empty if none). */
    Optional<Double> lastEquityBefore(LocalDate date);

    // ---- meta ----

    Optional<String> meta(String key);

    void setMeta(String key, String value);
}
