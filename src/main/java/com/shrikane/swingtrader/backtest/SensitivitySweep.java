package com.shrikane.swingtrader.backtest;

import com.shrikane.swingtrader.data.Candle;
import com.shrikane.swingtrader.risk.RiskManager;
import com.shrikane.swingtrader.signal.Strategy;
import com.shrikane.swingtrader.signal.strategies.BreakoutStrategy;
import com.shrikane.swingtrader.signal.strategies.PullbackStrategy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parameter sensitivity analysis (blueprint §6.5): run the SAME backtest over
 * a grid of parameter combinations and lay the results side by side.
 *
 * How to read it: a real edge is a PLATEAU — neighbouring parameter values
 * produce similar (positive) expectancy. If only one exact combination makes
 * money and its neighbours lose, that combination is curve-fit noise, not an
 * edge. The sweep exists to make that impossible to miss.
 *
 * Run this on the IN-SAMPLE window only (design on 2015–2021; blueprint §6.4).
 * Every sweep you run against the out-of-sample years silently converts them
 * into in-sample data — you get very few honest shots at OOS.
 */
public final class SensitivitySweep {

    /** One grid cell: the strategy variant and its full backtest stats. */
    public record Row(String label, boolean isDefault, BacktestStats stats) {}

    private SensitivitySweep() {}

    /** The sweepable strategy kinds and their grids. */
    public static List<Strategy> grid(String kind) {
        List<Strategy> combos = new ArrayList<>();
        switch (kind) {
            case "pullback" -> {
                for (double rsi : new double[]{5, 10, 15})
                    for (int hold : new int[]{5, 7, 10})
                        for (double atr : new double[]{1.0, 1.5, 2.0})
                            combos.add(new PullbackStrategy(rsi, hold, atr));
            }
            case "breakout" -> {
                for (int days : new int[]{40, 50, 60})
                    for (double vol : new double[]{1.25, 1.5, 2.0})
                        for (double trail : new double[]{2.0, 2.5, 3.0})
                            combos.add(new BreakoutStrategy(days, vol, trail, 10));
            }
            default -> throw new IllegalArgumentException(
                    "Unknown strategy kind: " + kind + " (use pullback or breakout)");
        }
        return combos;
    }

    /** Runs the full grid; rows come back sorted by expectancy, best first. */
    public static List<Row> run(String kind, Map<String, List<Candle>> candles,
                                LocalDate start, LocalDate end, double startingCapital) {
        List<Row> rows = new ArrayList<>();
        for (Strategy strategy : grid(kind)) {
            Backtester backtester = new Backtester(
                    strategy, new RiskManager(), new CostModel(), startingCapital);
            Backtester.Result result = backtester.run(candles, start, end);
            boolean isDefault = strategy.name().endsWith("-v1");
            rows.add(new Row(strategy.name(), isDefault, BacktestStats.from(result)));
        }
        rows.sort(Comparator.comparingDouble((Row r) -> r.stats().expectancy()).reversed());
        return rows;
    }

