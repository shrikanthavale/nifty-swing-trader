package com.shrikane.swingtrader.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import com.shrikane.swingtrader.data.MembershipTable;

/**
 * Dated NIFTY 100 membership (blueprint §6.3, survivorship bias): each row is
 * a membership interval [from_date, to_date), to_date NULL meaning "still a
 * member". Backtests ask "who was in the index on date d", never "who is in
 * it today".
 *
 * v1 seeds only the CURRENT list, so history before the first snapshot is
 * mildly flattered (today's winners). That is a known, documented bias —
 * demand a bigger margin of safety until dated NSE index-change data is
 * loaded.
 */
public final class ConstituentsRepository {

    /** What changed between the stored membership and a new snapshot. */
    public record MembershipDiff(Set<String> added, Set<String> removed) {
        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }
    }

    private final Connection conn;

    public ConstituentsRepository(Connection conn) {
        this.conn = conn;
    }

    /**
     * Pure diff: which symbols enter and leave when moving from
     * {@code current} membership to {@code snapshot}.
     */
    public static MembershipDiff computeDiff(Set<String> current, Set<String> snapshot) {
        Set<String> added = new TreeSet<>(snapshot);
        added.removeAll(current);
        Set<String> removed = new TreeSet<>(current);
        removed.removeAll(snapshot);
        return new MembershipDiff(added, removed);
    }

    /** Symbols that are members right now (open intervals). */
    public Set<String> currentMembers() throws SQLException {
        Set<String> members = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT symbol FROM constituents WHERE to_date IS NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) members.add(rs.getString(1));
        }
        return members;
    }

    /** Membership as of a given date — the query backtests use. */
    public List<String> membersOn(LocalDate date) throws SQLException {
        List<String> members = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT symbol FROM constituents
                WHERE from_date <= ? AND (to_date IS NULL OR to_date > ?)
                ORDER BY symbol""")) {
            ps.setString(1, date.toString());
            ps.setString(2, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) members.add(rs.getString(1));
            }
        }
        return members;
    }

    /**
     * Reconciles the stored membership with a fresh snapshot dated
     * {@code asOf}: new symbols open an interval at asOf, departed symbols
     * close theirs at asOf. Idempotent for an unchanged list.
     */
    public MembershipDiff applySnapshot(Set<String> snapshot, LocalDate asOf) throws SQLException {
        MembershipDiff diff = computeDiff(currentMembers(), snapshot);
        if (diff.isEmpty()) return diff;

        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO constituents (symbol, from_date, to_date) VALUES (?, ?, NULL)")) {
                for (String symbol : diff.added()) {
                    insert.setString(1, symbol);
                    insert.setString(2, asOf.toString());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            try (PreparedStatement close = conn.prepareStatement(
                    "UPDATE constituents SET to_date = ? WHERE symbol = ? AND to_date IS NULL")) {
                for (String symbol : diff.removed()) {
                    close.setString(1, asOf.toString());
                    close.setString(2, symbol);
                    close.addBatch();
                }
                close.executeBatch();
            }
            conn.commit();
            return diff;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /** Wipes the table and loads the given dated intervals (history import). */
    public void replaceAllIntervals(java.util.List<MembershipTable.Interval> intervals)
            throws SQLException {
        boolean previousAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement wipe = conn.prepareStatement("DELETE FROM constituents")) {
                wipe.executeUpdate();
            }
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO constituents (symbol, from_date, to_date) VALUES (?, ?, ?)")) {
                for (MembershipTable.Interval iv : intervals) {
                    insert.setString(1, iv.symbol());
                    insert.setString(2, iv.fromDate().toString());
                    insert.setString(3, iv.toDate() == null ? null : iv.toDate().toString());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(previousAutoCommit);
        }
    }

    /** Every interval in the table, as an in-memory MembershipTable. */
    public MembershipTable loadMembership() throws SQLException {
        java.util.List<MembershipTable.Interval> intervals = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT symbol, from_date, to_date FROM constituents");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String to = rs.getString(3);
                intervals.add(new MembershipTable.Interval(rs.getString(1),
                        LocalDate.parse(rs.getString(2)),
                        to == null ? null : LocalDate.parse(to)));
            }
        }
        return new MembershipTable(intervals);
    }
}
