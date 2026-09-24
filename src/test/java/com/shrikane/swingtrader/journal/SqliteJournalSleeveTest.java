package com.shrikane.swingtrader.journal;

import com.shrikane.swingtrader.db.Database;
import com.shrikane.swingtrader.risk.Position;
import com.shrikane.swingtrader.signal.Signal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sleeve namespacing and the v0 → v1 schema migration. */
class SqliteJournalSleeveTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 1);

    @Test
    void sleevesKeepIndependentLedgersInOneDatabase() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            SqliteJournal imr = new SqliteJournal(conn, "imr");
            SqliteJournal vrs = new SqliteJournal(conn, "vrs");

            // both sleeves hold NIFTYBEES — keyed (sleeve, symbol)
            imr.addPosition(new Position("NIFTYBEES", 57, 280, D, 262));
            vrs.addPosition(new Position("NIFTYBEES", 56, 281, D, 263));
            assertEquals(57, imr.openPositions().get(0).quantity());
            assertEquals(56, vrs.openPositions().get(0).quantity());
            imr.removePosition("NIFTYBEES");
            assertTrue(imr.openPositions().isEmpty());
            assertEquals(1, vrs.openPositions().size());

            // same date, different equity rows
            imr.saveEquity(D, 16_100, 100, 0);
            vrs.saveEquity(D, 15_900, 100, 1);
            assertEquals(Optional.of(16_100.0), imr.equityPeakSince(D));
            assertEquals(Optional.of(15_900.0), vrs.equityPeakSince(D));

            // orders and cash are per sleeve
            long id = imr.createOrder(D, "NIFTYBEES", Signal.Action.ENTER, 57, 280, 262);
            assertEquals(1, imr.pendingOrders().size());
            assertTrue(vrs.pendingOrders().isEmpty());
            vrs.cancelOrder(id, "wrong sleeve must not touch it");
            assertEquals(1, imr.pendingOrders().size());

            imr.setCash(16_000);
            vrs.setCash(15_000);
            assertEquals(16_000, imr.cash(), 1e-9);
            assertEquals(15_000, vrs.cash(), 1e-9);
            assertEquals(-1, new SqliteJournal(conn).cash(), 1e-9); // default sleeve untouched
        }
    }

    @Test
    void migrationKeepsPreSleeveJournalDataUnderTheDefaultSleeve(@TempDir Path dir) throws Exception {
        String path = dir.resolve("old.db").toString();
        // a v0 database as the Phase 3 paper command left it
        try (Connection old = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement st = old.createStatement()) {
            st.executeUpdate("CREATE TABLE signals (id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, "
                    + "symbol TEXT NOT NULL, action TEXT NOT NULL, ref_price REAL, stop_price REAL, rank REAL, "
                    + "reason TEXT, approved INTEGER NOT NULL, note TEXT)");
            st.executeUpdate("CREATE TABLE orders (id INTEGER PRIMARY KEY AUTOINCREMENT, created_date TEXT NOT NULL, "
                    + "symbol TEXT NOT NULL, action TEXT NOT NULL, quantity INTEGER NOT NULL, ref_price REAL, "
                    + "stop_price REAL, status TEXT NOT NULL, fill_date TEXT, fill_price REAL, charges REAL, note TEXT)");
            st.executeUpdate("CREATE TABLE equity_daily (date TEXT PRIMARY KEY, equity REAL NOT NULL, "
                    + "cash REAL NOT NULL, open_positions INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE paper_positions (symbol TEXT PRIMARY KEY, quantity INTEGER NOT NULL, "
                    + "entry_price REAL NOT NULL, entry_date TEXT NOT NULL, stop_price REAL NOT NULL)");
            st.executeUpdate("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.executeUpdate("INSERT INTO orders (created_date, symbol, action, quantity, ref_price, stop_price, status) "
                    + "VALUES ('2026-09-01', 'TCS', 'EXIT', 10, 3500, 0, 'PENDING')");
            st.executeUpdate("INSERT INTO equity_daily VALUES ('2026-09-01', 101000, 50000, 1)");
            st.executeUpdate("INSERT INTO paper_positions VALUES ('TCS', 10, 3400, '2026-08-25', 3300)");
            st.executeUpdate("INSERT INTO meta VALUES ('paper_cash', '50000')");
        }

        for (int run = 0; run < 2; run++) {                  // second open: migration is a no-op
            try (Connection conn = Database.open(path)) {
                SqliteJournal paper = new SqliteJournal(conn);
                assertEquals(1, paper.pendingOrders().size());
                assertEquals("TCS", paper.openPositions().get(0).symbol());
                assertEquals(Optional.of(101_000.0), paper.equityPeakSince(LocalDate.of(2026, 1, 1)));
                assertEquals(50_000, paper.cash(), 1e-9);
                assertTrue(new SqliteJournal(conn, "imr").openPositions().isEmpty());
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                    assertEquals(1, rs.getInt(1));
                }
            }
        }
    }
}
