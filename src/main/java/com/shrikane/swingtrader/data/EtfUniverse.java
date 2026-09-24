package com.shrikane.swingtrader.data;

import java.util.List;

/**
 * The frozen ETF mini-universe of the forward campaign
 * (docs/forward-campaign.md §4B). These six symbols are traded by the three
 * live sleeves (IMR-v1 and VRS-v1 use NIFTYBEES only; ROT-v1 ranks all six).
 *
 * Unlike the stock universe they are NOT membership-gated: an ETF doesn't
 * join or leave an index, so there is no survivorship bias to correct — the
 * list itself is the (pre-registered, permanent) universe. `download` fetches
 * them in addition to every symbol ever in the NIFTY 50.
 *
 * This list is also how {@link com.shrikane.swingtrader.backtest.CostModel}
 * profiles are chosen: Kite's instruments dump files ETFs under
 * instrument_type "EQ" like ordinary shares, so the instruments table cannot
 * tell them apart — the frozen symbol list is the source of truth.
 */
public final class EtfUniverse {

    private EtfUniverse() {}

    /** The core instrument for IMR-v1 and VRS-v1. */
    public static final String NIFTYBEES = "NIFTYBEES";

    /** Frozen order as written in the pre-registration. Do not edit in flight. */
    public static final List<String> SYMBOLS = List.of(
            NIFTYBEES, "BANKBEES", "ITBEES", "PHARMABEES", "PSUBNKBEES", "AUTOBEES");

    public static boolean isEtf(String symbol) {
        return SYMBOLS.contains(symbol);
    }
}
