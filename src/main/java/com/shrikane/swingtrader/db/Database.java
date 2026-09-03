package com.shrikane.swingtrader.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite bootstrap: opens the DB file and creates tables if missing. */
public final class Database {

    private Database() {}

    public static Connection open(String path) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        init(conn);
        return conn;
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
