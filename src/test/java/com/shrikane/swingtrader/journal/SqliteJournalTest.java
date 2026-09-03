package com.shrikane.swingtrader.journal;

import com.shrikane.swingtrader.db.Database;
import com.shrikane.swingtrader.risk.Position;
import com.shrikane.swingtrader.signal.Signal;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trips of the JDBC journal against a real in-memory SQLite DB. */
class SqliteJournalTest {

    private static final LocalDate D = LocalDate.of(2026, 7, 13);

    @Test
    void orderLifecycle() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            SqliteJournal journal = new SqliteJournal(conn);

            long id = journal.createOrder(D, "TCS", Signal.Action.ENTER, 100, 3500, 3400);
            assertTrue(id > 0);
            assertEquals(1, journal.pendingOrders().size());
            assertEquals("TCS", journal.pendingOrders().get(0).symbol());

            journal.fillOrder(id, D.plusDays(1), 3510, 12.5);
            assertTrue(journal.pendingOrders().isEmpty());

            long id2 = journal.createOrder(D, "INFY", Signal.Action.ENTER, 50, 1500, 1450);
            journal.cancelOrder(id2, "no candle");
            assertTrue(journal.pendingOrders().isEmpty());
        }
    }

    @Test
    void positionsCashAndMeta() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            SqliteJournal journal = new SqliteJournal(conn);

            assertEquals(-1, journal.cash(), 1e-9); // unset marker
            journal.setCash(97_500.50);
            assertEquals(97_500.50, journal.cash(), 1e-9);

            journal.addPosition(new Position("TCS", 100, 3500, D, 3400));
            assertEquals(1, journal.openPositions().size());
            assertEquals(3500, journal.openPositions().get(0).entryPrice(), 1e-9);
            journal.removePosition("TCS");
            assertTrue(journal.openPositions().isEmpty());

            assertTrue(journal.meta("nope").isEmpty());
            journal.setMeta("k", "v1");
            journal.setMeta("k", "v2"); // upsert
            assertEquals(Optional.of("v2"), journal.meta("k"));
        }
    }

    @Test
    void equityHistoryQueries() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            SqliteJournal journal = new SqliteJournal(conn);
            journal.saveEquity(D, 100_000, 100_000, 0);
            journal.saveEquity(D.plusDays(1), 104_000, 50_000, 2);
            journal.saveEquity(D.plusDays(2), 102_000, 50_000, 2);

            assertEquals(Optional.of(104_000.0), journal.equityPeakSince(D));
            assertEquals(Optional.of(102_000.0), journal.equityPeakSince(D.plusDays(2)));
            assertTrue(journal.equityPeakSince(D.plusDays(3)).isEmpty());
            assertEquals(Optional.of(104_000.0), journal.lastEquityBefore(D.plusDays(2)));
            assertTrue(journal.lastEquityBefore(D).isEmpty());

            journal.saveEquity(D.plusDays(2), 103_000, 51_000, 2); // upsert same day
            assertEquals(Optional.of(103_000.0), journal.equityPeakSince(D.plusDays(2)));
        }
    }

    @Test
    void signalsAreRecorded() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            SqliteJournal journal = new SqliteJournal(conn);
            journal.recordSignal(D, new Signal("TCS", Signal.Action.ENTER,
                    3500, 3400, 1.2, "test"), false, "kill switch active");
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("SELECT symbol, approved, note FROM signals")) {
                assertTrue(rs.next());
                assertEquals("TCS", rs.getString(1));
                assertEquals(0, rs.getInt(2));
                assertEquals("kill switch active", rs.getString(3));
            }
        }
    }
}
