package com.shrikane.swingtrader.data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dated index membership held in memory: which symbols were constituents on
 * any given date. Built from the reconstructed NIFTY 50 history (see
 * datasets/nifty50_membership.csv) and loaded via `universe history`.
 * This is the survivorship-bias fix: backtests gate ENTRIES by membership
 * on the signal date, so 2016's test trades 2016's index, not today's.
 */
public final class MembershipTable {

    /** One membership interval: [fromDate, toDate); toDate null = still a member. */
    public record Interval(String symbol, LocalDate fromDate, LocalDate toDate) {}

    private final Map<String, List<Interval>> bySymbol = new HashMap<>();
    private final List<Interval> all;

    public MembershipTable(List<Interval> intervals) {
        this.all = List.copyOf(intervals);
        for (Interval iv : intervals) {
            bySymbol.computeIfAbsent(iv.symbol(), s -> new ArrayList<>()).add(iv);
        }
    }

    public List<Interval> intervals() {
        return all;
    }

    public Set<String> allSymbols() {
        return bySymbol.keySet();
    }

    /** Members on {@code date}: fromDate <= date and (toDate null or toDate > date). */
    public Set<String> membersOn(LocalDate date) {
        Set<String> members = new HashSet<>();
        for (Interval iv : all) {
            if (!iv.fromDate().isAfter(date)
                    && (iv.toDate() == null || iv.toDate().isAfter(date))) {
                members.add(iv.symbol());
            }
        }
        return members;
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    /**
     * Parses the membership CSV (header: symbol,from_date,to_date; empty
     * to_date = open). Pure; throws IllegalArgumentException on malformed
     * rows or overlapping intervals for a symbol.
     */
    public static MembershipTable parseCsv(String csvText) {
        List<Interval> intervals = new ArrayList<>();
        List<String> lines = csvText.lines().filter(l -> !l.isBlank()).toList();
        if (lines.isEmpty() || !lines.get(0).toLowerCase().startsWith("symbol,")) {
            throw new IllegalArgumentException("Not a membership CSV (expect header symbol,from_date,to_date)");
        }
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",", -1);
            if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("Bad membership row " + (i + 1) + ": " + lines.get(i));
            }
            LocalDate from = LocalDate.parse(parts[1].trim());
            LocalDate to = parts[2].isBlank() ? null : LocalDate.parse(parts[2].trim());
            if (to != null && !to.isAfter(from)) {
                throw new IllegalArgumentException("Interval must have to > from at row " + (i + 1));
            }
            intervals.add(new Interval(parts[0].trim(), from, to));
        }
        MembershipTable table = new MembershipTable(intervals);
        for (Map.Entry<String, List<Interval>> e : table.bySymbol.entrySet()) {
            List<Interval> list = new ArrayList<>(e.getValue());
            list.sort((a, b) -> a.fromDate().compareTo(b.fromDate()));
            for (int i = 1; i < list.size(); i++) {
                LocalDate prevTo = list.get(i - 1).toDate();
                if (prevTo == null || prevTo.isAfter(list.get(i).fromDate())) {
                    throw new IllegalArgumentException("Overlapping intervals for " + e.getKey());
                }
            }
        }
        return table;
    }
}
