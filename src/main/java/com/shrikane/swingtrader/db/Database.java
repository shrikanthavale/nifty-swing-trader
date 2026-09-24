package com.shrikane.swingtrader.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite bootstrap: opens the DB file and creates tables if missing. */
public final class Database {

    private Database() {}

    /** Current schema version, stored in SQLite's PRAGMA user_version. */
    static final int SCHEMA_VERSION = 1;

    /** Sleeve name that pre-sleeve journal rows (the single-strategy `paper` command) belong to. */
    public static final String DEFAULT_SLEEVE = "paper";

    public static Connection open(String path) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        init(conn);
        migrate(conn);
        return conn;
    }

    /**
     * Additive, in-place schema upgrades. Existing journal rows are never
     * dropped: v0 → v1 tags every existing signal/order/equity/position row
     * with the sleeve {@value #DEFAULT_SLEEVE} and re-keys equity_daily and
     * paper_positions by (sleeve, …) so several sleeves can share a date or a
     * symbol (IMR and VRS both hold NIFTYBEES). Runs in one transaction.
     */
    private static void migrate(Connection conn) throws SQLException {
        int version;
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("PRAGMA user_version")) {
            version = rs.next() ? rs.getInt(1) : 0;
        }
        if (version >= SCHEMA_VERSION) return;

        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            if (version < 1) {
                String sleeveCol = " ADD COLUMN sleeve TEXT NOT NULL DEFAULT '" + DEFAULT_SLEEVE + "'";
                st.executeUpdate("ALTER TABLE signals" + sleeveCol);
                st.executeUpdate("ALTER TABLE orders" + sleeveCol);

                st.executeUpdate("""
                    CREATE TABLE equity_daily_v1 (
                        sleeve TEXT NOT NULL,
                        date   TEXT NOT NULL,
                        equity REAL NOT NULL,
                        cash   REAL NOT NULL,
                        open_positions INTEGER NOT NULL,
                        PRIMARY KEY (sleeve, date)
                    )""");
                st.executeUpdate("INSERT INTO equity_daily_v1 (sleeve, date, equity, cash, open_positions) "
                        + "SELECT '" + DEFAULT_SLEEVE + "', date, equity, cash, open_positions FROM equity_daily");
                st.executeUpdate("DROP TABLE equity_daily");
                st.executeUpdate("ALTER TABLE equity_daily_v1 RENAME TO equity_daily");

                st.executeUpdate("""
                    CREATE TABLE paper_positions_v1 (
                        sleeve TEXT NOT NULL,
                        symbol TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        entry_price REAL NOT NULL,
                        entry_date TEXT NOT NULL,
                        stop_price REAL NOT NULL,
                        PRIMARY KEY (sleeve, symbol)
                    )""");
                st.executeUpdate("INSERT INTO paper_positions_v1 "
                        + "(sleeve, symbol, quantity, entry_price, entry_date, stop_price) "
                        + "SELECT '" + DEFAULT_SLEEVE + "', symbol, quantity, entry_price, entry_date, stop_price "
                        + "FROM paper_positions");
                st.executeUpdate("DROP TABLE paper_positions");
                st.executeUpdate("ALTER TABLE paper_positions_v1 RENAME TO paper_positions");

                // every order the live executor places, refuses or would place (dry run)
                st.executeUpdate("""
                    CREATE TABLE live_orders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        date TEXT NOT NULL,             -- evening the order was submitted
                        sleeve TEXT NOT NULL,
                        symbol TEXT NOT NULL,
                        side TEXT NOT NULL,             -- BUY / SELL
                        quantity INTEGER NOT NULL,
                        ref_price REAL,                 -- signal close the order was sized on
                        tag TEXT NOT NULL,              -- idempotency tag sent to Kite
                        outcome TEXT NOT NULL,          -- DRY_RUN / PLACED / SKIPPED_DUPLICATE / REFUSED / FAILED
                        broker_order_id TEXT,
                        ledger_order_id INTEGER,        -- orders.id in the sleeve ledger
                        note TEXT
                    )""");
            }
            st.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    private static void init(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS candles (
                    symbol TEXT NOT NULL,
                    date   TEXT NOT NULL,
                    open REAL, high REAL, low REAL, close REAL,
                    volume INTEGER,
                    PRIMARY KEY (symbol, date)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS instruments (
                    symbol TEXT PRIMARY KEY,
                    instrument_token INTEGER NOT NULL,
                    exchange TEXT NOT NULL DEFAULT 'NSE',
                    name TEXT,
                    synced_on TEXT
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS constituents (
                    symbol TEXT NOT NULL,
                    from_date TEXT NOT NULL,
                    to_date TEXT,           -- null = still a member
                    PRIMARY KEY (symbol, from_date)
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS signals (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    date TEXT NOT NULL,
                    symbol TEXT NOT NULL,
                    action TEXT NOT NULL,          -- ENTER / EXIT
                    ref_price REAL,
                    stop_price REAL,
                    rank REAL,
                    reason TEXT,
                    approved INTEGER NOT NULL,     -- 1 = risk manager approved
                    note TEXT                      -- why rejected, if rejected
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS orders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_date TEXT NOT NULL,    -- signal evening
                    symbol TEXT NOT NULL,
                    action TEXT NOT NULL,
                    quantity INTEGER NOT NULL,
                    ref_price REAL,
                    stop_price REAL,
                    status TEXT NOT NULL,          -- PENDING / FILLED / CANCELLED
                    fill_date TEXT,
                    fill_price REAL,
                    charges REAL,
                    note TEXT
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS equity_daily (
                    date TEXT PRIMARY KEY,
                    equity REAL NOT NULL,
                    cash REAL NOT NULL,
                    open_positions INTEGER NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS paper_positions (
                    symbol TEXT PRIMARY KEY,
                    quantity INTEGER NOT NULL,
                    entry_price REAL NOT NULL,
                    entry_date TEXT NOT NULL,
                    stop_price REAL NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""");
        }
    }
}
