package in.shrikant.swingtrader;

/**
 * Entry point. Subcommands planned (blueprint §8):
 *   download   — pull latest EOD candles into the DB          (Phase 1)
 *   backtest   — run a strategy over a date range             (Phase 1)
 *   signals    — compute today's signals and print them       (Phase 2)
 *   paper      — full daily cycle, orders to journal only     (Phase 3)
 *   live       — real orders (only after Phase 4 gates pass)  (Phase 4)
 */
public class Main {
    public static void main(String[] args) {
        String cmd = args.length > 0 ? args[0] : "help";
        switch (cmd) {
            case "download", "backtest", "signals", "paper", "live" ->
                    System.out.println("'" + cmd + "' is not implemented yet — see docs/blueprint.md §8 roadmap.");
            default -> System.out.println("""
                    nifty-swing-trader — personal swing trading system (see docs/blueprint.md)
                    usage: java -jar nifty-swing-trader.jar <download|backtest|signals|paper|live>""");
        }
    }
}
