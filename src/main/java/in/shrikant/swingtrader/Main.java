package in.shrikant.swingtrader;

import in.shrikant.swingtrader.auth.KiteAuthenticator;
import in.shrikant.swingtrader.auth.TokenStore;
import in.shrikant.swingtrader.config.AppConfig;

import java.nio.file.Path;

/**
 * Entry point. Subcommands planned (blueprint §8):
 *   auth       — daily Kite login → access token for the day   (done)
 *   download   — pull latest EOD candles into the DB           (Phase 1)
 *   backtest   — run a strategy over a date range              (Phase 1)
 *   signals    — compute today's signals and print them        (Phase 2)
 *   paper      — full daily cycle, orders to journal only      (Phase 3)
 *   live       — real orders (only after Phase 4 gates pass)   (Phase 4)
 */
public class Main {

    private static final String DEFAULT_CONFIG = "config/config.properties";

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "help";
        switch (cmd) {
            case "auth" -> auth(args.length > 1 ? args[1] : DEFAULT_CONFIG);
            case "download", "backtest", "signals", "paper", "live" ->
                    System.out.println("'" + cmd + "' is not implemented yet — see docs/blueprint.md §8 roadmap.");
            default -> System.out.println("""
                    nifty-swing-trader — personal swing trading system (see docs/blueprint.md)
                    usage: java -jar nifty-swing-trader.jar <auth|download|backtest|signals|paper|live> [config-path]""");
        }
    }

    private static void auth(String configPath) throws Exception {
        AppConfig config = AppConfig.load(configPath);
        TokenStore tokenStore = new TokenStore(Path.of(config.tokenPath()));
        new KiteAuthenticator(config, tokenStore).authenticate();
    }
}
