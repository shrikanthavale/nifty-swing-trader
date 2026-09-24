package com.shrikane.swingtrader.executor;

import com.shrikane.swingtrader.executor.SleeveCycle.QueuedOrder;
import com.shrikane.swingtrader.journal.LiveOrderLog;
import com.shrikane.swingtrader.signal.Signal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 4: sends the funded sleeves' queued orders to Zerodha as CNC AMO
 * MARKET orders in the evening, so they fill at the next open — the same
 * fill assumption as the backtester and the paper ledger (blueprint §9,
 * forward-campaign.md §5).
 *
 * Safety, in the order it is applied:
 *  - DRY RUN is the default (config live.enabled=false): every order is
 *    journaled exactly as it WOULD be placed and nothing is sent.
 *  - Entries (BUY) are refused while the live account's kill switch or
 *    weekly-loss pause is active. Exits (SELL) always go out.
 *  - A BUY is refused if it would take its sleeve above the sleeve's capital
 *    or the funded total above capital.total (at the reference price).
 *  - Idempotency: every order carries a deterministic tag, hash of (date,
 *    sleeve, symbol, side). Today's order book is read BEFORE placing and
 *    any tag already present is skipped — re-running the same evening can
 *    never double-order. If the order book can't be read, nothing is placed.
 *  - Never blind-retry: when a placement call errors (timeout!), the order
 *    book is re-read; if the tag is there the order counts as placed,
 *    otherwise it is journaled FAILED and NOT retried.
 *  - 1 second between placements (we send ~0–4 orders a day; the SEBI
 *    retail threshold is 10 orders/second).
 */
public final class OrderExecutor {

    public enum Mode { DRY_RUN, LIVE }

    public enum Outcome { DRY_RUN, PLACED, SKIPPED_DUPLICATE, REFUSED, FAILED }

    /** Capital caps for the funded sleeves; unknown sleeves may not buy. */
    public record Limits(double totalCapital, Map<String, Double> sleeveCapital) {}

    public record Result(QueuedOrder order, String tag, Outcome outcome,
                         String brokerOrderId, String note) {}

    /** Kite accepts alphanumeric tags up to 20 characters. */
    static final int TAG_LENGTH = 20;
    static final long PAUSE_BETWEEN_ORDERS_MILLIS = 1000;

    interface Pause {
        void millis(long ms);
    }

    private final Mode mode;
    private final BrokerGateway broker;
    private final LiveOrderLog log;
    private final Limits limits;
    private final Pause pause;

