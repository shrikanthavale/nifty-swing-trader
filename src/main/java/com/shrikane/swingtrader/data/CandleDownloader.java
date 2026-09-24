package com.shrikane.swingtrader.data;

import com.shrikane.swingtrader.db.CandleRepository;
import com.shrikane.swingtrader.db.InstrumentRepository;

import java.io.IOException;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Incremental EOD candle downloader (blueprint §4 data layer, §9 failure
 * modes). For each symbol: resume from the day after the newest stored
 * candle (or {@link #DEFAULT_START} for a fresh DB), fetch daily bars in
 * chunks, persist, then run two honesty checks:
 *
 *  1. STALE DATA: every symbol's newest candle must equal the expected
 *     trading date. Signals computed on yesterday's data are the "stale data
 *     day" failure mode — the caller must treat staleness as "do not trade",
 *     never as a warning to scroll past. (Caveat: the expected date is a
 *     weekday approximation; on NSE holidays everything reports stale —
 *     annoying but safe. NSE trading calendar is a known TODO.)
 *
 *  2. DISCONTINUITY: >20% close-to-close overnight moves are flagged for
 *     manual review — usually a split/bonus Kite already adjusted (fine) or
 *     an unadjusted corporate action that would poison indicators (not fine).
 */
public final class CandleDownloader {

    /** Backtests want 2015→present (blueprint §5). */
    public static final LocalDate DEFAULT_START = LocalDate.of(2015, 1, 1);

    /** Kite allows ~2000 daily bars per historical request; stay under it. */
    static final int MAX_DAYS_PER_REQUEST = 1800;

    /** Kite historical API rate limit is 3 req/s; ~350ms spacing is polite. */
    private static final long THROTTLE_MILLIS = 350;

    private static final double DISCONTINUITY_THRESHOLD = 0.20;

    private final HistoricalSource source;
    private final InstrumentRepository instruments;
    private final CandleRepository candles;
    private final LocalDate defaultStart;
    private final long throttleMillis;

    public CandleDownloader(HistoricalSource source, InstrumentRepository instruments,
                            CandleRepository candles) {
        this(source, instruments, candles, DEFAULT_START, THROTTLE_MILLIS);
    }

    /** Full-control constructor (tests: zero throttle, custom start). */
    public CandleDownloader(HistoricalSource source, InstrumentRepository instruments,
                            CandleRepository candles, LocalDate defaultStart, long throttleMillis) {
        this.source = source;
        this.instruments = instruments;
        this.candles = candles;
        this.defaultStart = defaultStart;
        this.throttleMillis = throttleMillis;
    }

    /** Outcome of a download run — the caller decides how loudly to react. */
    public record Result(
            int symbolsProcessed,
            int candlesStored,
            List<String> missingToken,   // no instruments-table entry; run `instruments`
            List<String> stale,          // newest candle != expected date → DO NOT TRADE
            List<String> discontinuities // "SYMBOL date ±xx.x%" — review manually
    ) {
        public boolean clean() {
            return missingToken.isEmpty() && stale.isEmpty();
        }
    }

    /** Downloads all symbols up to {@code expectedDate} and runs the checks. */
    public Result downloadAll(List<String> symbols, LocalDate expectedDate)
            throws IOException, SQLException {
        List<String> missingToken = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        List<String> discontinuities = new ArrayList<>();
        int stored = 0;

        for (String symbol : symbols) {
            Optional<Long> token = instruments.tokenFor(symbol);
            if (token.isEmpty()) {
                missingToken.add(symbol);
                continue;
            }

            Optional<LocalDate> last = candles.lastDateFor(symbol);
            LocalDate from = last.map(d -> d.plusDays(1)).orElse(defaultStart);

            List<Candle> fetched = new ArrayList<>();
            if (!from.isAfter(expectedDate)) {
                for (DateRange chunk : chunks(from, expectedDate, MAX_DAYS_PER_REQUEST)) {
                    fetched.addAll(source.fetchDaily(symbol, token.get(), chunk.from(), chunk.to()));
                    throttle();
                }
                // defensive: never re-store bars we already have or future-dated junk
                List<Candle> fresh = fetched.stream()
                        .filter(c -> last.isEmpty() || c.date().isAfter(last.get()))
                        .filter(c -> !c.date().isAfter(expectedDate))
                        .toList();
                candles.upsertAll(fresh);
                stored += fresh.size();
                discontinuities.addAll(findDiscontinuities(fresh, DISCONTINUITY_THRESHOLD));
            }

            LocalDate newest = candles.lastDateFor(symbol).orElse(null);
            if (newest == null || !newest.equals(expectedDate)) {
                stale.add(symbol + (newest == null ? " (no data)" : " (newest " + newest + ")"));
            }
        }
        return new Result(symbols.size(), stored, missingToken, stale, discontinuities);
    }

    /**
     * Outcome of the ETF mini-universe download. {@code unavailable} ETFs have
     * no token, no data on Kite, or failed to fetch — warn loudly, but they
     * don't stop the run (ROT-v1 simply can't rank them). {@code stale} ETFs
     * HAVE data that just isn't today's — that is a DO-NOT-TRADE condition,
     * exactly as for stocks.
     */
    public record EtfResult(int candlesStored, List<String> unavailable,
                            List<String> stale, List<String> discontinuities) {}

    /**
     * Downloads the ETF mini-universe one symbol at a time, never throwing:
     * a missing or broken ETF must not take the stock download down with it.
     */
    public EtfResult downloadEtfs(List<String> etfs, LocalDate expectedDate) throws SQLException {
        int stored = 0;
        List<String> unavailable = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        List<String> discontinuities = new ArrayList<>();
        for (String etf : etfs) {
            Result r;
            try {
                r = downloadAll(List.of(etf), expectedDate);
            } catch (IOException | RuntimeException e) {
                unavailable.add(etf + " (fetch failed: " + e.getMessage() + ")");
                continue;
            }
            stored += r.candlesStored();
            discontinuities.addAll(r.discontinuities());
            if (!r.missingToken().isEmpty()) {
                unavailable.add(etf + " (no instrument token — run `instruments`)");
            } else if (candles.lastDateFor(etf).isEmpty()) {
                unavailable.add(etf + " (no data on Kite)");
            } else {
                stale.addAll(r.stale());
            }
        }
        return new EtfResult(stored, unavailable, stale, discontinuities);
    }

    // ---------- pure helpers (unit-tested without I/O) ----------

    /** Inclusive date range. */
    public record DateRange(LocalDate from, LocalDate to) {}

    /** Splits [from, to] into inclusive chunks of at most {@code maxDays} days. */
    static List<DateRange> chunks(LocalDate from, LocalDate to, int maxDays) {
        List<DateRange> ranges = new ArrayList<>();
        LocalDate start = from;
        while (!start.isAfter(to)) {
            LocalDate end = start.plusDays(maxDays - 1L);
            if (end.isAfter(to)) end = to;
            ranges.add(new DateRange(start, end));
            start = end.plusDays(1);
        }
        return ranges;
    }

    /**
     * The most recent NSE trading date whose EOD candle should exist, as a
     * weekday approximation: today after 18:00 IST on a weekday, otherwise
     * walk back to the previous weekday. Ignores NSE holidays (known TODO —
     * on a holiday every symbol reports stale, which is safe, just noisy).
     */
    public static LocalDate expectedTradingDate(ZonedDateTime nowIst) {
        LocalDate day = nowIst.toLocalDate();
        boolean eodReady = !nowIst.toLocalTime().isBefore(LocalTime.of(18, 0));
        if (isWeekday(day) && eodReady) return day;
        day = day.minusDays(1);
        while (!isWeekday(day)) day = day.minusDays(1);
        return day;
    }

    private static boolean isWeekday(LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /** Flags >threshold close-to-close moves within consecutive fetched bars. */
    static List<String> findDiscontinuities(List<Candle> ordered, double threshold) {
        List<String> flags = new ArrayList<>();
        for (int i = 1; i < ordered.size(); i++) {
            double previousClose = ordered.get(i - 1).close();
            if (previousClose <= 0) continue;
            double change = ordered.get(i).close() / previousClose - 1.0;
            if (Math.abs(change) > threshold) {
                flags.add(String.format(Locale.ROOT, "%s %s %+.1f%%",
                        ordered.get(i).symbol(), ordered.get(i).date(), change * 100));
            }
        }
        return flags;
    }

    private void throttle() {
        if (throttleMillis <= 0) return;
        try {
            Thread.sleep(throttleMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
