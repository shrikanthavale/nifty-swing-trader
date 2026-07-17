package in.shrikant.swingtrader;

import com.zerodhatech.kiteconnect.KiteConnect;
import in.shrikant.swingtrader.auth.KiteAuthenticator;
import in.shrikant.swingtrader.auth.TokenStore;
import in.shrikant.swingtrader.config.AppConfig;
import in.shrikant.swingtrader.data.CandleDownloader;
import in.shrikant.swingtrader.data.ConstituentsDownloader;
import in.shrikant.swingtrader.data.InstrumentSync;
import in.shrikant.swingtrader.data.KiteHistoricalSource;
import in.shrikant.swingtrader.db.CandleRepository;
import in.shrikant.swingtrader.db.ConstituentsRepository;
import in.shrikant.swingtrader.db.Database;
import in.shrikant.swingtrader.db.InstrumentRepository;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * Entry point. Subcommands planned (blueprint §8):
 *   auth        — daily Kite login → access token for the day    (done)
 *   instruments — Kite NSE dump → instruments table              (done)
 *   universe    — NIFTY 100 constituents → constituents table    (done)
 *   download    — pull latest EOD candles into the DB            (Phase 1)
 *   backtest    — run a strategy over a date range               (Phase 1)
 *   signals     — compute today's signals and print them         (Phase 2)
 *   paper       — full daily cycle, orders to journal only       (Phase 3)
 *   live        — real orders (only after Phase 4 gates pass)    (Phase 4)
 */
public class Main {

