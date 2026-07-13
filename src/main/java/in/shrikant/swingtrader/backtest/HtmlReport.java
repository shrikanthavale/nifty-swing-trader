package in.shrikant.swingtrader.backtest;

import in.shrikant.swingtrader.backtest.Backtester.EquityPoint;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Renders a backtest result as a single self-contained static HTML file —
 * the project's "UI" (decision: no web app; reports are files you open in a
 * browser). Inline SVG charts, no external assets.
 *
 * Chart conventions follow the dataviz method: single-series charts carry no
 * legend (the title names them), 2px line with a ~10% area wash, hairline
 * gridlines, text in ink tokens (never the series color), clean axis ticks,
 * a crosshair tooltip on the equity chart, and the full trades table as the
 * accessible data view. Light and dark palettes are both defined.
 */
public final class HtmlReport {

    private HtmlReport() {}

    private static final int W = 960, H = 300;
    private static final int ML = 64, MR = 16, MT = 16, MB = 28; // margins

    public static String render(Backtester.Result result, BacktestStats stats) {
        List<EquityPoint> curve = result.equityCurve();
        StringBuilder html = new StringBuilder(1 << 16);
        html.append("""
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Backtest — %s</title>
            <style>
              .viz-root {
                --surface-1: #fcfcfb; --page: #f9f9f7;
                --ink-1: #0b0b0b; --ink-2: #52514e; --ink-3: #898781;
                --grid: #e1e0d9; --axis: #c3c2b7;
                --series-1: #2a78d6; --series-dd: #e34948;
                --good: #006300; --bad: #d03b3b;
                --border: rgba(11,11,11,0.10);
              }
              @media (prefers-color-scheme: dark) {
                .viz-root {
                  --surface-1: #1a1a19; --page: #0d0d0d;
                  --ink-1: #ffffff; --ink-2: #c3c2b7; --ink-3: #898781;
                  --grid: #2c2c2a; --axis: #383835;
                  --series-1: #3987e5; --series-dd: #e66767;
                  --good: #0ca30c; --bad: #e66767;
                  --border: rgba(255,255,255,0.10);
                }
              }
              body { margin: 0; font-family: system-ui, -apple-system, "Segoe UI", sans-serif; }
              .viz-root { background: var(--page); color: var(--ink-1); min-height: 100vh;
                          padding: 24px; box-sizing: border-box; }
              .wrap { max-width: 1020px; margin: 0 auto; }
              h1 { font-size: 20px; margin: 0 0 4px; }
              h2 { font-size: 14px; font-weight: 600; margin: 28px 0 8px; color: var(--ink-1); }
              .sub { color: var(--ink-2); font-size: 13px; margin-bottom: 20px; }
              .honesty { font-size: 12.5px; color: var(--ink-2); border: 1px solid var(--border);
                         border-left: 3px solid var(--series-1); background: var(--surface-1);
                         padding: 10px 14px; border-radius: 6px; margin-bottom: 20px; }
              .tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
                       gap: 10px; }
              .tile { background: var(--surface-1); border: 1px solid var(--border);
                      border-radius: 8px; padding: 12px 14px; }
              .tile .label { font-size: 12px; color: var(--ink-2); margin-bottom: 4px; }
              .tile .value { font-size: 21px; font-weight: 600; }
              .tile .delta { font-size: 12px; margin-top: 2px; }
              .up { color: var(--good); } .down { color: var(--bad); }
              .chart-card { background: var(--surface-1); border: 1px solid var(--border);
                            border-radius: 8px; padding: 14px; position: relative; }
              svg { display: block; width: 100%%; height: auto; }
              .bar-list { list-style: none; padding: 0; margin: 0; font-size: 13.5px; }
              .bar-list li { padding: 6px 2px; display: flex; gap: 8px; align-items: baseline; }
              .bar-list .mark { font-weight: 700; width: 1.2em; text-align: center; }
              table { border-collapse: collapse; width: 100%%; font-size: 12.5px;
                      background: var(--surface-1); border: 1px solid var(--border);
                      border-radius: 8px; }
              th, td { padding: 6px 10px; text-align: right;
                       font-variant-numeric: tabular-nums; }
              th { color: var(--ink-2); font-weight: 600; border-bottom: 1px solid var(--grid); }
              td:first-child, th:first-child { text-align: left; }
              tr:nth-child(even) td { background: rgba(137,135,129,0.06); }
              .tooltip { position: absolute; pointer-events: none; display: none;
                         background: var(--surface-1); border: 1px solid var(--border);
                         border-radius: 6px; padding: 6px 9px; font-size: 12px;
                         color: var(--ink-1); box-shadow: 0 2px 8px rgba(0,0,0,0.12);
                         white-space: nowrap; }
              .foot { color: var(--ink-3); font-size: 12px; margin-top: 24px; }
            </style></head>
            <body><div class="viz-root"><div class="wrap">
            """.formatted(esc(result.strategyName())));

        html.append("<h1>Backtest report — ").append(esc(result.strategyName())).append("</h1>\n");
        html.append("<div class=\"sub\">").append(result.start()).append(" → ")
            .append(result.end()).append(" · NIFTY 100 universe · starting capital ")
            .append(inr(result.startingCapital()))
            .append(" · Zerodha CNC costs + slippage included · fills at next-day open</div>\n");
        html.append("<div class=\"honesty\">Costs are included and fills are next-day open — "
                + "but this is still a backtest: survivorship bias before the first constituents "
                + "snapshot, no circuit-limit or liquidity modelling, and the out-of-sample rules "
                + "of blueprint §6 apply. A good-looking curve here is a reason to paper trade, "
                + "not to go live.</div>\n");

        appendTiles(html, result, stats);
        appendAcceptanceBar(html, stats);

        html.append("<h2>Equity curve</h2>\n<div class=\"chart-card\" id=\"eq-card\">\n");
        appendEquitySvg(html, curve, result.startingCapital());
        html.append("<div class=\"tooltip\" id=\"eq-tip\"></div></div>\n");

        html.append("<h2>Drawdown</h2>\n<div class=\"chart-card\">\n");
        appendDrawdownSvg(html, curve);
        html.append("</div>\n");

        appendTrades(html, result.trades());

        html.append("<div class=\"foot\">Generated by nifty-swing-trader · ")
            .append(result.trades().size()).append(" completed trades · ")
            .append(result.openAtEnd().size())
            .append(" position(s) still open at end (marked to market in final equity).");
        if (!result.killSwitchFirings().isEmpty()) {
            html.append(" Kill switch fired on: ").append(esc(String.valueOf(result.killSwitchFirings())))
                .append(" (entries paused ").append(Backtester.COOLING_OFF_DAYS)
                .append(" trading days each, then peak reset — the simulated manual review).");
        }
        html.append(" Not investment advice.</div>\n");

        appendTooltipScript(html, curve);
        html.append("</div></div></body></html>\n");
        return html.toString();
    }

