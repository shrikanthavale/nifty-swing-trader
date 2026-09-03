package in.shrikant.swingtrader.journal;

import in.shrikant.swingtrader.risk.Position;
import in.shrikant.swingtrader.signal.Signal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed {@link Journal}. Thin JDBC mapping over the tables created in
 * {@link in.shrikant.swingtrader.db.Database}; all decision logic lives in
 * PaperTrader, which is tested against an in-memory Journal.
 */
public final class SqliteJournal implements Journal {

    private final Connection conn;

    public SqliteJournal(Connection conn) {
        this.conn = conn;
    }

    /** SQLException → IllegalStateException so the interface stays clean. */
    private static RuntimeException wrap(SQLException e) {
        return new IllegalStateException("Journal DB failure: " + e.getMessage(), e);
    }

    @Override
    public void recordSignal(LocalDate date, Signal signal, boolean approved, String note) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO signals (date, symbol, action, ref_price, stop_price, rank, reason, approved, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, date.toString());
            ps.setString(2, signal.symbol());
            ps.setString(3, signal.action().name());
            ps.setDouble(4, signal.referencePrice());
            ps.setDouble(5, signal.stopPrice());
            ps.setDouble(6, signal.rank());
            ps.setString(7, signal.reason());
            ps.setInt(8, approved ? 1 : 0);
            ps.setString(9, note);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public long createOrder(LocalDate createdDate, String symbol, Signal.Action action,
                            int quantity, double refPrice, double stopPrice) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO orders (created_date, symbol, action, quantity, ref_price, stop_price, status)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING')""",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, createdDate.toString());
            ps.setString(2, symbol);
            ps.setString(3, action.name());
            ps.setInt(4, quantity);
            ps.setDouble(5, refPrice);
            ps.setDouble(6, stopPrice);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public List<PendingOrder> pendingOrders() {
        List<PendingOrder> orders = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, created_date, symbol, action, quantity, ref_price, stop_price
                FROM orders WHERE status = 'PENDING' ORDER BY id""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                orders.add(new PendingOrder(rs.getLong(1),
                        LocalDate.parse(rs.getString(2)), rs.getString(3),
                        Signal.Action.valueOf(rs.getString(4)), rs.getInt(5),
                        rs.getDouble(6), rs.getDouble(7)));
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
        return orders;
    }

    @Override
    public void fillOrder(long orderId, LocalDate fillDate, double fillPrice, double charges) {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE orders SET status = 'FILLED', fill_date = ?, fill_price = ?, charges = ?
                WHERE id = ?""")) {
            ps.setString(1, fillDate.toString());
            ps.setDouble(2, fillPrice);
            ps.setDouble(3, charges);
            ps.setLong(4, orderId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public void cancelOrder(long orderId, String reason) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE orders SET status = 'CANCELLED', note = ? WHERE id = ?")) {
            ps.setString(1, reason);
            ps.setLong(2, orderId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public double cash() {
        return meta("paper_cash").map(Double::parseDouble).orElse(-1.0);
    }

    @Override
    public void setCash(double cash) {
        setMeta("paper_cash", String.valueOf(cash));
    }

    @Override
    public List<Position> openPositions() {
        List<Position> positions = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT symbol, quantity, entry_price, entry_date, stop_price
                FROM paper_positions ORDER BY symbol""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                positions.add(new Position(rs.getString(1), rs.getInt(2),
                        rs.getDouble(3), LocalDate.parse(rs.getString(4)),
                        rs.getDouble(5)));
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
        return positions;
    }

    @Override
    public void addPosition(Position p) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO paper_positions (symbol, quantity, entry_price, entry_date, stop_price)
                VALUES (?, ?, ?, ?, ?)""")) {
            ps.setString(1, p.symbol());
            ps.setInt(2, p.quantity());
            ps.setDouble(3, p.entryPrice());
            ps.setString(4, p.entryDate().toString());
            ps.setDouble(5, p.stopPrice());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public void removePosition(String symbol) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM paper_positions WHERE symbol = ?")) {
            ps.setString(1, symbol);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public void saveEquity(LocalDate date, double equity, double cash, int openPositions) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO equity_daily (date, equity, cash, open_positions)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(date) DO UPDATE SET
                    equity = excluded.equity, cash = excluded.cash,
                    open_positions = excluded.open_positions""")) {
            ps.setString(1, date.toString());
            ps.setDouble(2, equity);
            ps.setDouble(3, cash);
            ps.setInt(4, openPositions);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public Optional<Double> equityPeakSince(LocalDate since) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT MAX(equity) FROM equity_daily WHERE date >= ?")) {
            ps.setString(1, since.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double max = rs.getDouble(1);
                    if (!rs.wasNull()) return Optional.of(max);
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public Optional<Double> lastEquityBefore(LocalDate date) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT equity FROM equity_daily WHERE date < ? ORDER BY date DESC LIMIT 1")) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getDouble(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public Optional<String> meta(String key) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    @Override
    public void setMeta(String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO meta (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value""")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }
}