    /** @param broker may be null in DRY_RUN mode (nothing is ever sent) */
    public OrderExecutor(Mode mode, BrokerGateway broker, LiveOrderLog log, Limits limits) {
        this(mode, broker, log, limits, ms -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    OrderExecutor(Mode mode, BrokerGateway broker, LiveOrderLog log, Limits limits, Pause pause) {
        if (mode == Mode.LIVE && broker == null) {
            throw new IllegalArgumentException("LIVE mode needs a broker gateway");
        }
        this.mode = mode;
        this.broker = broker;
        this.log = log;
        this.limits = limits;
        this.pause = pause;
    }

    public Mode mode() {
        return mode;
    }

    /**
     * Submits the orders (exits first). {@code investedAtCost} = current
     * funded positions per sleeve at entry cost — the base for the exposure
     * caps. Every outcome is written to the {@link LiveOrderLog}.
     */
    public List<Result> submit(LocalDate date, List<QueuedOrder> orders,
                               Map<String, Double> investedAtCost, boolean entriesBlocked) {
        List<QueuedOrder> ordered = new ArrayList<>(orders);
        ordered.sort(Comparator.comparing(o -> o.action() == Signal.Action.ENTER)); // exits first

        Map<String, Double> invested = new HashMap<>(investedAtCost);
        double[] totalInvested = {invested.values().stream().mapToDouble(Double::doubleValue).sum()};

        Set<String> bookTags = null;
        String bookError = null;
        if (mode == Mode.LIVE && !ordered.isEmpty()) {
            try {
                bookTags = new java.util.HashSet<>(broker.todaysOrderTags());
            } catch (Exception e) {
                bookError = e.getMessage();
            }
        }

        List<Result> results = new ArrayList<>();
        int placed = 0;
        for (QueuedOrder order : ordered) {
            String side = order.action() == Signal.Action.ENTER ? "BUY" : "SELL";
            String tag = tag(date, order.sleeve(), order.symbol(), side);
            Result result;

            String refusal = refusal(order, side, invested, totalInvested[0], entriesBlocked);
            if (refusal != null) {
                result = new Result(order, tag, Outcome.REFUSED, null, refusal);
            } else if (mode == Mode.DRY_RUN) {
                result = new Result(order, tag, Outcome.DRY_RUN, null, "would place: " + describe(order, side, tag));
            } else if (bookTags == null) {
                result = new Result(order, tag, Outcome.FAILED, null,
                        "order book unreadable (" + bookError + ") — refusing to place blind");
            } else if (bookTags.contains(tag)) {
                result = new Result(order, tag, Outcome.SKIPPED_DUPLICATE, null,
                        "tag already in today's order book — not placed again");
            } else {
                if (placed++ > 0) pause.millis(PAUSE_BETWEEN_ORDERS_MILLIS);
                result = place(order, side, tag, bookTags);
            }

            boolean holdsExposure = result.outcome() == Outcome.DRY_RUN
                    || result.outcome() == Outcome.PLACED
                    || result.outcome() == Outcome.SKIPPED_DUPLICATE;
            if (side.equals("BUY") && holdsExposure) {
                double cost = order.quantity() * order.refPrice();
                invested.merge(order.sleeve(), cost, Double::sum);
                totalInvested[0] += cost;
            }
            log.record(new LiveOrderLog.Entry(date, order.sleeve(), order.symbol(), side,
                    order.quantity(), order.refPrice(), tag, result.outcome().name(),
                    result.brokerOrderId(), order.ledgerOrderId(), result.note()));
            results.add(result);
        }
        return results;
    }

    private Result place(QueuedOrder order, String side, String tag, Set<String> bookTags) {
        try {
            String id = broker.placeAmoMarketCnc(order.symbol(), side, order.quantity(), tag);
            bookTags.add(tag);
            return new Result(order, tag, Outcome.PLACED, id, describe(order, side, tag));
        } catch (Exception e) {
            // reconcile before anything else: did it land despite the error?
            try {
                if (broker.todaysOrderTags().contains(tag)) {
                    bookTags.add(tag);
                    return new Result(order, tag, Outcome.PLACED, null,
                            "call errored (" + e.getMessage() + ") but the tag IS in the order book");
                }
                return new Result(order, tag, Outcome.FAILED, null,
                        "not placed, NOT retried: " + e.getMessage());
            } catch (Exception again) {
                return new Result(order, tag, Outcome.FAILED, null,
                        "UNKNOWN state — placement errored (" + e.getMessage() + ") and the order "
                                + "book is unreadable (" + again.getMessage() + "). Check Kite "
                                + "manually before re-running.");
            }
        }
    }

    private String refusal(QueuedOrder order, String side, Map<String, Double> invested,
                           double totalInvested, boolean entriesBlocked) {
        if (order.quantity() <= 0) return "non-positive quantity";
        if (!side.equals("BUY")) return null;                   // exits always go out
        if (entriesBlocked) return "kill switch / weekly-loss pause active — entries refused";
        Double capital = limits.sleeveCapital().get(order.sleeve());
        if (capital == null) return "unknown sleeve " + order.sleeve() + " — no capital limit configured";
        double cost = order.quantity() * order.refPrice();
        double sleeveAfter = invested.getOrDefault(order.sleeve(), 0.0) + cost;
        if (sleeveAfter > capital + 1e-6) {
            return String.format(Locale.ROOT, "would take sleeve %s to ₹%,.0f > capital ₹%,.0f",
                    order.sleeve(), sleeveAfter, capital);
        }
        if (totalInvested + cost > limits.totalCapital() + 1e-6) {
            return String.format(Locale.ROOT, "would take total exposure to ₹%,.0f > capital.total ₹%,.0f",
                    totalInvested + cost, limits.totalCapital());
        }
        return null;
    }

    private static String describe(QueuedOrder order, String side, String tag) {
        return String.format(Locale.ROOT,
                "NSE %s AMO MARKET CNC %s x%d (ref %.2f) market_protection=-1 tag=%s",
                order.symbol(), side, order.quantity(), order.refPrice(), tag);
    }

    /**
     * Deterministic idempotency tag: "ST" + the first 18 hex chars of
     * SHA-256("date|sleeve|symbol|side") — 20 alphanumeric chars, Kite's max.
     */
    static String tag(LocalDate date, String sleeve, String symbol, String side) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (date + "|" + sleeve + "|" + symbol + "|" + side).getBytes(StandardCharsets.UTF_8));
            return ("ST" + HexFormat.of().formatHex(hash)).substring(0, TAG_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
