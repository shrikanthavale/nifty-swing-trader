package com.shrikane.swingtrader.executor;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Kite Connect implementation of {@link BrokerGateway}. Order placement
 * needs today's access token AND a request from the static IP registered in
 * the Kite developer console (Zerodha rule since Apr 2025) — from any other
 * IP Kite rejects the order, which the executor journals as FAILED.
 */
public final class KiteBrokerGateway implements BrokerGateway {

    /** Kite's "auto" market protection. 0 is rejected by the exchange — never send it. */
    static final double MARKET_PROTECTION_AUTO = -1;

    private final KiteConnect kite;

    public KiteBrokerGateway(KiteConnect kite) {
        this.kite = kite;
    }

    @Override
    public Set<String> todaysOrderTags() throws IOException {
        try {
            Set<String> tags = new HashSet<>();
            for (Order order : kite.getOrders()) {
                if (order.tag != null && !order.tag.isBlank()) tags.add(order.tag);
            }
            return tags;
        } catch (KiteException e) {
            throw new IOException("Kite order book fetch failed: " + e.message + " (code " + e.code + ")", e);
        }
    }

    @Override
    public String placeAmoMarketCnc(String symbol, String side, int quantity, String tag)
            throws IOException {
        OrderParams params = new OrderParams();
        params.exchange = Constants.EXCHANGE_NSE;
        params.tradingsymbol = symbol;
        params.transactionType = side.equals("BUY")
                ? Constants.TRANSACTION_TYPE_BUY : Constants.TRANSACTION_TYPE_SELL;
        params.quantity = quantity;
        params.product = Constants.PRODUCT_CNC;
        params.orderType = Constants.ORDER_TYPE_MARKET;
        params.validity = Constants.VALIDITY_DAY;
        params.tag = tag;
        params.marketProtection = MARKET_PROTECTION_AUTO;
        params.autoslice = false;
        try {
            return kite.placeOrder(params, Constants.VARIETY_AMO).orderId;
        } catch (KiteException e) {
            throw new IOException("Kite rejected order: " + e.message + " (code " + e.code + ")", e);
        }
    }
}