    /** Self-contained HTML report of the grid, best rows first. */
    public static String renderHtml(String kind, List<Row> rows,
                                    LocalDate start, LocalDate end) {
        long positive = rows.stream().filter(r -> r.stats().expectancy() > 0).count();
        StringBuilder html = new StringBuilder(1 << 14);
        html.append("""
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Sensitivity sweep — %s</title>
            <style>
              .viz-root { --surface-1:#fcfcfb; --page:#f9f9f7; --ink-1:#0b0b0b; --ink-2:#52514e;
                          --ink-3:#898781; --grid:#e1e0d9; --good:#006300; --bad:#d03b3b;
                          --accent:#2a78d6; --border:rgba(11,11,11,0.10); }
              @media (prefers-color-scheme: dark) {
                .viz-root { --surface-1:#1a1a19; --page:#0d0d0d; --ink-1:#ffffff; --ink-2:#c3c2b7;
                            --ink-3:#898781; --grid:#2c2c2a; --good:#0ca30c; --bad:#e66767;
                            --accent:#3987e5; --border:rgba(255,255,255,0.10); } }
              body { margin:0; font-family: system-ui, -apple-system, "Segoe UI", sans-serif; }
              .viz-root { background:var(--page); color:var(--ink-1); min-height:100vh;
                          padding:24px; box-sizing:border-box; }
              .wrap { max-width:1020px; margin:0 auto; }
              h1 { font-size:20px; margin:0 0 4px; }
              .sub { color:var(--ink-2); font-size:13px; margin-bottom:16px; }
              .howto { font-size:12.5px; color:var(--ink-2); border:1px solid var(--border);
                       border-left:3px solid var(--accent); background:var(--surface-1);
                       padding:10px 14px; border-radius:6px; margin-bottom:18px; }
              table { border-collapse:collapse; width:100%%; font-size:12.5px;
                      background:var(--surface-1); border:1px solid var(--border); }
              th, td { padding:6px 10px; text-align:right; font-variant-numeric:tabular-nums; }
              th { color:var(--ink-2); font-weight:600; border-bottom:1px solid var(--grid);
                   position:sticky; top:0; background:var(--surface-1); }
              td:first-child, th:first-child { text-align:left; }
              tr.default td { outline:2px solid var(--accent); outline-offset:-2px; }
              .up { color:var(--good); } .down { color:var(--bad); }
              .foot { color:var(--ink-3); font-size:12px; margin-top:18px; }
            </style></head><body><div class="viz-root"><div class="wrap">
            """.formatted(esc(kind)));
        html.append("<h1>Sensitivity sweep — ").append(esc(kind)).append("</h1>\n")
            .append("<div class=\"sub\">").append(start).append(" → ").append(end)
            .append(" · ").append(rows.size()).append(" parameter combinations · ")
            .append(positive).append(" with positive expectancy · v1 default outlined in blue</div>\n");
        html.append("<div class=\"howto\">How to read this: a real edge is a <b>plateau</b> — "
                + "many neighbouring rows positive together. One good row surrounded by losers "
                + "is curve-fit noise; do not pick it. Run sweeps on the in-sample window only "
                + "(blueprint §6.4–6.5).</div>\n");

        html.append("<table>\n<tr><th>Parameters</th><th>Trades</th><th>Expectancy</th>")
            .append("<th>per position</th><th>Win rate</th><th>Max DD</th><th>CAGR</th>")
            .append("<th>Both halves +</th></tr>\n");
        for (Row row : rows) {
            BacktestStats s = row.stats();
            String cls = s.expectancy() > 0 ? "up" : "down";
            html.append("<tr").append(row.isDefault() ? " class=\"default\"" : "").append(">")
                .append("<td>").append(esc(row.label())).append("</td>")
                .append("<td>").append(s.tradeCount()).append("</td>")
                .append("<td class=\"").append(cls).append("\">")
                .append(String.format(Locale.ROOT, "₹%,.0f", s.expectancy())).append("</td>")
                .append("<td class=\"").append(cls).append("\">")
                .append(String.format(Locale.ROOT, "%+.2f%%", s.expectancyPct() * 100)).append("</td>")
                .append("<td>").append(String.format(Locale.ROOT, "%.0f%%", s.winRate() * 100)).append("</td>")
                .append("<td>").append(String.format(Locale.ROOT, "-%.1f%%", s.maxDrawdown() * 100)).append("</td>")
                .append("<td>").append(String.format(Locale.ROOT, "%+.1f%%", s.cagr() * 100)).append("</td>")
                .append("<td>").append(s.meetsBothHalvesBar() ? "✓" : "—").append("</td>")
                .append("</tr>\n");
        }
        html.append("</table>\n<div class=\"foot\">Same Backtester, RiskManager and cost model "
                + "as the main report; only strategy parameters vary. Not investment advice."
                + "</div>\n</div></div></body></html>\n");
        return html.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
