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

Done: blueprint, compiling skeleton, `CostModel` with passing unit tests, `Indicators` (SMA/RSI/ATR), `PullbackStrategy`, `RiskManager`, SQLite schema bootstrap.

Next, in order:
1. Kite auth flow (`AppConfig` exists; add login URL + request-token exchange).
2. Instruments dump → `instruments` table; NIFTY 100 constituents → `constituents` table.
3. `CandleDownloader`: incremental EOD fetch with the stale-data assertion (blueprint §9).
4. `Backtester`: daily loop as specced in its javadoc, producing a report (equity curve, expectancy, max DD, cost drag).
5. Run PullbackStrategy 2015→present; report honestly even (especially) if it fails the acceptance bar.

## Conventions

- Java 21, Maven, no framework — plain Java, small dependencies.
- Strategies/risk = pure functions: no I/O, no clocks, no randomness inside them.
- Position.tradingDaysHeld is a calendar approximation — replace with an NSE trading calendar (known TODO).
- This is a **public repo**: never commit `config/config.properties`, tokens, or `.db` files (gitignored). Never put real credentials in code, tests, or docs.
- User context: Shrikant primarily codes in Java; explain trading-domain concepts when they come up, don't assume finance background.
