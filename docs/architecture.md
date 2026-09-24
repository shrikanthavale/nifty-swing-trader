# Architecture — how the code maps to the blueprint

*Companion to [`blueprint.md`](blueprint.md) (the design) and [`../CLAUDE.md`](../CLAUDE.md)
(decisions + current status). This file: what each package does and how a trading
day flows through the classes. As of September 2026.*

## The one rule everything serves

**The identical `Strategy` and `RiskManager` classes run in backtests, paper
trading, and (later) live trading.** The backtester and the paper trader are two
different "clocks" driving the same brain. If you ever find yourself writing
strategy logic that only runs in one of them, stop — that's how backtests become
fiction (blueprint §4).

## Package map

```
com.shrikane.swingtrader
│
├── config      AppConfig — reads config/config.properties (keys, capital,
│               ports, Telegram). Never committed; example file is.
│
├── auth        The daily Kite login ritual (tokens expire every morning):
│               KiteAuthenticator      orchestrates login → token exchange
│               RequestTokenListener   one-shot local HTTP server that catches
│                                      Zerodha's redirect (auto-capture)
│               TokenStore             persists today's token; stale = not today
│
├── data        Market data in, honestly:
│               Candle                 one day's OHLCV bar (immutable record)
│               MarketSnapshot         history up to a cutoff date — the
│                                      anti-lookahead guarantee lives HERE
│               CandleDownloader       incremental EOD fetch + stale-data
│                                      assertion + >20% discontinuity flags
│               KiteHistoricalSource   Kite impl of HistoricalSource (the
│                                      interface exists so tests fake it)
│               ConstituentsCsv/-Downloader  NIFTY 100 membership from NSE
│               DbUniverse             "who was in the index on date d"
│               EtfUniverse            the forward campaign's frozen 6 ETFs
│                                      (not membership-gated)
│               InstrumentSync         Kite's symbol → instrument_token dump
│
├── signal      The brain (pure functions — no I/O, no clocks, no randomness):
│               Strategy               interface: (snapshot, portfolio) → signals
│               Signal                 ENTER/EXIT + reference price, stop, rank
│               Indicators             SMA, RSI, ATR, volume avg, highest close,
│                                      realized vol, median, total return
│               strategies/
│                 PullbackStrategy     A: RSI(2) dip in an uptrend  (falsified)
│                 BreakoutStrategy     B: 50-day high + volume surge (falsified;
│                                      runs as the unfunded paper shadow)
│                 IndexMeanReversion…  IMR-v1: RSI(2) dip on NIFTYBEES   (forward
│                 SectorRotation…      ROT-v1: monthly 63d ETF leader    campaign,
│                 VolRegime…           VRS-v1: in when vol20 < median   frozen)
│                 DisasterStop         shared entry − 2.5×ATR(14) stop
│
├── risk        The adult in the room (also pure):
│               RiskManager            sizes positions (1% risk, 25% cap, max 4),
│                                      weekly-loss pause, 6% kill switch;
│                                      sleeveProfile() = full-sleeve sizing (A1)
│               Portfolio, Position    account state
│
├── backtest    The measuring instrument:
│               Backtester             daily replay; fills at NEXT day's open
│               CostModel              Zerodha CNC charges + slippage — every
│                                      fill goes through it; etf() profile
│               BacktestStats          expectancy, drawdown, acceptance bar
│               HtmlReport             the report card (reports/*.html)
│               SensitivitySweep       27-combo parameter grid → plateau or spike?
│               Trade                  one completed round trip
│
├── journal     The black box recorder + paper account state:
│               Journal (interface) / SqliteJournal / (tests: InMemoryJournal)
│               every signal (incl. rejected + why), order lifecycle,
│               daily equity, open paper positions — one namespace per
│               SLEEVE (schema v1); LiveOrderLog = every live/dry-run order
│
├── executor    PaperTrader            one strategy's daily cycle, journal only
│               SleeveCycle            all sleeves in one evening; rails on the
│                                      total live account
│               ForwardCampaign        the sleeve line-up (IMR/ROT/VRS + shadow)
│               OrderExecutor          live Kite AMO orders: dry-run default,
│                                      idempotent tags, reconcile-before-retry
│               BrokerGateway / KiteBrokerGateway   the two Kite order calls
│
├── notify      TelegramNotifier       daily summary to your phone (optional)
│
└── db          Database (schema bootstrap) + repositories (candles,
                instruments, constituents) — SQLite, one file: swingtrader.db
```

## How one trading day flows (paper mode, the target daily routine)

```
morning (~8:30)      java -jar ... auth        → 30-second Kite login, token saved
evening (~18:30)     java -jar ... download    → today's candles; LOUD failure if stale
                     java -jar ... paper       → the cycle:
                        1. stale-data guard (aborts rather than trade on old data)
                        2. fill orders queued yesterday at TODAY's open (CostModel)
                        3. mark equity, save daily snapshot
                        4. Strategy.evaluate(snapshot up to today, portfolio)
                        5. RiskManager.approve → journal ALL signals, queue orders
                        6. summary → stdout + Telegram
```

The backtester runs steps 2–5 in a loop over history — same classes, simulated
clock, fills at next open, costs always on.

## Commands (all via `java -jar target/nifty-swing-trader-*.jar`)

| Command | What | Needs |
|---|---|---|
| `auth` | daily Kite login → token | free Personal app |
| `instruments` | Kite NSE dump → symbol/token map | free |
| `universe [csv]` | NIFTY 100 membership snapshot | free (NSE download) |
| `download` | incremental EOD candles | **₹500/mo Connect plan** |
| `backtest [pullback\|breakout] [start] [end]` | full backtest → reports/*.html | data |
| `sweep [pullback\|breakout] [start] [end]` | 27-combo sensitivity grid | data |
| `backtest [imr\|rot\|vrs] [start] [end]` | forward-campaign §7 sanity check (ETF universe, sleeve capital, ETF costs) — run ONCE | data |
| `paper [pullback\|breakout]` | one daily paper cycle | data |
| `paper reset-peak` | manual kill-switch re-enable | — |
| `cycle` | forward campaign evening run: IMR/ROT/VRS sleeves + breakout shadow, journal only | data |
| `live` | `cycle` + funded-sleeve orders through the executor (dry run unless `live.enabled=true`) | data; live: `auth` + static IP |
| `live reset-peak [sleeve]` | re-enable entries after kill-switch review (default: live account) | — |
| `signals` | not implemented | — |

## Where we are (Sept 2026)

Code complete through Phase 3 plumbing; **zero real market data has flowed yet**.
The gate to everything: Connect plan → `download` → first honest
`backtest`/`sweep` on 2015–2021 (keep 2022+ as the untouched out-of-sample).
Then 4–8 weeks paper, then half-size live. Full detail: CLAUDE.md "Next, in order".

## Known TODOs / honest limitations

- NSE holiday calendar: expected-trading-date is a weekday approximation, so on
  holidays `download`/`paper` report "stale" (safe, noisy). `Position.tradingDaysHeld`
  is a calendar approximation too.
- Survivorship bias: constituents history starts at the first snapshot you take;
  earlier backtest years use it flat (blueprint §6.3 says: demand extra margin).
- Live executor (Phase 4) not built: idempotent order tags, reconcile-before-retry,
  partial fills — deliberately last, after paper trading proves the plumbing.
- Backtests simulate the kill-switch manual review as a 10-day cooling-off +
  peak reset (decided July 2026); in paper/live the reset is truly manual.
