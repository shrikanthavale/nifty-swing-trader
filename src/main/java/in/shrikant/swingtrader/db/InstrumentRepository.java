package in.shrikant.swingtrader.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * SQLite access for the instruments table: the symbol → instrument_token
 * mapping that Kite's historical data API needs (it takes tokens, not
 * trading symbols).
 */
public final class InstrumentRepository {

    /** One row of the instruments table. */
    public record InstrumentRow(String symbol, long instrumentToken,
                                String exchange, String name) {}

    private final Connection conn;

    public InstrumentRepository(Connection conn) {
        this.conn = conn;
    }

    /** Insert-or-replace the given instruments, stamped with {@code syncedOn}. */
    public void upsertAll(List<InstrumentRow> rows, LocalDate syncedOn) throws SQLException {
        String sql = """
                INSERT INTO instruments (symbol, instrument_token, exchange, name, synced_on)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(symbol) DO UPDATE SET
                    instrument_token = excluded.instrument_token,
                    exchange = excluded.exchange,
                    name = excluded.name,
                    synced_on = excluded.synced_on
                """;
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (InstrumentRow row : rows) {
                ps.setString(1, row.symbol());
                ps.setLong(2, row.instrumentToken());
                ps.setString(3, row.exchange());
                ps.setString(4, row.name());
                ps.setString(5, syncedOn.toString());
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

    /** The instrument token for a trading symbol, if we have it. */
    public Optional<Long> tokenFor(String symbol) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT instrument_token FROM instruments WHERE symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    public int count() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM instruments");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
