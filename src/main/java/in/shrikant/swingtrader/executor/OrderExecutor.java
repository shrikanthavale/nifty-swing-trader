package in.shrikant.swingtrader.executor;

/**
 * Places approved orders via Kite Connect (live) or writes them to the
 * journal only (paper mode). Paper mode is the default and MUST be the
 * default until Phase 4 (blueprint §8).
 *
 * TODO (Phase 3):
 *  - CNC limit orders at next-day open, limit = previous close ± 0.5% band.
 *  - Idempotency: tag every order (kite order tag) and reconcile against the
 *    order book BEFORE any retry — never double-order on a timeout
 *    (blueprint §9 "silent double-order").
 *  - Handle rejections (margin, symbol ban) and partial fills.
 *  - Daily access-token flow: Kite tokens expire every morning; an
 *    unauthenticated system must stand down safely and alert (§9).
 */
public class OrderExecutor {

    public enum Mode { PAPER, LIVE }

    private final Mode mode;

    public OrderExecutor(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }
}
