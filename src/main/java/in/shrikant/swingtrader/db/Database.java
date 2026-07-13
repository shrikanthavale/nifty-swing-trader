package in.shrikant.swingtrader.db;

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
                    exchange TEXT NOT NULL DEFAULT 'NSE'
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS constituents (
                    symbol TEXT NOT NULL,
                    from_date TEXT NOT NULL,
                    to_date TEXT,           -- null = still a member
                    PRIMARY KEY (symbol, from_date)
                )""");
            // signals / orders / fills / equity_daily tables arrive in Phase 3
        }
    }
}
