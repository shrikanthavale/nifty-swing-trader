# Project context for Claude

This file carries the full context from the planning conversation (July 2026) so any new Claude session working in this repo can pick up exactly where we left off. **Read `docs/blueprint.md` before writing code — it is the source of truth for design decisions.**

## What this project is

A personal (not commercial) short-swing trading system for the Indian stock market, run by Shrikant with his own Zerodha account and under ₹1 lakh of capital. Java + Maven, Zerodha Kite Connect API, SQLite storage. Long-only CNC delivery trades in NIFTY 100 stocks, held 2–10 days, signals computed after market close, orders placed at next open. A handful of orders per week — far below SEBI's 10-orders-per-second retail algo threshold, so no exchange registration of the strategy is required.

## Decisions already made (do not relitigate without asking Shrikant)

1. **Swing first, intraday maybe later.** Chosen explicitly over intraday: cheaper (zero delivery brokerage), no real-time infrastructure race, easier to build and test.
2. **Capital: under ₹1 lakh** → 3–4 concurrent positions of ~₹20–25k, 1% risk per trade, 25% max position, 6% drawdown kill switch, 3% weekly-loss pause. Encoded in `RiskManager`.
3. **No profit guarantees.** The user originally wanted "profit every day"; we established that's impossible and the goal is a positive-expectancy system with honest backtesting. Never imply guaranteed returns.
4. **Golden rule:** the identical `Strategy` and `RiskManager` classes run in backtest and live. `MarketSnapshot` is cutoff-dated to make lookahead bias structurally impossible. Fills are simulated at *next-day open*, never signal-day close.
5. **Costs first:** every backtest subtracts the Zerodha CNC cost model (`CostModel`, verified against zerodha.com/charges July 2026: ~0.30% per round trip in charges, ~0.45–0.5% with slippage). Breakeven per trade ≈ 0.5%.
6. **Two candidate strategies** (blueprint §5): A = RSI(2) pullback in uptrend (implemented, unvalidated), B = 50-day breakout with volume confirmation (stub). Neither is validated yet — that's Phase 1/2 work.
7. **Acceptance bar before live** (blueprint §6): positive expectancy after costs, ≥150 trades in backtest, profitable in both halves of the data, survivable drawdown, parameter plateau, then 4–8 weeks paper trading, then half-size live.

## Key external facts (verified July 2026 — recheck if much time has passed)

- Kite Connect: order APIs free for personal use; **historical data + WebSocket needs the ₹500/month plan**. Access token expires daily (morning re-auth required; fully unattended token generation violates Zerodha ToS).
- Static IP required for API order placement (Zerodha, since Apr 2025); up to two IPs whitelistable in the developer console. Data endpoints work from any IP.
- SEBI retail algo framework fully mandatory from April 1, 2026; below 10 orders/second = regular API user.
- Official Java client: `com.zerodhatech.kiteconnect:kiteconnect` (github.com/zerodha/javakiteconnect).
- Zerodha CNC charges: 0 brokerage; STT 0.1% both sides; NSE txn 0.00307%; SEBI ₹10/crore; GST 18% on (txn+SEBI); stamp 0.015% buy; DP ₹15.34/scrip on sell.

## Current state / next steps (Phase 1, blueprint §8)

