package com.shrikane.swingtrader.executor;

import java.io.IOException;
import java.util.Set;

/**
 * The two broker calls the live executor needs. An interface so the
 * executor's safety logic (idempotent tags, reconcile-before-retry, exposure
 * caps) is unit-tested against a fake — tests never touch the real Kite API.
 */
public interface BrokerGateway {

    /** Tags of every order in today's order book (AMOs included). */
    Set<String> todaysOrderTags() throws IOException;

    /**
     * Places an after-market (AMO) MARKET order, CNC product, NSE, with market
     * protection −1 (auto) — it fills at the next session's open.
     *
     * @param side "BUY" or "SELL"
     * @return the broker's order id
     */
    String placeAmoMarketCnc(String symbol, String side, int quantity, String tag) throws IOException;
}
