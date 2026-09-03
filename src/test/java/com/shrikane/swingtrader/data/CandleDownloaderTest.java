package com.shrikane.swingtrader.data;

import com.shrikane.swingtrader.db.CandleRepository;
import com.shrikane.swingtrader.db.Database;
import com.shrikane.swingtrader.db.InstrumentRepository;
import com.shrikane.swingtrader.db.InstrumentRepository.InstrumentRow;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandleDownloaderTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // 2026-07-13 is a Monday
    private static final LocalDate MON = LocalDate.of(2026, 7, 13);
    private static final LocalDate FRI = LocalDate.of(2026, 7, 10);

    /** In-memory fake: serves whatever candles it was given, records calls. */
    private static final class FakeSource implements HistoricalSource {
        final Map<String, List<Candle>> bySymbol = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        void add(String symbol, LocalDate date, double close) {
            bySymbol.computeIfAbsent(symbol, s -> new ArrayList<>())
                    .add(new Candle(symbol, date, close, close, close, close, 1000));
        }

        @Override
        public List<Candle> fetchDaily(String symbol, long token, LocalDate from, LocalDate to) {
            calls.add(symbol + ":" + from + ".." + to);
            return bySymbol.getOrDefault(symbol, List.of()).stream()
                    .filter(c -> !c.date().isBefore(from) && !c.date().isAfter(to))
                    .toList();
        }
    }

    private static CandleDownloader downloader(Connection conn, FakeSource source,
                                               LocalDate start) {
        return new CandleDownloader(source, new InstrumentRepository(conn),
                new CandleRepository(conn), start, 0);
    }

    @Test
    void freshDbDownloadsFromDefaultStartAndPersists() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            new InstrumentRepository(conn).upsertAll(
                    List.of(new InstrumentRow("TCS", 1L, "NSE", "TCS")), MON);
            FakeSource source = new FakeSource();
            source.add("TCS", FRI, 100);
            source.add("TCS", MON, 101);

            CandleDownloader.Result result =
                    downloader(conn, source, FRI).downloadAll(List.of("TCS"), MON);

            assertEquals(2, result.candlesStored());
            assertTrue(result.clean(), "expected clean run: " + result);
            assertEquals(2, new CandleRepository(conn).countFor("TCS"));
        }
    }

    @Test
    void incrementalRunFetchesOnlyAfterLastStoredDate() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            new InstrumentRepository(conn).upsertAll(
                    List.of(new InstrumentRow("TCS", 1L, "NSE", "TCS")), MON);
            CandleRepository candles = new CandleRepository(conn);
            candles.upsertAll(List.of(new Candle("TCS", FRI, 100, 100, 100, 100, 1000)));

            FakeSource source = new FakeSource();
            source.add("TCS", FRI, 100); // available upstream but already stored
            source.add("TCS", MON, 101);

            CandleDownloader.Result result =
                    downloader(conn, source, FRI).downloadAll(List.of("TCS"), MON);

            assertEquals(1, result.candlesStored()); // only Monday is new
            assertEquals(1, source.calls.size());
            assertTrue(source.calls.get(0).startsWith("TCS:" + FRI.plusDays(1)),
                    "fetch must resume after last stored date: " + source.calls);
        }
    }

    @Test
    void staleSymbolIsReportedWhenNewestCandleLagsExpectedDate() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            new InstrumentRepository(conn).upsertAll(
                    List.of(new InstrumentRow("TCS", 1L, "NSE", "TCS")), MON);
            FakeSource source = new FakeSource();
            source.add("TCS", FRI, 100); // Monday's bar never arrives

            CandleDownloader.Result result =
                    downloader(conn, source, FRI).downloadAll(List.of("TCS"), MON);

            assertEquals(1, result.stale().size());
            assertTrue(result.stale().get(0).contains("TCS"));
            assertTrue(!result.clean());
        }
    }

    @Test
    void missingInstrumentTokenIsReportedNotFetched() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            FakeSource source = new FakeSource();
            CandleDownloader.Result result =
                    downloader(conn, source, FRI).downloadAll(List.of("NOSUCH"), MON);

            assertEquals(List.of("NOSUCH"), result.missingToken());
            assertTrue(source.calls.isEmpty());
        }
    }

    @Test
    void upToDateSymbolMakesNoFetchCall() throws Exception {
        try (Connection conn = Database.open(":memory:")) {
            new InstrumentRepository(conn).upsertAll(
                    List.of(new InstrumentRow("TCS", 1L, "NSE", "TCS")), MON);
            new CandleRepository(conn).upsertAll(
                    List.of(new Candle("TCS", MON, 101, 101, 101, 101, 1000)));

            FakeSource source = new FakeSource();
            CandleDownloader.Result result =
                    downloader(conn, source, FRI).downloadAll(List.of("TCS"), MON);

            assertTrue(source.calls.isEmpty());
            assertTrue(result.clean());
        }
    }

    // ---------- pure helpers ----------

    @Test
    void chunksSplitInclusiveRanges() {
        var chunks = CandleDownloader.chunks(
                LocalDate.of(2015, 1, 1), LocalDate.of(2015, 1, 10), 4);
        assertEquals(3, chunks.size());
        assertEquals(LocalDate.of(2015, 1, 1), chunks.get(0).from());
        assertEquals(LocalDate.of(2015, 1, 4), chunks.get(0).to());
        assertEquals(LocalDate.of(2015, 1, 9), chunks.get(2).from());
        assertEquals(LocalDate.of(2015, 1, 10), chunks.get(2).to());
    }

    @Test
    void expectedTradingDateWeekdayRules() {
        // Monday 19:00 IST → Monday (EOD ready)
        assertEquals(MON, CandleDownloader.expectedTradingDate(
                ZonedDateTime.of(MON.atTime(19, 0), IST)));
        // Monday 09:00 IST → previous Friday (today's EOD not out yet)
        assertEquals(FRI, CandleDownloader.expectedTradingDate(
                ZonedDateTime.of(MON.atTime(9, 0), IST)));
        // Sunday → Friday
        assertEquals(FRI, CandleDownloader.expectedTradingDate(
                ZonedDateTime.of(MON.minusDays(1).atTime(12, 0), IST)));
        // Saturday → Friday
        assertEquals(FRI, CandleDownloader.expectedTradingDate(
                ZonedDateTime.of(MON.minusDays(2).atTime(12, 0), IST)));
    }

    @Test
    void discontinuityDetectorFlagsBigMovesOnly() {
        List<Candle> series = List.of(
                new Candle("X", LocalDate.of(2026, 1, 1), 100, 100, 100, 100, 1),
                new Candle("X", LocalDate.of(2026, 1, 2), 105, 105, 105, 105, 1),  // +5%
                new Candle("X", LocalDate.of(2026, 1, 3), 50, 50, 50, 52.5, 1));   // -50%
        List<String> flags = CandleDownloader.findDiscontinuities(series, 0.20);
        assertEquals(1, flags.size());
        assertTrue(flags.get(0).contains("2026-01-03"));
        assertTrue(flags.get(0).contains("-50.0%"));
    }
}
