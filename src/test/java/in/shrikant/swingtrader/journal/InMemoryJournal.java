package in.shrikant.swingtrader.journal;

import in.shrikant.swingtrader.risk.Position;
import in.shrikant.swingtrader.signal.Signal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * In-memory {@link Journal} for tests: lets PaperTrader's decision logic run
 * without a database, and exposes the recorded rows for assertions.
 */
public final class InMemoryJournal implements Journal {

    public record SignalRow(LocalDate date, Signal signal, boolean approved, String note) {}
    public record OrderRow(long id, LocalDate createdDate, String symbol,
                           Signal.Action action, int quantity, double refPrice,
                           double stopPrice, String status, LocalDate fillDate,
                           Double fillPrice, Double charges, String note) {}

    public final List<SignalRow> signals = new ArrayList<>();
    public final Map<Long, OrderRow> orders = new HashMap<>();
    public final Map<String, Position> positions = new HashMap<>();
    public final TreeMap<LocalDate, double[]> equity = new TreeMap<>(); // [equity, cash, n]
    public final Map<String, String> metaMap = new HashMap<>();
    private double cash = -1;
    private long nextId = 1;

    @Override
    public void recordSignal(LocalDate date, Signal signal, boolean approved, String note) {
        signals.add(new SignalRow(date, signal, approved, note));
    }

    @Override
    public long createOrder(LocalDate createdDate, String symbol, Signal.Action action,
                            int quantity, double refPrice, double stopPrice) {
        long id = nextId++;
        orders.put(id, new OrderRow(id, createdDate, symbol, action, quantity,
                refPrice, stopPrice, "PENDING", null, null, null, null));
        return id;
    }

    @Override
    public List<PendingOrder> pendingOrders() {
        return orders.values().stream()
                .filter(o -> o.status().equals("PENDING"))
                .sorted((a, b) -> Long.compare(a.id(), b.id()))
                .map(o -> new PendingOrder(o.id(), o.createdDate(), o.symbol(),
                        o.action(), o.quantity(), o.refPrice(), o.stopPrice()))
                .toList();
    }

    @Override
    public void fillOrder(long orderId, LocalDate fillDate, double fillPrice, double charges) {
        OrderRow o = orders.get(orderId);
        orders.put(orderId, new OrderRow(o.id(), o.createdDate(), o.symbol(), o.action(),
                o.quantity(), o.refPrice(), o.stopPrice(), "FILLED",
                fillDate, fillPrice, charges, o.note()));
    }

    @Override
    public void cancelOrder(long orderId, String reason) {
        OrderRow o = orders.get(orderId);
        orders.put(orderId, new OrderRow(o.id(), o.createdDate(), o.symbol(), o.action(),
                o.quantity(), o.refPrice(), o.stopPrice(), "CANCELLED",
                null, null, null, reason));
    }

    @Override public double cash() { return cash; }
    @Override public void setCash(double cash) { this.cash = cash; }

    @Override
    public List<Position> openPositions() {
        return positions.values().stream()
                .sorted((a, b) -> a.symbol().compareTo(b.symbol())).toList();
    }

    @Override public void addPosition(Position position) { positions.put(position.symbol(), position); }
    @Override public void removePosition(String symbol) { positions.remove(symbol); }

    @Override
    public void saveEquity(LocalDate date, double eq, double cash, int openPositions) {
        equity.put(date, new double[]{eq, cash, openPositions});
    }

    @Override
    public Optional<Double> equityPeakSince(LocalDate since) {
        return equity.tailMap(since, true).values().stream()
                .map(v -> v[0]).max(Double::compareTo);
    }

    @Override
    public Optional<Double> lastEquityBefore(LocalDate date) {
        var entry = equity.headMap(date, false).lastEntry();
        return entry == null ? Optional.empty() : Optional.of(entry.getValue()[0]);
    }

    @Override public Optional<String> meta(String key) { return Optional.ofNullable(metaMap.get(key)); }
    @Override public void setMeta(String key, String value) { metaMap.put(key, value); }
}
