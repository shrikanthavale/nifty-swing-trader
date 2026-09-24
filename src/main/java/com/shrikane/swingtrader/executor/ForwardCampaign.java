package com.shrikane.swingtrader.executor;

import com.shrikane.swingtrader.backtest.CostModel;
import com.shrikane.swingtrader.config.AppConfig;
import com.shrikane.swingtrader.journal.SqliteJournal;
import com.shrikane.swingtrader.risk.RiskManager;
import com.shrikane.swingtrader.signal.Strategy;
import com.shrikane.swingtrader.signal.strategies.BreakoutStrategy;
import com.shrikane.swingtrader.signal.strategies.IndexMeanReversionStrategy;
import com.shrikane.swingtrader.signal.strategies.SectorRotationStrategy;
import com.shrikane.swingtrader.signal.strategies.VolRegimeStrategy;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The forward campaign's sleeve line-up (forward-campaign.md §3), in one
 * place: three funded ETF sleeves on the A1 sleeve risk profile and the ETF
 * cost model, plus the unfunded breakout paper shadow.
 */
public final class ForwardCampaign {

    private ForwardCampaign() {}

    public static final String IMR = "imr";
    public static final String ROT = "rot";
    public static final String VRS = "vrs";
    public static final String BREAKOUT_SHADOW = "breakout-shadow";

    /** The shadow's virtual account (§3) — never real money. */
    public static final double SHADOW_CAPITAL = 50_000;

    /**
     * The breakout configuration that sat its out-of-sample exam, as frozen
     * in §3: 50-day high, volume 1.5×, ATR-trail 3.0, hold 10, no breadth
     * filter; membership-gated NIFTY 50. Not the CLI's `breakout2`.
     */
    public static Strategy breakoutShadowStrategy() {
        return new BreakoutStrategy(50, 1.5, 3.0, 10);
    }

    /** Sleeve capital by id, for the funded sleeves only. */
    public static Map<String, Double> fundedCapital(AppConfig config) {
        return Map.of(IMR, config.sleeveImr(), ROT, config.sleeveRot(), VRS, config.sleeveVrs());
    }

    /**
     * @param membership dated NIFTY 50 membership for the breakout shadow's
     *                   entries (null = ungated); the ETF sleeves are never gated
     */
    public static SleeveCycle cycle(Connection conn, AppConfig config,
                                    Function<LocalDate, Set<String>> membership) {
        List<SleeveCycle.Sleeve> sleeves = List.of(
                funded(conn, IMR, new IndexMeanReversionStrategy(), config.sleeveImr()),
                funded(conn, ROT, new SectorRotationStrategy(), config.sleeveRot()),
                funded(conn, VRS, new VolRegimeStrategy(), config.sleeveVrs()),
                SleeveCycle.Sleeve.of(BREAKOUT_SHADOW, breakoutShadowStrategy(), new RiskManager(),
                        new CostModel(), new SqliteJournal(conn, BREAKOUT_SHADOW), SHADOW_CAPITAL,
                        membership, false));
        return new SleeveCycle(sleeves, new SqliteJournal(conn, SleeveCycle.TOTAL), config.capitalTotal());
    }

    private static SleeveCycle.Sleeve funded(Connection conn, String id, Strategy strategy, double capital) {
        return SleeveCycle.Sleeve.of(id, strategy, RiskManager.sleeveProfile(), CostModel.etf(),
                new SqliteJournal(conn, id), capital, null, true);
    }
}
