package com.shrikane.swingtrader.db;

import com.shrikane.swingtrader.data.Candle;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandleRepositoryTest {

    @Test
    void upsertLastDateAndOrderedReads() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            CandleRepository repo = new CandleRepository(conn);
            assertTrue(repo.lastDateFor("TCS").isEmpty());

            LocalDate d1 = LocalDate.of(2026, 7, 9);
            LocalDate d2 = LocalDate.of(2026, 7, 10);
            LocalDate d3 = LocalDate.of(2026, 7, 13);
            repo.upsertAll(List.of(
                    new Candle("TCS", d2, 101, 102, 100, 101.5, 2000),
                    new Candle("TCS", d1, 100, 101, 99, 100.5, 1000),
                    new Candle("TCS", d3, 102, 103, 101, 102.5, 3000),
                    new Candle("INFY", d3, 50, 51, 49, 50.5, 500)));

            assertEquals(Optional.of(d3), repo.lastDateFor("TCS"));
            assertEquals(3, repo.countFor("TCS"));

            // cutoff-dated read, oldest first — what MarketSnapshot will consume
            List<Candle> upToD2 = repo.candlesUpTo("TCS", d2);
            assertEquals(List.of(d1, d2), upToD2.stream().map(Candle::date).toList());

            // idempotent re-upsert updates in place
            repo.upsertAll(List.of(new Candle("TCS", d3, 102, 103, 101, 999, 3000)));
            assertEquals(3, repo.countFor("TCS"));
            assertEquals(999, repo.candlesUpTo("TCS", d3).get(2).close());
        }
    }
}
