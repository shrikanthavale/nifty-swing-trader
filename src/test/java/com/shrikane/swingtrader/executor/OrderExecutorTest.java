package com.shrikane.swingtrader.executor;

import com.shrikane.swingtrader.executor.OrderExecutor.Limits;
import com.shrikane.swingtrader.executor.OrderExecutor.Mode;
import com.shrikane.swingtrader.executor.OrderExecutor.Outcome;
import com.shrikane.swingtrader.executor.SleeveCycle.QueuedOrder;
import com.shrikane.swingtrader.journal.LiveOrderLog;
import com.shrikane.swingtrader.signal.Signal;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The live executor's safety logic against a fake broker — never the real Kite API. */
class OrderExecutorTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 1);
    private static final Limits LIMITS = new Limits(50_000, Map.of("imr", 16_000.0, "vrs", 16_000.0));

    /** Scriptable fake: an order book of tags plus failure switches. */
    private static final class FakeBroker implements BrokerGateway {
        final Set<String> book = new HashSet<>();
        final List<String> placeCalls = new ArrayList<>();
        boolean bookUnreadable;
        boolean placeThrows;
        boolean landsDespiteError;

        @Override
        public Set<String> todaysOrderTags() throws IOException {
            if (bookUnreadable) throw new IOException("network down");
            return new HashSet<>(book);
        }

        @Override
        public String placeAmoMarketCnc(String symbol, String side, int quantity, String tag)
                throws IOException {
            placeCalls.add(side + " " + symbol + " x" + quantity + " " + tag);
            if (placeThrows) {
                if (landsDespiteError) book.add(tag);
                throw new IOException("read timed out");
            }
            book.add(tag);
            return "ORD" + placeCalls.size();
        }
    }

    private static QueuedOrder buy(String sleeve, int qty, double ref) {
        return new QueuedOrder(sleeve, 1, D, "NIFTYBEES", Signal.Action.ENTER, qty, ref);
    }

    private static QueuedOrder sell(String sleeve, int qty) {
        return new QueuedOrder(sleeve, 2, D, "NIFTYBEES", Signal.Action.EXIT, qty, 280);
    }

    private static final Map<String, Double> NOTHING_INVESTED = Map.of();

    @Test
    void dryRunJournalsTheExactOrderAndSendsNothing() {
        LiveOrderLog.InMemory log = new LiveOrderLog.InMemory();
        var results = new OrderExecutor(Mode.DRY_RUN, null, log, LIMITS)
                .submit(D, List.of(buy("imr", 57, 280)), NOTHING_INVESTED, false);
        assertEquals(Outcome.DRY_RUN, results.get(0).outcome());
        String note = results.get(0).note();
        assertTrue(note.contains("NIFTYBEES") && note.contains("AMO MARKET CNC BUY x57")
                && note.contains("market_protection=-1"), note);
        assertEquals(1, log.entries.size());
        assertEquals("DRY_RUN", log.entries.get(0).outcome());
    }

    @Test
    void placesAmoWithIdempotentTagAndPausesBetweenOrders() {
        FakeBroker broker = new FakeBroker();
        List<Long> pauses = new ArrayList<>();
        var executor = new OrderExecutor(Mode.LIVE, broker, new LiveOrderLog.InMemory(), LIMITS, pauses::add);
        var results = executor.submit(D, List.of(buy("imr", 20, 280), buy("vrs", 20, 280), sell("imr", 5)),
                NOTHING_INVESTED, false);
        assertTrue(results.stream().allMatch(r -> r.outcome() == Outcome.PLACED));
        assertTrue(broker.placeCalls.get(0).startsWith("SELL"), "exits go first");
        assertEquals(List.of(1000L, 1000L), pauses);
    }

    @Test
    void rerunSkipsOrdersWhoseTagIsAlreadyInTheOrderBook() {
        FakeBroker broker = new FakeBroker();
        var executor = new OrderExecutor(Mode.LIVE, broker, new LiveOrderLog.InMemory(), LIMITS, ms -> {});
        executor.submit(D, List.of(buy("imr", 57, 280)), NOTHING_INVESTED, false);
        var again = executor.submit(D, List.of(buy("imr", 57, 280)), NOTHING_INVESTED, false);
        assertEquals(Outcome.SKIPPED_DUPLICATE, again.get(0).outcome());
        assertEquals(1, broker.placeCalls.size(), "never double-order");
    }

    @Test
    void placementErrorIsReconciledNotRetried() {
        FakeBroker broker = new FakeBroker();
        broker.placeThrows = true;
        broker.landsDespiteError = true;                    // timeout, but Kite got it
        var executor = new OrderExecutor(Mode.LIVE, broker, new LiveOrderLog.InMemory(), LIMITS, ms -> {});
        var landed = executor.submit(D, List.of(buy("imr", 57, 280)), NOTHING_INVESTED, false);
        assertEquals(Outcome.PLACED, landed.get(0).outcome());
        assertEquals(1, broker.placeCalls.size());

        FakeBroker lost = new FakeBroker();
        lost.placeThrows = true;                            // genuinely rejected
        var failed = new OrderExecutor(Mode.LIVE, lost, new LiveOrderLog.InMemory(), LIMITS, ms -> {})
                .submit(D, List.of(buy("imr", 57, 280)), NOTHING_INVESTED, false);
        assertEquals(Outcome.FAILED, failed.get(0).outcome());
        assertTrue(failed.get(0).note().contains("NOT retried"));
        assertEquals(1, lost.placeCalls.size(), "exactly one attempt");
    }

    @Test
    void unreadableOrderBookMeansNothingIsPlaced() {
        FakeBroker broker = new FakeBroker();
        broker.bookUnreadable = true;
        var results = new OrderExecutor(Mode.LIVE, broker, new LiveOrderLog.InMemory(), LIMITS, ms -> {})
                .submit(D, List.of(sell("imr", 57), buy("vrs", 10, 280)), NOTHING_INVESTED, false);
        assertTrue(results.stream().allMatch(r -> r.outcome() == Outcome.FAILED));
        assertTrue(broker.placeCalls.isEmpty());
    }

    @Test
    void killSwitchRefusesEntriesButExitsStillGoOut() {
        FakeBroker broker = new FakeBroker();
        var results = new OrderExecutor(Mode.LIVE, broker, new LiveOrderLog.InMemory(), LIMITS, ms -> {})
                .submit(D, List.of(buy("vrs", 10, 280), sell("imr", 57)), NOTHING_INVESTED, true);
        assertEquals(Outcome.PLACED, results.get(0).outcome());       // the SELL, sorted first
        assertEquals(Outcome.REFUSED, results.get(1).outcome());
        assertEquals(1, broker.placeCalls.size());
    }

    @Test
    void refusesBuysAboveSleeveCapitalOrTotalCapitalOrForUnknownSleeves() {
        var executor = new OrderExecutor(Mode.DRY_RUN, null, new LiveOrderLog.InMemory(),
                new Limits(20_000, Map.of("imr", 16_000.0, "vrs", 16_000.0)));
        // imr already holds ₹10k: another ₹8.4k would breach its ₹16k
        var sleeveCap = executor.submit(D, List.of(buy("imr", 30, 280)), Map.of("imr", 10_000.0), false);
        assertEquals(Outcome.REFUSED, sleeveCap.get(0).outcome());
        assertTrue(sleeveCap.get(0).note().contains("capital"));
        // two sleeves each fine alone, but together past the ₹20k total
        var totalCap = executor.submit(D, List.of(buy("imr", 50, 280), buy("vrs", 50, 280)), NOTHING_INVESTED, false);
        assertEquals(Outcome.DRY_RUN, totalCap.get(0).outcome());
        assertEquals(Outcome.REFUSED, totalCap.get(1).outcome());
        assertTrue(totalCap.get(1).note().contains("capital.total"));
        var unknown = executor.submit(D, List.of(buy("breakout-shadow", 1, 280)), NOTHING_INVESTED, false);
        assertEquals(Outcome.REFUSED, unknown.get(0).outcome());
    }

    @Test
    void tagIsDeterministicTwentyAlphanumericCharsAndSleeveSpecific() {
        String t = OrderExecutor.tag(D, "imr", "NIFTYBEES", "BUY");
        assertEquals(20, t.length());
        assertTrue(t.matches("[A-Za-z0-9]{20}"));
        assertEquals(t, OrderExecutor.tag(D, "imr", "NIFTYBEES", "BUY"));
        assertNotEquals(t, OrderExecutor.tag(D, "vrs", "NIFTYBEES", "BUY"));
        assertNotEquals(t, OrderExecutor.tag(D.plusDays(1), "imr", "NIFTYBEES", "BUY"));
        assertNotEquals(t, OrderExecutor.tag(D, "imr", "NIFTYBEES", "SELL"));
    }
}