    // ---------- stat tiles ----------

    private static void appendTiles(StringBuilder html, Backtester.Result result,
                                    BacktestStats stats) {
        html.append("<div class=\"tiles\">\n");
        tile(html, "Final equity", inr(result.finalEquity()),
                pct(stats.totalReturn()) + " total", stats.totalReturn() >= 0);
        tile(html, "CAGR", pct(stats.cagr()), null, stats.cagr() >= 0);
        tile(html, "Max drawdown", pct(-stats.maxDrawdown()), null, false);
        tile(html, "Trades", String.valueOf(stats.tradeCount()),
                "target ≥ " + BacktestStats.MIN_TRADES, stats.meetsTradeCountBar());
        tile(html, "Win rate", pct(stats.winRate()), null, true);
        tile(html, "Expectancy / trade", inr(stats.expectancy()),
                pct(stats.expectancyPct()) + " of position", stats.expectancy() > 0);
        tile(html, "Avg win / avg loss", inr(stats.avgWin()) + " / " + inr(stats.avgLoss()),
                "profit factor " + two(stats.profitFactor()), stats.profitFactor() >= 1);
        tile(html, "Cost drag", inr(stats.totalCharges()),
                "charges only; slippage is in fills", false);
        tile(html, "Kill-switch firings", String.valueOf(result.killSwitchFirings().size()),
                result.killSwitchFirings().isEmpty() ? "never hit −6% from peak"
                        : Backtester.COOLING_OFF_DAYS + "-day cooling-off each",
                result.killSwitchFirings().isEmpty());
        html.append("</div>\n");
    }

