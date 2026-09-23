package com.shrikane.swingtrader.data;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MembershipTableTest {

    private static final String CSV = """
            symbol,from_date,to_date
            AAA,2016-04-01,
            BBB,2016-04-01,2020-03-19
            CCC,2020-03-19,
            BBB,2024-01-01,
            """;

    @Test
    void membershipRespectsIntervalBounds() {
        MembershipTable t = MembershipTable.parseCsv(CSV);
        assertEquals(Set.of("AAA", "BBB"), t.membersOn(LocalDate.of(2019, 6, 1)));
        // boundary day: to_date is EXCLUSIVE, from_date inclusive
        assertEquals(Set.of("AAA", "CCC"), t.membersOn(LocalDate.of(2020, 3, 19)));
        // re-entry interval
        assertEquals(Set.of("AAA", "CCC", "BBB"), t.membersOn(LocalDate.of(2024, 6, 1)));
        assertTrue(t.membersOn(LocalDate.of(2016, 3, 31)).isEmpty());
        assertEquals(3, t.allSymbols().size());
        assertEquals(4, t.intervals().size());
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class,
                () -> MembershipTable.parseCsv("not,a,membership\nfile,x,y"));
        assertThrows(IllegalArgumentException.class, () -> MembershipTable.parseCsv(
                "symbol,from_date,to_date\nAAA,2020-01-01,2019-01-01\n"));
        // overlap: open interval followed by another start
        assertThrows(IllegalArgumentException.class, () -> MembershipTable.parseCsv(
                "symbol,from_date,to_date\nAAA,2016-01-01,\nAAA,2020-01-01,\n"));
    }
}