    private static final String DEFAULT_CONFIG = "config/config.properties";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "help";
        switch (cmd) {
            case "auth" -> auth(args.length > 1 ? args[1] : DEFAULT_CONFIG);
            case "instruments" -> instruments(args.length > 1 ? args[1] : DEFAULT_CONFIG);
            case "universe" -> universe(args.length > 1 ? args[1] : null);
            case "download" -> download(args.length > 1 ? args[1] : DEFAULT_CONFIG);
            case "backtest" -> backtest(strategyArg(args), dateArg(args, 1), dateArg(args, 2));
            case "sweep" -> sweep(strategyArg(args), dateArg(args, 1), dateArg(args, 2));
            case "signals", "paper", "live" ->
                    System.out.println("'" + cmd + "' is not implemented yet — see docs/blueprint.md §8 roadmap.");
            default -> System.out.println("""
                    nifty-swing-trader — personal swing trading system (see docs/blueprint.md)
                    usage: java -jar nifty-swing-trader.jar <command>
                      auth [config-path]         daily Kite login; saves today's access token
                      instruments [config-path]  sync Kite's NSE instruments dump into the DB
                      universe [csv-path]        refresh NIFTY 100 constituents (downloads from
                                                 NSE, or parses a manually saved
                                                 ind_nifty100list.csv if a path is given)
                      download [config-path]     incremental EOD candle fetch for the universe
                                                 (needs the paid Connect plan for historical data)
                      backtest [pullback|breakout] [start] [end]
                                                 run a strategy over stored candles and write
                                                 reports/backtest-*.html
                      sweep [pullback|breakout] [start] [end]
                                                 27-combination parameter sensitivity grid →
                                                 reports/sweep-*.html (in-sample window only!)
                      signals|paper|live         not implemented yet""");
        }
    }

    private static KiteAuthenticator authenticator(AppConfig config) {
        return new KiteAuthenticator(config, new TokenStore(Path.of(config.tokenPath())));
    }

    private static void auth(String configPath) throws Exception {
        authenticator(AppConfig.load(configPath)).authenticate();
    }

    private static void instruments(String configPath) throws Exception {
        AppConfig config = AppConfig.load(configPath);
        KiteConnect kite = authenticator(config).authenticate();
        try (Connection conn = Database.open(config.dbPath())) {
            InstrumentSync sync = new InstrumentSync(kite, new InstrumentRepository(conn));
            int stored = sync.sync(LocalDate.now(IST));
            System.out.println("Stored " + stored + " NSE equity instruments in " + config.dbPath());
        }
    }

    private static void download(String configPath) throws Exception {
        AppConfig config = AppConfig.load(configPath);
        KiteConnect kite = authenticator(config).authenticate();
        try (Connection conn = Database.open(config.dbPath())) {
            ConstituentsRepository constituents = new ConstituentsRepository(conn);
            LocalDate expected = CandleDownloader.expectedTradingDate(ZonedDateTime.now(IST));
            List<String> universe = constituents.membersOn(expected);
            if (universe.isEmpty()) {
                System.err.println("Universe is empty — run the `universe` command first.");
                System.exit(2);
            }

            CandleDownloader downloader = new CandleDownloader(
                    new KiteHistoricalSource(kite),
                    new InstrumentRepository(conn),
                    new CandleRepository(conn));
            System.out.println("Downloading EOD candles for " + universe.size()
                    + " symbols up to " + expected + " ...");
            CandleDownloader.Result result = downloader.downloadAll(universe, expected);

            System.out.println("Stored " + result.candlesStored() + " new candles across "
                    + result.symbolsProcessed() + " symbols.");
            if (!result.missingToken().isEmpty()) {
                System.err.println("WARNING: no instrument token for " + result.missingToken()
                        + " — run the `instruments` command.");
            }
            for (String flag : result.discontinuities()) {
                System.out.println("REVIEW: >20% overnight move: " + flag
                        + " (corporate action? verify candles are adjusted)");
            }
            if (!result.stale().isEmpty()) {
                System.err.println("STALE DATA: newest candle != " + expected + " for "
                        + result.stale().size() + " symbols: " + result.stale());
                System.err.println("DO NOT TRADE on this data. (If today is an NSE holiday,"
                        + " this is expected — holiday calendar is a known TODO.)");
                System.exit(2);
            }
        }
    }

    /** First non-date arg after the command, defaulting to "pullback". */
    private static String strategyArg(String[] args) {
        for (int i = 1; i < args.length; i++) {
            if (!args[i].matches("\\d{4}-\\d{2}-\\d{2}")) return args[i];
        }
        return "pullback";
    }

    /** The nth date-shaped arg after the command (1-based), or null. */
    private static LocalDate dateArg(String[] args, int nth) {
        int seen = 0;
        for (int i = 1; i < args.length; i++) {
            if (args[i].matches("\\d{4}-\\d{2}-\\d{2}") && ++seen == nth) {
                return LocalDate.parse(args[i]);
            }
        }
        return null;
    }

    private static in.shrikant.swingtrader.signal.Strategy strategyFor(String kind) {
        return switch (kind) {
            case "pullback" -> new in.shrikant.swingtrader.signal.strategies.PullbackStrategy();
            case "breakout" -> new in.shrikant.swingtrader.signal.strategies.BreakoutStrategy();
            default -> throw new IllegalArgumentException(
                    "Unknown strategy: " + kind + " (use pullback or breakout)");
        };
    }

    private record LoadedCandles(
            java.util.Map<String, List<in.shrikant.swingtrader.data.Candle>> candles,
            LocalDate start, LocalDate end) {}

    private static LoadedCandles loadCandles(AppConfig config, LocalDate startArg,
                                             LocalDate endArg) throws Exception {
        java.util.Map<String, List<in.shrikant.swingtrader.data.Candle>> candles;
        try (Connection conn = Database.open(config.dbPath())) {
            candles = new CandleRepository(conn).allCandles();
        }
        if (candles.isEmpty()) {
            System.err.println("No candles in " + config.dbPath()
                    + " — run `instruments`, `universe`, then `download` first.");
            System.exit(2);
        }
        LocalDate dataEnd = candles.values().stream()
                .map(list -> list.get(list.size() - 1).date())
                .max(LocalDate::compareTo).orElseThrow();
        LocalDate start = startArg != null ? startArg
                : in.shrikant.swingtrader.data.CandleDownloader.DEFAULT_START;
        LocalDate end = endArg != null ? endArg : dataEnd;
        return new LoadedCandles(candles, start, end);
    }

    private static void sweep(String kind, LocalDate startArg, LocalDate endArg) throws Exception {
        AppConfig config = AppConfig.load(DEFAULT_CONFIG);
        LoadedCandles data = loadCandles(config, startArg, endArg);
        System.out.println("Sweeping " + kind + " parameter grid "
                + data.start() + " → " + data.end()
                + " (27 backtests — a few minutes on full history) ...");
        var rows = in.shrikant.swingtrader.backtest.SensitivitySweep.run(
                kind, data.candles(), data.start(), data.end(), config.startingCapital());

        System.out.println("Top of the grid (by expectancy):");
        rows.stream().limit(5).forEach(r -> System.out.printf(java.util.Locale.ROOT,
                "  %-38s trades=%-4d expectancy=₹%,.0f maxDD=%.1f%%%n",
                r.label(), r.stats().tradeCount(), r.stats().expectancy(),
                r.stats().maxDrawdown() * 100));
        long positive = rows.stream().filter(r -> r.stats().expectancy() > 0).count();
        System.out.println(positive + "/" + rows.size()
                + " combinations have positive expectancy"
                + (positive <= 3 && positive > 0
                        ? " — WARNING: isolated winners smell like curve-fit." : "."));

        Path reportDir = Path.of("reports");
        java.nio.file.Files.createDirectories(reportDir);
        Path report = reportDir.resolve("sweep-" + kind + "-" + data.end() + ".html");
        java.nio.file.Files.writeString(report,
                in.shrikant.swingtrader.backtest.SensitivitySweep.renderHtml(
                        kind, rows, data.start(), data.end()));
        System.out.println("Sweep report written to " + report.toAbsolutePath());
    }

    private static void backtest(String kind, LocalDate startArg, LocalDate endArg) throws Exception {
        AppConfig config = AppConfig.load(DEFAULT_CONFIG);
        LoadedCandles data = loadCandles(config, startArg, endArg);
        var candles = data.candles();
        LocalDate start = data.start();
        LocalDate end = data.end();

        var strategy = strategyFor(kind);
        var backtester = new in.shrikant.swingtrader.backtest.Backtester(
                strategy, new in.shrikant.swingtrader.risk.RiskManager(),
                new in.shrikant.swingtrader.backtest.CostModel(), config.startingCapital());

        System.out.println("Backtesting " + strategy.name() + " " + start + " → " + end
                + " on " + candles.size() + " symbols ...");
        var result = backtester.run(candles, start, end);
        var stats = in.shrikant.swingtrader.backtest.BacktestStats.from(result);

        System.out.println(in.shrikant.swingtrader.backtest.Backtester.summaryLine(result));
        System.out.printf(java.util.Locale.ROOT,
                "trades=%d winRate=%.1f%% expectancy=₹%.0f/trade (%.2f%%) maxDD=%.1f%% "
                        + "CAGR=%.1f%% costDrag=₹%.0f halves=₹%.0f/₹%.0f%n",
                stats.tradeCount(), stats.winRate() * 100, stats.expectancy(),
                stats.expectancyPct() * 100, stats.maxDrawdown() * 100,
                stats.cagr() * 100, stats.totalCharges(),
                stats.firstHalfPnl(), stats.secondHalfPnl());
        System.out.println("Acceptance bar: expectancy "
                + (stats.meetsExpectancyBar() ? "PASS" : "FAIL")
                + " | trades>=150 " + (stats.meetsTradeCountBar() ? "PASS" : "FAIL")
                + " | both halves " + (stats.meetsBothHalvesBar() ? "PASS" : "FAIL"));

        Path reportDir = Path.of("reports");
        java.nio.file.Files.createDirectories(reportDir);
        Path report = reportDir.resolve("backtest-" + strategy.name() + "-" + end + ".html");
        java.nio.file.Files.writeString(report,
                in.shrikant.swingtrader.backtest.HtmlReport.render(result, stats));
        System.out.println("Report written to " + report.toAbsolutePath());
    }

    private static void universe(String csvPath) throws Exception {
        AppConfig config = AppConfig.load(DEFAULT_CONFIG);
        Set<String> symbols = csvPath != null
                ? ConstituentsDownloader.fromFile(Path.of(csvPath))
                : new ConstituentsDownloader().download();
        try (Connection conn = Database.open(config.dbPath())) {
            ConstituentsRepository repo = new ConstituentsRepository(conn);
            LocalDate today = LocalDate.now(IST);
            ConstituentsRepository.MembershipDiff diff = repo.applySnapshot(symbols, today);
            System.out.println("NIFTY 100 snapshot (" + symbols.size() + " symbols) applied as of "
                    + today + ": +" + diff.added().size() + " added, -"
                    + diff.removed().size() + " removed.");
            if (!diff.added().isEmpty())   System.out.println("  added:   " + diff.added());
            if (!diff.removed().isEmpty()) System.out.println("  removed: " + diff.removed());
        }
    }
}