    private static void tile(StringBuilder html, String label, String value,
                             String delta, boolean up) {
        html.append("<div class=\"tile\"><div class=\"label\">").append(esc(label))
            .append("</div><div class=\"value\">").append(esc(value)).append("</div>");
        if (delta != null) {
            html.append("<div class=\"delta ").append(up ? "up" : "down").append("\">")
                .append(esc(delta)).append("</div>");
        }
        html.append("</div>\n");
    }

    private static void appendAcceptanceBar(StringBuilder html, BacktestStats stats) {
        html.append("<h2>Acceptance bar (blueprint §6) — before anything trades live</h2>\n")
            .append("<ul class=\"bar-list\">\n");
        barItem(html, stats.meetsExpectancyBar(),
                "Positive expectancy after costs: " + inr(stats.expectancy()) + " per trade");
        barItem(html, stats.meetsTradeCountBar(),
                "Sample size ≥ " + BacktestStats.MIN_TRADES + " trades: " + stats.tradeCount());
        barItem(html, stats.meetsBothHalvesBar(),
                "Profitable in both halves: " + inr(stats.firstHalfPnl()) + " / "
                        + inr(stats.secondHalfPnl()));
        html.append("<li><span class=\"mark\">?</span> Max drawdown "
                + "survivable (judgment call): ").append(pct(-stats.maxDrawdown()))
            .append(" — if this number would make you turn the system off, it failed.</li>\n");
        html.append("<li><span class=\"mark\">?</span> Parameter plateau: run the "
                + "sensitivity sweep (Phase 2) before trusting this configuration.</li>\n");
        html.append("</ul>\n");
    }

    private static void barItem(StringBuilder html, boolean pass, String text) {
        html.append("<li><span class=\"mark ").append(pass ? "up" : "down").append("\">")
            .append(pass ? "✓" : "✗").append("</span> ").append(esc(text)).append("</li>\n");
    }

    // ---------- charts ----------

    private static void appendEquitySvg(StringBuilder html, List<EquityPoint> curve,
                                        double startingCapital) {
        if (curve.isEmpty()) { html.append("<p>No equity points.</p>"); return; }
        double min = startingCapital, max = startingCapital;
        for (EquityPoint p : curve) { min = Math.min(min, p.equity()); max = Math.max(max, p.equity()); }
        double[] ticks = niceTicks(min, max, 5);
        min = ticks[0]; max = ticks[ticks.length - 1];

        html.append("<svg id=\"eq-svg\" viewBox=\"0 0 ").append(W).append(' ').append(H)
            .append("\" role=\"img\" aria-label=\"Equity curve\">\n");
        appendGridAndYAxis(html, ticks, min, max);
        appendYearTicks(html, curve.get(0).date(), curve.get(curve.size() - 1).date());

        StringBuilder line = new StringBuilder();
        StringBuilder area = new StringBuilder();
        for (int i = 0; i < curve.size(); i++) {
            double x = x(i, curve.size());
            double y = y(curve.get(i).equity(), min, max);
            line.append(i == 0 ? "M" : "L").append(one(x)).append(' ').append(one(y));
            area.append(i == 0 ? "M" : "L").append(one(x)).append(' ').append(one(y));
        }
        area.append("L").append(one(x(curve.size() - 1, curve.size()))).append(' ').append(H - MB)
            .append("L").append(ML).append(' ').append(H - MB).append("Z");
        html.append("<path d=\"").append(area)
            .append("\" fill=\"var(--series-1)\" fill-opacity=\"0.1\"/>\n");
        html.append("<path d=\"").append(line)
            .append("\" fill=\"none\" stroke=\"var(--series-1)\" stroke-width=\"2\" ")
            .append("stroke-linejoin=\"round\" stroke-linecap=\"round\"/>\n");
        // crosshair (driven by script)
        html.append("<line id=\"eq-cross\" x1=\"0\" x2=\"0\" y1=\"").append(MT)
            .append("\" y2=\"").append(H - MB)
            .append("\" stroke=\"var(--axis)\" stroke-width=\"1\" visibility=\"hidden\"/>\n");
        html.append("<circle id=\"eq-dot\" r=\"4\" fill=\"var(--series-1)\" ")
            .append("stroke=\"var(--surface-1)\" stroke-width=\"2\" visibility=\"hidden\"/>\n");
        html.append("</svg>\n");
    }

