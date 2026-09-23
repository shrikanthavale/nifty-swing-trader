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

Also done (July 2026): **Phase 2 tooling** (built before the real-data run, which Shrikant hasn't done yet). `BreakoutStrategy` fully implemented (N-day closing high + volume > mult × prior-20-day average + 200-SMA filter; trailing `trailAtr`×ATR(14) stop from highest close since entry; timeout; rank = volume surge). Both strategies now take constructor params (no-arg = v1 defaults, names stay `*-v1`; variants get descriptive names). `SensitivitySweep` runs a 27-combination grid per strategy through the identical Backtester and renders an HTML plateau table (sorted by expectancy, default outlined). New `Indicators`: `avgVolume`, `highestCloseSince`. CLI: `backtest [pullback|breakout] [start] [end]`, `sweep [pullback|breakout] [start] [end]`.

Also done (July 2026): **Phase 3 plumbing** (paper mode). `Journal` interface + `SqliteJournal` (signals incl. rejected with reasons, orders PENDING/FILLED/CANCELLED, equity_daily, paper_positions, meta; new tables in `Database`). `PaperTrader.runDaily` = one evening cycle: stale-data guard (aborts loudly, exit 2), fill queued orders at today's open (identical semantics/CostModel as Backtester — exits first, gap-up shrink, halted entry cancelled / halted exit stays pending), mark + persist equity, evaluate strategy, risk-approve, journal everything, queue tomorrow's orders, build summary text. Kill switch live-style: manual reset via `paper reset-peak` (meta marker date; peak computed since marker). Note the interplay: after a reset the 3% weekly-loss pause can STILL block entries until the week turns — intended. `TelegramNotifier` (optional `telegram.bot_token`/`telegram.chat_id` in config; no-op when blank; never throws). PaperTrader logic verified against `InMemoryJournal` (test sources); `SqliteJournal` covered by JUnit on real in-memory SQLite. CLI: `paper [pullback|breakout]`, `paper reset-peak`.

Next, in order:
1. **Real data (blocking everything):** upgrade the Kite app to the Connect plan (₹500/30 days), run `instruments` + `universe` + `download` for 2015→present, then `mvn test` (never yet run on Shrikant's machine for backtest/Phase-2/Phase-3 code — sandbox couldn't run Maven).
2. In-sample discipline (blueprint §6.4): design/sweep on 2015→2021 only (`backtest pullback 2015-01-01 2021-12-31`, `sweep pullback 2015-01-01 2021-12-31`); keep 2022→present untouched for ONE final out-of-sample run per surviving strategy.
3. Pick a survivor (or iterate on filters, not parameters); write the one-page rule spec (Phase 2 milestone).
4. Start the paper loop: each evening `auth` → `download` → `paper` (schedule via Windows Task Scheduler; a scheduler script is still TODO). Milestone: 4+ weeks of paper trades matching the backtester on the same days.
5. Remaining build TODOs: live `OrderExecutor` (Phase 4 — idempotent Kite orders, reconcile-before-retry), NSE holiday calendar, strategy-decay monitor (rolling 30-trade expectancy vs backtest).

## Conventions

- Java 21, Maven, no framework — plain Java, small dependencies.
- Strategies/risk = pure functions: no I/O, no clocks, no randomness inside them.
- Position.tradingDaysHeld is a calendar approximation — replace with an NSE trading calendar (known TODO).
- This is a **public repo**: never commit `config/config.properties`, tokens, or `.db` files (gitignored). Never put real credentials in code, tests, or docs.
- User context: Shrikant primarily codes in Java; explain trading-domain concepts when they come up, don't assume finance background.

## First real backtests (Sept 19, 2026 — in-sample 2015→2021, real Kite data)

Data: 264k candles, 100 symbols, 2015→2026-09-18 downloaded (Connect plan active since Sept 2026; manual credit top-up, no auto-renew). Discontinuity flags all verified as real events (COVID 2020-03-23, PSU recap 2017-10-25, Adani/Hindenburg, 2024 election day) or demergers (ADANIENT 2015, SIEMENS 2025, VEDL 2026 — known un-adjustable caveat, bias is against the strategy).

- **pullback-v1: FAIL.** −6.6% total, expectancy −₹7/trade, 1050 trades, win rate 61%, PF 0.98, maxDD −32%, cost drag ₹62k (!), 15 kill-switch firings, both halves negative. Death by costs: too many shallow dips.
- **breakout-v1: partial.** +24.8% (CAGR 3.2%), expectancy +₹50/trade, 489 trades, PF 1.16, maxDD −21.9%. FAILS both-halves (−₹3.5k / +₹28k) — profits concentrated in 2019–21; possible bull-market dependence.
- **pullback sweep: 9/27 positive, and it's a PLATEAU — the entire rsi<5 family tops the table (expectancy ₹11–32 across holds/stops) while rsi<10 and rsi<15 lose.** Lesson: at our cost level, fewer/deeper dips is the game.

**Phase 2 iteration (built Sept 19): market-breadth regime filter.** `Indicators.breadthAboveSma(snapshot, 200)` = fraction of universe above own 200-SMA. Both strategies gained a `marketBreadthMin` param (0 = off; exits NEVER gated). CLI: `pullback2` = PullbackStrategy(rsi<5, hold7, atr1.5, breadth≥50%), `breakout2` = BreakoutStrategy(50d, 1.5x, 2.5atr, 10d, breadth≥50%). New sweep grid `pullback2`: rsi {4,5,6} × hold {5,7,10} × breadth {40,50,60%}. Run configs 10–12. Results pending — Shrikant runs them. 2022+ remains SEALED for the one OOS shot.

## THE OOS EXAM — FIRED AND FAILED (Sept 19, 2026)

Shrikant authorized the one out-of-sample run. pullback2 champion (rsi<5, hold10, atr1.5, b60%) on SEALED 2022-01-01→2026-09-18: **−16.1% total (CAGR −3.7%), expectancy −₹35.7/trade, PF 0.87, 451 trades, maxDD −19.1%, both halves negative, 8 kill-switch firings.** In-sample it was +37% with a 15/15-green plateau — the edge did not survive unseen data.

**2022–2026 is now BURNED as out-of-sample for the pullback family.** Any future pullback variant judged on it is in-sample by definition. Honest validation from here = forward data only (paper trading) or a future re-download extending past Sept 2026.

Post-mortem hypotheses (unproven, in order of suspicion): (1) survivorship-bias asymmetry — constituents were seeded from TODAY's list, so 2015–21 backtests bought 'today's winners' during the very years that made them winners, while 2022–26 is barely flattered; the in-sample edge may have been largely this artifact. (2) Iterative in-sample mining — v1→sweep→v2→probe was 4 rounds of selection on the same 7 years; plateaus reduce but don't eliminate overfit. (3) Regime: 2022 rate shock + 2024–26 chop genuinely differ from 2015–21.

Next steps decided by this result: fix the data before fixing the strategy — reconstruct DATED NIFTY 100 membership from NSE's public index-change announcements (kills hypothesis 1 properly), then restart strategy research on honest universes. No strategy goes to paper trading on the current evidence.

## Survivorship-bias fix: dated NIFTY 50 membership (Sept 23, 2026)

Universe pivoted NIFTY 100 → **NIFTY 50 with true dated membership**. Source: marketcalls.in reconstruction xlsx (22 semi-annual snapshots 2016-03→2026-08 + change log, all NSE-press-release-sourced). Claude validated it by replaying the change log from the 2016-03 baseline — reproduces all 21 later snapshots exactly. `datasets/nifty50_membership.csv` = 78 intervals / 77 symbols, mapped to current Kite tickers (INFRATEL→INDUSTOWER, ZOMATO→ETERNAL, TATAMOTORS→TMPV, IBULHSGFIN→SAMMAANCAP, LTIM→LTM); TATAMTRDVR excluded; includes announced WIPRO→BSE change effective 2026-09-30. Known gap: HDFC (delisted 2023 merger — no Kite candles 2016-23).

Machinery: `MembershipTable` (pure, parseCsv + membersOn), `universe history [csv]` command (wipes + loads constituents), `ConstituentsRepository.replaceAllIntervals/loadMembership`, `MarketSnapshot.ofPresorted(map, asOf, eligible)` — `symbols()` = eligible ∩ has-data while `candles()` stays open so exits work after a symbol leaves the index; `Backtester`/`SensitivitySweep`/`PaperTrader` take an optional membership function (Main wires it; loud UNGATED warning when absent). `download` now fetches every symbol EVER a member. Run config 13.

**Honest-backtest rules from here:** start 2016-04-01 or later (dataset floor); run `universe history` before backtests; expect ~77-symbol downloads (ex-members incl. YESBANK/ZEEL/etc.). All prior backtest numbers (Sept 19) are tainted by survivorship and superseded. NEXT: Shrikant runs auth + download (fetches ex-member candles), then rerun v1/v2 in-sample honestly.

## HONEST RESULTS — survivorship-corrected in-sample, 2016-04-01→2021-12-31 (Sept 23, 2026)

Run by Claude in-cloud on the gated NIFTY 50 universe (dated membership, ex-members' candles included; HDFC gap). Reports in reports/honest-*.html.

- **pullback-v1: −29.4%** (was −6.6% rigged). exp −₹34.9, PF 0.86, maxDD 36%. Ungated pullback sweep: **0/27 positive**. Dip-buying is dead at our cost level.
- **pullback2 champion (rsi<5,hold10,b60%): −2.1%** (was +37% rigged!). Its whole in-sample edge was survivorship bias — which is exactly why it failed OOS. Instrument now consistent. pullback2 sweep: 9/27, weak rsi<4 pocket, no plateau worth trusting.
- **breakout-v1: +35.6%**, exp ₹91/trade, PF 1.27, maxDD 15.7% — IMPROVED on honest data (momentum naturally avoids dying companies). Fails both-halves narrowly (−₹980 / +₹36.4k).
- **breakout sweep: 27/27 POSITIVE** with clean monotone structure (40d > 50d > 60d lookback; wider trail stops better). Three configs pass ALL bars; plateau-principled pick = **breakout(50d, vol1.5, atr3.0, hold10)**: exp ₹103, 382 trades, maxDD 8.3%, both halves +. One knob away from the pre-registered blueprint Strategy B.

**Strategic position: 2022–2026 was burned ONLY for the pullback family. The breakout family still holds its one sealed OOS exam.** Next decision (Shrikant's): fire breakout's one OOS shot on the chosen config, or go straight to paper trading with OOS kept sealed. No further in-sample tuning of breakout beyond this selection — every extra sweep mines the sample.

## BREAKOUT'S OOS EXAM — FIRED AND FAILED (Sept 23, 2026)

Shrikant authorized breakout's one shot. breakout(50d, vol1.5, atr3.0, hold10) on sealed 2022-01-01→2026-09-22, honest gated universe: **−27.2% (CAGR −6.5%), exp −₹82.4/trade, PF 0.70, win 42.1%, 330 trades, maxDD −30.4%, both halves negative, 10 kill-switch firings.** Report: reports/breakout-OOS-2022-2026.html.

**2022–2026 is now burned for BOTH strategy families.** With survivorship fixed, the failure reads as REGIME DEPENDENCE: 2016–21 held two huge momentum runs (2017, 2020–21); 2022–26's rate-shock chop whipsaws trend-followers. Key meta-lesson for this project: an honest in-sample plateau — even 27/27 — does not guarantee regime survival for price-only strategies.

**Campaign conclusion (Phase 2, honest):** two strategy families designed, sensitivity-swept, and examined out-of-sample on clean data; both failed their exams. Under our own gates, NOTHING proceeds to paper or live. Historical validation on this dataset is exhausted (2016–26 all seen). Honest options recorded for Shrikant: (a) pause the ₹500/mo data plan — the DB and machinery keep working offline; (b) any future strategy family faces validation on FORWARD data only (paper trading as the exam, months-long by nature); (c) the boring benchmark remains: index investing has beaten everything we tested. The system did its job: it priced these lessons at ₹1,000 of data fees instead of a slow bleed of real capital.