Done: blueprint, compiling skeleton, `CostModel` with passing unit tests, `Indicators` (SMA/RSI/ATR), `PullbackStrategy`, `RiskManager`, SQLite schema bootstrap, **Kite auth flow** (July 2026): `auth` package with `KiteAuthenticator` (daily login orchestration; reuses today's token only after verifying it via `getProfile()`), `RequestTokenListener` (one-shot JDK HttpServer on `http://127.0.0.1:{kite.redirect_port}/callback` auto-capturing the request token; manual-paste fallback), and `TokenStore` (persists the daily token to `config/access_token.properties`, gitignored; freshness = same IST calendar day + same api_key). Wired as the `auth` subcommand in `Main`. **Verified against the real Kite API on July 13, 2026** — Shrikant created a free *Personal* Kite app (order/session APIs, NO historical data; upgrade to the paid Connect plan when candle download starts) and the full login → capture → exchange flow works end-to-end. The app's Redirect URL is registered as `http://127.0.0.1:5000/callback` (matching `kite.redirect_port`). The pom uses maven-shade to build a fat runnable jar (a thin jar broke `java -jar` with NoClassDefFoundError).

Also done (July 2026): **instruments + universe pipeline.** `InstrumentSync` pulls Kite's NSE dump (cash-equity rows) into the `instruments` table via `InstrumentRepository` (symbol → instrument_token, needed by the historical API). `ConstituentsCsv` (quote-aware parser for NSE's ind_nifty100list.csv, rejects HTML error pages), `ConstituentsDownloader` (NSE archives URL with browser headers; manual-CSV fallback since NSE bot protection is moody), `ConstituentsRepository` (dated membership intervals [from_date, to_date), snapshot diff/apply, idempotent), `DbUniverse` implements `Universe.membersOn(date)`. Commands: `instruments`, `universe [csv-path]`. v1 seeds only the current list — history before the first snapshot has survivorship bias (documented, blueprint §6.3). Also fixed a `.gitignore` bug: bare `data/` was silently untracking `src/main/java/**/data/` — now `/data/`; the data package files needed a `git add` after this fix.

UI decision (July 2026): no web UI. Backtest reports = generated static HTML files with the equity curve; Phase 3 monitoring = Telegram/email push; a Javalin dashboard is a possible much-later add-on.

Also done (July 2026): **candle downloader.** `CandleRepository` (upsert, lastDateFor, cutoff-dated `candlesUpTo` ready for MarketSnapshot), `HistoricalSource` interface (fake in tests → downloader logic tested offline), `KiteHistoricalSource` (Kite impl; parses "+0530" timestamps; needs paid Connect plan), `CandleDownloader` (incremental per-symbol fetch resuming after last stored date, 1800-day chunks, 350ms throttle for Kite's 3 req/s limit, default start 2015-01-01, stale-data assertion — `download` command exits 2 and says DO NOT TRADE on staleness — and >20% close-to-close discontinuity flags for corporate-action review). `expectedTradingDate` is a weekday approximation: NSE holidays report as stale (safe but noisy) — trading calendar still a TODO.

Also done (July 2026): **backtester + HTML report.** `Backtester` replays the daily loop (signals at close D → fills at D+1 OPEN through `CostModel`; exits fill before entries; halted-symbol exits stay queued, halted entries drop; trading calendar = the data's own dates). `MarketSnapshot.ofPresorted` added (binary-search cutoff, no copying — semantics identical to `of`, tested). **Kill-switch decision (Shrikant, July 2026):** backtests simulate the manual review as a *cooling-off reset* — after a 6% drawdown firing, entries stay blocked 10 trading days, then the equity peak resets to current equity; `RiskManager` untouched (backtester only controls the equityPeak argument). Firings are counted and reported. `BacktestStats` (expectancy, win rate, maxDD, CAGR, profit factor, cost drag, half-split, acceptance-bar booleans), `HtmlReport` (self-contained static HTML: stat tiles, acceptance checklist, SVG equity+drawdown charts with crosshair tooltip, full trades table, light+dark). `backtest [start] [end]` command writes reports/backtest-*.html (gitignored). Verified end-to-end on synthetic GBM data: random walk + costs → negative expectancy, exactly as it should be.

Next, in order:
1. Upgrade the Kite app to the Connect plan (₹500/30 days), run `instruments` + `universe` + `download` for 2015→present real candles.
2. Run the real backtest of PullbackStrategy; report honestly even (especially) if it fails the acceptance bar.
3. Phase 2 begins: sensitivity sweep (parameter plateau), in/out-of-sample split, implement BreakoutStrategy.

## Conventions

- Java 21, Maven, no framework — plain Java, small dependencies.
- Strategies/risk = pure functions: no I/O, no clocks, no randomness inside them.
- Position.tradingDaysHeld is a calendar approximation — replace with an NSE trading calendar (known TODO).
- This is a **public repo**: never commit `config/config.properties`, tokens, or `.db` files (gitignored). Never put real credentials in code, tests, or docs.
- User context: Shrikant primarily codes in Java; explain trading-domain concepts when they come up, don't assume finance background.
