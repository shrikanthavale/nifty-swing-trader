package com.shrikane.swingtrader.db;

import com.shrikane.swingtrader.db.ConstituentsRepository.MembershipDiff;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstituentsRepositoryTest {

    // --- pure diff logic ---

    @Test
    void diffFindsAdditionsAndRemovals() {
        MembershipDiff diff = ConstituentsRepository.computeDiff(
                Set.of("A", "B", "C"), Set.of("B", "C", "D"));
        assertEquals(Set.of("D"), diff.added());
        assertEquals(Set.of("A"), diff.removed());
    }

    @Test
    void identicalSnapshotIsEmptyDiff() {
        assertTrue(ConstituentsRepository.computeDiff(
                Set.of("A", "B"), Set.of("A", "B")).isEmpty());
    }

    @Test
    void firstSnapshotIsAllAdditions() {
        MembershipDiff diff = ConstituentsRepository.computeDiff(Set.of(), Set.of("A", "B"));
        assertEquals(Set.of("A", "B"), diff.added());
        assertTrue(diff.removed().isEmpty());
    }

    // --- against a real (in-memory) SQLite database ---

    @Test
    void snapshotLifecycleAndDatedQueries() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            ConstituentsRepository repo = new ConstituentsRepository(conn);
            LocalDate jan = LocalDate.of(2026, 1, 1);
            LocalDate jul = LocalDate.of(2026, 7, 1);

            MembershipDiff first = repo.applySnapshot(Set.of("RELIANCE", "TCS", "OLDCO"), jan);
            assertEquals(3, first.added().size());
            assertEquals(List.of("OLDCO", "RELIANCE", "TCS"), repo.membersOn(jan));

            // July rebalance: OLDCO out, NEWCO in
            MembershipDiff second = repo.applySnapshot(Set.of("RELIANCE", "TCS", "NEWCO"), jul);
            assertEquals(Set.of("NEWCO"), second.added());
            assertEquals(Set.of("OLDCO"), second.removed());

            // dated queries see the right universe for each era
            assertEquals(List.of("OLDCO", "RELIANCE", "TCS"), repo.membersOn(jan.plusMonths(2)));
            assertEquals(List.of("NEWCO", "RELIANCE", "TCS"), repo.membersOn(jul));
            assertTrue(repo.membersOn(jan.minusDays(1)).isEmpty());

            // idempotent: re-applying the same list changes nothing
            assertTrue(repo.applySnapshot(Set.of("RELIANCE", "TCS", "NEWCO"), jul).isEmpty());
            assertEquals(Set.of("RELIANCE", "TCS", "NEWCO"), repo.currentMembers());
        }
    }

    @Test
    void intervalReplaceAndLoadRoundTrip() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            ConstituentsRepository repo = new ConstituentsRepository(conn);
            repo.applySnapshot(Set.of("OLD"), LocalDate.of(2026, 1, 1)); // pre-existing rows
            var table = com.shrikane.swingtrader.data.MembershipTable.parseCsv(
                    "symbol,from_date,to_date\nAAA,2016-04-01,\nBBB,2016-04-01,2020-03-19\n");
            repo.replaceAllIntervals(table.intervals());
            var loaded = repo.loadMembership();
            assertEquals(2, loaded.intervals().size()); // OLD was wiped
            assertEquals(Set.of("AAA", "BBB"), loaded.membersOn(LocalDate.of(2019, 1, 1)));
            assertEquals(Set.of("AAA"), loaded.membersOn(LocalDate.of(2021, 1, 1)));
            assertEquals(List.of("AAA"), repo.membersOn(LocalDate.of(2021, 1, 1))); // SQL path agrees
        }
    }
}