    private static void appendDrawdownSvg(StringBuilder html, List<EquityPoint> curve) {
        if (curve.isEmpty()) { html.append("<p>No equity points.</p>"); return; }
        double peak = Double.NEGATIVE_INFINITY;
        double[] dd = new double[curve.size()];
        double worst = 0;
        for (int i = 0; i < curve.size(); i++) {
            peak = Math.max(peak, curve.get(i).equity());
            dd[i] = peak > 0 ? (curve.get(i).equity() - peak) / peak : 0; // <= 0
            worst = Math.min(worst, dd[i]);
        }
        double min = Math.min(worst * 1.1, -0.01), max = 0;
        double[] ticks = niceTicks(min, max, 4);
        min = ticks[0];

        html.append("<svg viewBox=\"0 0 ").append(W).append(' ').append(H / 2 + MB)
            .append("\" role=\"img\" aria-label=\"Drawdown\">\n");
        int h = H / 2 + MB;
        for (double t : ticks) {
            double yy = MT + (max - t) / (max - min) * (h - MT - MB);
            html.append("<line x1=\"").append(ML).append("\" x2=\"").append(W - MR)
                .append("\" y1=\"").append(one(yy)).append("\" y2=\"").append(one(yy))
                .append("\" stroke=\"var(--grid)\" stroke-width=\"1\"/>\n");
            html.append("<text x=\"").append(ML - 8).append("\" y=\"").append(one(yy + 4))
                .append("\" text-anchor=\"end\" font-size=\"11\" fill=\"var(--ink-3)\" ")
                .append("font-variant-numeric=\"tabular-nums\">")
                .append(String.format(Locale.ROOT, "%.0f%%", t * 100)).append("</text>\n");
        }
        StringBuilder area = new StringBuilder();
        for (int i = 0; i < dd.length; i++) {
            double x = x(i, dd.length);
            double yy = MT + (max - dd[i]) / (max - min) * (h - MT - MB);
            area.append(i == 0 ? "M" : "L").append(one(x)).append(' ').append(one(yy));
        }
        String top = one(MT);
        html.append("<path d=\"").append(area)
            .append("L").append(one(x(dd.length - 1, dd.length))).append(' ').append(top)
            .append("L").append(ML).append(' ').append(top).append("Z")
            .append("\" fill=\"var(--series-dd)\" fill-opacity=\"0.1\"/>\n");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < dd.length; i++) {
            double x = x(i, dd.length);
            double yy = MT + (max - dd[i]) / (max - min) * (h - MT - MB);
            line.append(i == 0 ? "M" : "L").append(one(x)).append(' ').append(one(yy));
        }
        html.append("<path d=\"").append(line)
            .append("\" fill=\"none\" stroke=\"var(--series-dd)\" stroke-width=\"2\" ")
            .append("stroke-linejoin=\"round\" stroke-linecap=\"round\"/>\n</svg>\n");
    }

    private static void appendGridAndYAxis(StringBuilder html, double[] ticks,
                                           double min, double max) {
        for (double t : ticks) {
            double yy = y(t, min, max);
            html.append("<line x1=\"").append(ML).append("\" x2=\"").append(W - MR)
                .append("\" y1=\"").append(one(yy)).append("\" y2=\"").append(one(yy))
                .append("\" stroke=\"var(--grid)\" stroke-width=\"1\"/>\n");
            html.append("<text x=\"").append(ML - 8).append("\" y=\"").append(one(yy + 4))
                .append("\" text-anchor=\"end\" font-size=\"11\" fill=\"var(--ink-3)\" ")
                .append("font-variant-numeric=\"tabular-nums\">").append(compactInr(t))
                .append("</text>\n");
        }
    }

    private static void appendYearTicks(StringBuilder html, LocalDate first, LocalDate last) {
        long span = java.time.temporal.ChronoUnit.DAYS.between(first, last);
        if (span <= 0) return;
        for (int year = first.getYear() + 1; year <= last.getYear(); year++) {
            LocalDate jan1 = LocalDate.of(year, 1, 1);
            double frac = (double) java.time.temporal.ChronoUnit.DAYS.between(first, jan1) / span;
            double xx = ML + frac * (W - ML - MR);
            html.append("<text x=\"").append(one(xx)).append("\" y=\"").append(H - 8)
                .append("\" text-anchor=\"middle\" font-size=\"11\" fill=\"var(--ink-3)\" ")
                .append("font-variant-numeric=\"tabular-nums\">").append(year).append("</text>\n");
        }
        html.append("<line x1=\"").append(ML).append("\" x2=\"").append(W - MR)
            .append("\" y1=\"").append(H - MB).append("\" y2=\"").append(H - MB)
            .append("\" stroke=\"var(--axis)\" stroke-width=\"1\"/>\n");
    }

    // ---------- trades table ----------

    private static void appendTrades(StringBuilder html, List<Trade> trades) {
        html.append("<h2>Trades (").append(trades.size()).append(")</h2>\n<table>\n")
            .append("<tr><th>Symbol</th><th>Qty</th><th>Entry</th><th>@</th>")
            .append("<th>Exit</th><th>@</th><th>Days</th><th>Charges</th>")
            .append("<th>Net P&amp;L</th><th>Return</th></tr>\n");
        for (Trade t : trades) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(t.entryDate(), t.exitDate());
            html.append("<tr><td>").append(esc(t.symbol()))
                .append("</td><td>").append(t.quantity())
                .append("</td><td>").append(t.entryDate())
                .append("</td><td>").append(two(t.entryFill()))
                .append("</td><td>").append(t.exitDate())
                .append("</td><td>").append(two(t.exitFill()))
                .append("</td><td>").append(days)
                .append("</td><td>").append(two(t.entryCharges() + t.exitCharges()))
                .append("</td><td class=\"").append(t.isWin() ? "up" : "down").append("\">")
                .append(inr(t.netPnl()))
                .append("</td><td class=\"").append(t.isWin() ? "up" : "down").append("\">")
                .append(pct(t.returnFraction())).append("</td></tr>\n");
        }
        html.append("</table>\n");
    }

    // ---------- crosshair tooltip ----------

    private static void appendTooltipScript(StringBuilder html, List<EquityPoint> curve) {
        html.append("<script>\nconst EQ = [");
        for (int i = 0; i < curve.size(); i++) {
            if (i > 0) html.append(',');
            html.append("[\"").append(curve.get(i).date()).append("\",")
                .append(one(curve.get(i).equity())).append(']');
        }
        html.append("];\n").append("""
            const svg = document.getElementById('eq-svg');
            if (svg && EQ.length > 1) {
              const card = document.getElementById('eq-card');
              const tip = document.getElementById('eq-tip');
              const cross = document.getElementById('eq-cross');
              const dot = document.getElementById('eq-dot');
              const ML=%d, MR=%d, MT=%d, MB=%d, W=%d, H=%d;
              let mn=Infinity, mx=-Infinity;
              for (const p of EQ) { mn=Math.min(mn,p[1]); mx=Math.max(mx,p[1]); }
              const T = %s; mn=T[0]; mx=T[T.length-1];
              const xAt = i => ML + i/(EQ.length-1)*(W-ML-MR);
              const yAt = v => MT + (mx-v)/(mx-mn)*(H-MT-MB);
              svg.addEventListener('mousemove', ev => {
                const r = svg.getBoundingClientRect();
                const px = (ev.clientX-r.left)*(W/r.width);
                let i = Math.round((px-ML)/(W-ML-MR)*(EQ.length-1));
                i = Math.max(0, Math.min(EQ.length-1, i));
                const cx = xAt(i), cy = yAt(EQ[i][1]);
                cross.setAttribute('x1',cx); cross.setAttribute('x2',cx);
                cross.setAttribute('visibility','visible');
                dot.setAttribute('cx',cx); dot.setAttribute('cy',cy);
                dot.setAttribute('visibility','visible');
                tip.style.display='block';
                tip.innerHTML = EQ[i][0]+' &middot; &#8377;'+EQ[i][1].toLocaleString('en-IN',{maximumFractionDigits:0});
                const cr = card.getBoundingClientRect();
                let tx = (cx/W)*r.width + (r.left-cr.left) + 12;
                if (tx + tip.offsetWidth > cr.width - 8) tx -= tip.offsetWidth + 24;
                tip.style.left = tx+'px';
                tip.style.top = ((cy/H)*r.height + (r.top-cr.top) - 30)+'px';
              });
              svg.addEventListener('mouseleave', () => {
                tip.style.display='none';
                cross.setAttribute('visibility','hidden');
                dot.setAttribute('visibility','hidden');
              });
            }
            </script>
            """.formatted(ML, MR, MT, MB, W, H, jsTicksArray(curve)));
    }

    private static String jsTicksArray(List<EquityPoint> curve) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (EquityPoint p : curve) { min = Math.min(min, p.equity()); max = Math.max(max, p.equity()); }
        double[] ticks = niceTicks(min, max, 5);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ticks.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(one(ticks[i]));
        }
        return sb.append(']').toString();
    }

    // ---------- geometry & formatting helpers ----------

    private static double x(int i, int n) {
        return n <= 1 ? ML : ML + (double) i / (n - 1) * (W - ML - MR);
    }

    private static double y(double v, double min, double max) {
        if (max <= min) return H - MB;
        return MT + (max - v) / (max - min) * (H - MT - MB);
    }

    /** Clean round tick values spanning [min, max]. Pure, unit-tested. */
    static double[] niceTicks(double min, double max, int target) {
        if (max <= min) max = min + 1;
        double rawStep = (max - min) / Math.max(1, target);
        double mag = Math.pow(10, Math.floor(Math.log10(rawStep)));
        double step = mag;
        for (double m : new double[]{1, 2, 2.5, 5, 10}) {
            if (mag * m >= rawStep) { step = mag * m; break; }
        }
        double start = Math.floor(min / step) * step;
        double end = Math.ceil(max / step) * step;
        int n = (int) Math.round((end - start) / step) + 1;
        double[] ticks = new double[n];
        for (int i = 0; i < n; i++) ticks[i] = start + i * step;
        return ticks;
    }

    private static String inr(double v) {
        return "₹" + String.format(Locale.ROOT, "%,.0f", v);
    }

    private static String compactInr(double v) {
        double abs = Math.abs(v);
        if (abs >= 1e7) return String.format(Locale.ROOT, "₹%.2fCr", v / 1e7);
        if (abs >= 1e5) return String.format(Locale.ROOT, "₹%.2fL", v / 1e5);
        if (abs >= 1e3) return String.format(Locale.ROOT, "₹%.0fK", v / 1e3);
        return String.format(Locale.ROOT, "₹%.0f", v);
    }

    private static String pct(double fraction) {
        return String.format(Locale.ROOT, "%+.1f%%", fraction * 100);
    }

    private static String two(double v) {
        return Double.isInfinite(v) ? "∞" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String one(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
