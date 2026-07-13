package in.shrikant.swingtrader.db;

import in.shrikant.swingtrader.data.Candle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SQLite access for EOD candles. Dates are stored as ISO strings (sortable). */
public final class CandleRepository {

    private final Connection conn;

    public CandleRepository(Connection conn) {
        this.conn = conn;
    }

    /** The newest stored candle date for a symbol — where incremental fetch resumes. */
    public Optional<LocalDate> lastDateFor(String symbol) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT MAX(date) FROM candles WHERE symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                String max = rs.next() ? rs.getString(1) : null;
                return max == null ? Optional.empty() : Optional.of(LocalDate.parse(max));
            }
        }
    }

    /** Insert-or-replace candles (idempotent — refetching a day is harmless). */
    public void upsertAll(List<Candle> candles) throws SQLException {
        String sql = """
                INSERT INTO candles (symbol, date, open, high, low, close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(symbol, date) DO UPDATE SET
                    open = excluded.open, high = excluded.high,
                    low = excluded.low, close = excluded.close,
                    volume = excluded.volume
                """;
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Candle c : candles) {
                ps.setString(1, c.symbol());
                ps.setString(2, c.date().toString());
                ps.setDouble(3, c.open());
                ps.setDouble(4, c.high());
                ps.setDouble(5, c.low());
                ps.setDouble(6, c.close());
                ps.setLong(7, c.volume());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /** All candles for a symbol up to and including {@code to}, oldest first. */
    public List<Candle> candlesUpTo(String symbol, LocalDate to) throws SQLException {
        List<Candle> candles = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT symbol, date, open, high, low, close, volume
                FROM candles WHERE symbol = ? AND date <= ? ORDER BY date""")) {
            ps.setString(1, symbol);
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candles.add(new Candle(
                            rs.getString(1), LocalDate.parse(rs.getString(2)),
                            rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                            rs.getDouble(6), rs.getLong(7)));
                }
            }
        }
        return candles;
    }

    /** Every symbol with stored candles, ascending history each — backtester input. */
    public java.util.Map<String, List<Candle>> allCandles() throws SQLException {
        java.util.Map<String, List<Candle>> bySymbol = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT symbol, date, open, high, low, close, volume
                FROM candles ORDER BY symbol, date""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Candle c = new Candle(
                        rs.getString(1), LocalDate.parse(rs.getString(2)),
                        rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                        rs.getDouble(6), rs.getLong(7));
                bySymbol.computeIfAbsent(c.symbol(), s -> new ArrayList<>()).add(c);
            }
        }
        return bySymbol;
    }

    public int countFor(String symbol) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM candles WHERE symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
