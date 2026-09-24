package com.shrikane.swingtrader.journal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only record of every order the live executor placed, skipped,
 * refused, failed — or, in dry-run mode, would have placed. This is the
 * paper trail for "what actually left the building" and, together with the
 * sleeve ledgers, the input for measuring true fills vs the cost model
 * (forward-campaign.md §9).
 */
public interface LiveOrderLog {

    record Entry(LocalDate date, String sleeve, String symbol, String side, int quantity,
                 double refPrice, String tag, String outcome, String brokerOrderId,
                 long ledgerOrderId, String note) {}

    void record(Entry entry);

    /** In-memory log (tests, and anywhere persistence isn't wanted). */
    final class InMemory implements LiveOrderLog {
        public final List<Entry> entries = new ArrayList<>();

        @Override
        public void record(Entry entry) {
            entries.add(entry);
        }
    }

    /** The live_orders table (see Database schema v1). */
    final class Sqlite implements LiveOrderLog {
        private final Connection conn;

        public Sqlite(Connection conn) {
            this.conn = conn;
        }

        @Override
        public void record(Entry e) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO live_orders (date, sleeve, symbol, side, quantity, ref_price, tag,
                                             outcome, broker_order_id, ledger_order_id, note)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, e.date().toString());
                ps.setString(2, e.sleeve());
                ps.setString(3, e.symbol());
                ps.setString(4, e.side());
                ps.setInt(5, e.quantity());
                ps.setDouble(6, e.refPrice());
                ps.setString(7, e.tag());
                ps.setString(8, e.outcome());
                ps.setString(9, e.brokerOrderId());
                ps.setLong(10, e.ledgerOrderId());
                ps.setString(11, e.note());
                ps.executeUpdate();
            } catch (SQLException ex) {
                throw new IllegalStateException("live_orders write failed: " + ex.getMessage(), ex);
            }
        }
    }
}
