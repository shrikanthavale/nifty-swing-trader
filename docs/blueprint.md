# Personal Swing-Trading System — Blueprint

**For:** Shrikant · Zerodha account · Java stack · starting capital under ₹1 lakh
**Style:** Short-swing (hold 2–10 days), long-only cash equity, signals computed after market close
**Purpose:** Personal use only — not a product, not for other users' money

> **Honesty box (read first):** This system cannot guarantee profit — daily, weekly, or ever. What it *can* do is execute a rules-based strategy without emotion, enforce risk limits ruthlessly, and give you honest statistics about whether your strategy has an edge. Most retail strategies don't survive contact with costs and out-of-sample data. The backtester exists to tell you that *before* the market does. Budget for the possibility that version 1, 2, and 3 of your strategy all fail backtesting — that is the system working, not failing.

---

## 1. Scope decisions (locked in)

| Decision | Choice | Why |
|---|---|---|
| Style | Short-swing, 2–10 day holds | Signals run after market close → no real-time infrastructure race, no HFT competition, delivery trades get zero brokerage on Zerodha |
| Direction | Long-only, cash (CNC) delivery | Under ₹1 lakh, shorting requires F&O or intraday margin — both add risk and complexity you don't need in v1 |
| Universe | NIFTY 100 constituents | Liquid, tight spreads, low slippage, no circuit-limit surprises. ~100 symbols is trivially scannable |
| Positions | 3–4 concurrent, ~₹20–25k each | Diversification without over-fragmenting small capital |
| Execution | Orders placed at next-day market open (or 9:20 AM after opening volatility) | Signal at close → order next morning. No intraday urgency |
| Frequency | A handful of orders per week | Far below SEBI's 10 orders/second algo threshold → you're a regular API user, minimal regulatory burden |

---

## 2. Regulatory and account setup

What applies to you under SEBI's retail algo framework (fully mandatory from April 1, 2026):

- **Below 10 orders/second you are a regular API user, not a registered algo.** A swing system placing 2–10 orders a week is nowhere near this. No exchange registration of your strategy is needed.
- **Static IP is required for order placement** via API (Zerodha rule since April 2025). You can whitelist up to two IPs in the Kite developer console. If you run the system from home, get a static IP from your ISP (usually ₹100–200/month extra) or run the executor on a small cloud VM (an AWS Lightsail / DigitalOcean box with a fixed IP, ~₹300–500/month). Note: only *order placement* needs the static IP — data endpoints work from anywhere.
- **Kite Connect subscription:** order placement APIs are free for personal use, but **live WebSocket streaming and historical candle data require the ₹500/month Connect plan**. For a swing system you need historical data (for signals and backtesting), so budget ₹500/month. This is your main running cost.
- Keep your own audit log of every order the system places (you'll want this anyway — see Journal module).

Setup checklist:
1. Create a Kite Connect app at developers.kite.trade (₹500/month plan).
2. Whitelist your static IP.
3. Note: Kite Connect uses a **daily login flow** — the access token expires every morning. Your system needs a small "authenticate at ~8:30 AM" step (semi-manual TOTP-assisted login is the common pattern; fully unattended token generation violates Zerodha's ToS, so plan a 30-second morning ritual or a TOTP-based helper you trigger yourself).

---

## 3. The cost model — build this in before anything else

Zerodha delivery (CNC) charges as of mid-2026: zero brokerage; STT 0.1% on buy and sell; NSE transaction charge 0.00307%; SEBI charges ₹10/crore; 18% GST on (transaction + SEBI charges); stamp duty 0.015% on buy; DP charge ₹15.34 per scrip on sell.

**Worked example — one round trip on a ₹20,000 position (bought 20,000, sold 20,200):**

| Component | Amount |
|---|---|
| STT (0.1% × both sides) | ₹40.20 |
| Exchange transaction charges | ₹1.23 |
| SEBI charges | ₹0.04 |
| GST | ₹0.23 |
| Stamp duty (buy side) | ₹3.00 |
| DP charge (sell side) | ₹15.34 |
| **Total** | **₹60.04 ≈ 0.30% of position** |

Add realistic slippage (~0.05–0.10% per side in NIFTY 100 names at market open) and your **breakeven per trade is roughly 0.40–0.50% of position value**. Every backtest must subtract ~0.5% per round trip. A strategy averaging +0.6% gross per trade is really averaging +0.1–0.2% net — this single number kills most naive strategies, which is exactly why it goes in first.

Note the DP charge is *fixed* (₹15.34/scrip), so it hurts small positions disproportionately — another reason to hold 3–4 positions of ₹20k+ rather than ten positions of ₹8k.

---

## 4. Architecture

```
                ┌─────────────────────────────────────────────┐
                │                SCHEDULER (cron)              │
                │  ~8:30 auth · 9:15–9:25 execute · 18:30 EOD  │
                └──────┬───────────────┬───────────────┬───────┘
                       │               │               │
              ┌────────▼──────┐ ┌──────▼───────┐ ┌─────▼──────┐
              │  DATA LAYER   │ │ SIGNAL ENGINE│ │  EXECUTOR   │
              │ Kite hist API │ │ strategy(ies)│ │ Kite orders │
              │ EOD candles   │ │ rank + filter│ │ CNC, limits │
              │ corp actions  │ │ entry/exit   │ │ retries     │
              └────────┬──────┘ └──────┬───────┘ └─────┬──────┘
                       │               │               │
              ┌────────▼───────────────▼───────────────▼──────┐
              │              LOCAL DATABASE (SQLite)           │
              │  candles · signals · orders · fills · equity   │
              └────────┬───────────────────────────────┬──────┘
                       │                               │
              ┌────────▼──────┐                 ┌──────▼──────┐
              │  BACKTESTER   │                 │ RISK MANAGER │
              │ same strategy │                 │ sizing, stops│
              │ code, walk-fwd│                 │ kill switch  │
              └───────────────┘                 └─────────────┘
```

**The golden rule of this architecture:** the Signal Engine and Risk Manager are *pure functions over data* — the identical Java classes run in backtesting and live. If your backtest code path differs from your live code path, your backtest results are fiction.

### Module breakdown (Java)

**`data`** — Pulls end-of-day OHLCV candles for the NIFTY 100 universe via Kite's historical API after market close; stores in SQLite. Handles: instrument token mapping (Kite's instruments dump), corporate action adjustments (splits/bonuses — Kite candles are adjusted, but verify on discontinuities), universe membership changes over time (keep a dated constituents table to avoid survivorship bias). One year of EOD data for 100 symbols is tiny — a few MB.

**`signal`** — The strategy interface:

```java
public interface Strategy {
    List<Signal> evaluate(MarketSnapshot snapshot, Portfolio portfolio);
}
// Signal = { symbol, action (ENTER/EXIT), referencePrice, stopPrice, rank, reason }
```

`MarketSnapshot` exposes candles *only up to the evaluation date* — enforce this in the type itself (e.g., the snapshot object is constructed with a cutoff date and refuses to return later bars). This makes lookahead bias structurally impossible rather than a matter of discipline.

**`risk`** — Consumes signals, decides what actually gets traded:
- Position size = min(capital/4, riskBudget/stopDistance), where riskBudget = 1% of current equity (₹1,000 on ₹1 lakh)
- Rejects entries when: 4 positions already open, daily/weekly loss limit hit, symbol already held, insufficient cash
- Owns the **kill switch**: if account equity drops 6% below its recent peak, the system flattens everything and stops trading until you manually review and re-enable. This is a hard rule, not a parameter you tune when it fires.

**`executor`** — Talks to Kite via the official Java client ([zerodha/javakiteconnect](https://github.com/zerodha/javakiteconnect)). Places CNC limit orders at next-day open (limit at previous close ±0.5% band beats market orders for cost control). Handles: order rejection (insufficient margin, symbol ban), partial fills (re-check position, adjust or cancel remainder), retry with idempotency (never double-order on a timeout — tag orders and reconcile against the order book before retrying). Every action logged before and after.

**`journal`** — SQLite tables for every signal (including rejected ones), order, fill, and a daily equity snapshot. Plus a small report generator: win rate, average win/loss, expectancy, max drawdown, cost drag. This is also your record for taxes — swing gains under a year are short-term capital gains (20%), and if trading is frequent enough it may be treated as business income; keep clean records and confirm treatment with a CA.

**`scheduler`** — Plain cron (or a lightweight Quartz setup) driving three jobs: morning auth + order placement (9:15–9:25 AM), evening data pull + signal computation (~6:30 PM after bhavcopy settles), and a weekly report.

### Tech stack

Java 17+ · [zerodha/javakiteconnect](https://github.com/zerodha/javakiteconnect) (official client: REST + WebSocket ticker) · SQLite via JDBC (upgrade to Postgres only if you ever need to) · JUnit for strategy unit tests · optionally ta4j (Java technical-analysis library, saves you reimplementing ATR/RSI/SMA correctly) · run on your home machine or a ₹400/month cloud VM with static IP.

---

## 5. Candidate strategies for v1 (backtest these first)

Start with *simple and well-known*. The goal of v1 is to validate your pipeline end-to-end, not to find a secret edge. Both of these are white-box, low-frequency, and long-only.

### Strategy A — Pullback in an uptrend (mean reversion within momentum)
- **Universe filter:** NIFTY 100 stocks trading above their 200-day SMA (only buy dips in uptrends)
- **Entry signal:** RSI(2) < 10 at today's close (a sharp short-term oversold in an uptrending stock)
- **Rank:** if more signals than free slots, prefer highest 6-month momentum
- **Entry:** limit order next open
- **Exit:** close > yesterday's high, or RSI(2) > 70, or 7 trading days elapsed — whichever first
- **Stop:** 1.5 × ATR(14) below entry, checked at close (exit next open if breached)

### Strategy B — Breakout with confirmation (momentum)
- **Entry signal:** today's close is a 50-day closing high AND volume > 1.5× its 20-day average AND stock is above 200-day SMA
- **Entry:** limit next open; **Exit:** trailing stop at 2.5 × ATR(14) from highest close since entry, or 10 days elapsed
- Typically lower win rate but larger winners than Strategy A — good contrast for learning how different edge shapes feel

For each: backtest 2015→present, subtract 0.5% per round trip, and demand: positive expectancy after costs, max drawdown you could emotionally survive (if the backtest shows −25%, assume live will feel worse), ≥150 trades in the test so the stats mean something, and profitability in *both* halves of the data. If neither survives — normal outcome — iterate on filters, not on adding parameters.

---

## 6. Backtesting methodology (where projects live or die)

1. **Same code, two clocks.** The backtester replays history through the identical `Strategy` and `RiskManager` classes. Fills are simulated at next-day open ± slippage, never at the signal day's close.
2. **Lookahead bias:** enforced impossible via the cutoff-dated `MarketSnapshot` (above).
3. **Survivorship bias:** use dated index constituents (NSE publishes changes) so 2018's universe is 2018's NIFTY 100, not today's winners. If that's too much for v1, acknowledge that your backtest is mildly flattered and demand a bigger margin of safety.
4. **In-sample / out-of-sample:** design and tune on 2015–2021, then run *once* on 2022–present. If you iterate against the out-of-sample data repeatedly, it silently becomes in-sample — you get very few honest shots at it.
5. **Parameter sensitivity:** if RSI(2)<10 works but RSI(2)<12 loses money, you've curve-fit noise. Real edges are plateaus, not spikes.
6. **Then paper trade for 4–8 weeks minimum:** run the full live pipeline with order placement stubbed to the journal. This tests the plumbing (auth, data timing, fills logic) and gives a final reality check.
7. **Then go live at half size** (₹10–12k positions) for the first two months.

Free/cheap data for backtesting: Kite historical API (included in your ₹500/month plan — EOD and intraday candles, several years), NSE's daily bhavcopy archives (free, official EOD), and dated NIFTY 100 constituent lists from NSE index reports.

---

## 7. Risk rules (₹1 lakh configuration)

| Rule | Value | Rationale |
|---|---|---|
| Risk per trade | 1% of equity (₹1,000) | Stop distance defines size, not conviction |
| Max position size | 25% of equity (~₹25k) | Cap even when the stop is tight |
| Max concurrent positions | 4 | DP charges + diversification sweet spot |
| Max weekly loss | 3% → pause entries for the week | Cools off losing streaks automatically |
| Kill switch | 6% drawdown from equity peak → flatten & halt | Requires manual review to resume |
| Order type | Limit only, ±0.5% band | No unprotected market orders |
| New-symbol guard | Skip stocks in ban lists / upper-lower circuits / results-day (optional) | Avoids the ugliest fill scenarios |

Expectation-setting: with ₹1 lakh, 1% risk per trade, and a decent strategy, a *good* year might be +10–20% (₹10–20k) with drawdowns of 5–10% along the way. Months will be negative. If a backtest promises much more than that with small drawdowns, distrust the backtest before trusting the strategy.

---

## 8. Build roadmap

**Phase 1 — Data + backtester (2–3 weekends).** Kite auth flow, instruments dump, EOD candle downloader, SQLite schema, `Strategy` interface, backtest engine with cost model, run Strategy A end-to-end on 5 years of data. *Milestone: a printed backtest report with equity curve and stats.*

**Phase 2 — Strategy research (2–4 weekends).** Implement Strategy B, sensitivity analysis, in/out-of-sample split, pick a survivor (or iterate). *Milestone: one strategy meeting the section-6 bar, with a one-page written spec of its exact rules.*

**Phase 3 — Live plumbing, paper mode (2 weekends + 4–8 weeks elapsed).** Scheduler, executor in paper mode, journal, daily Telegram/email summary to yourself. *Milestone: 4+ weeks of paper trades matching what the backtester would have done on the same days.*

**Phase 4 — Live at half size (2 months elapsed).** Real orders, ₹10–12k positions, weekly review of journal vs. backtest expectations. *Milestone: live slippage and fill quality measured, no operational incidents for 4 straight weeks.*

**Phase 5 — Full size + iteration.** Scale to full sizing; only now consider a second strategy running alongside, or intraday experiments.

Total realistic calendar time to full-size live: **4–6 months**. Rushing phases 3–4 is how plumbing bugs meet real money.

---

## 9. Failure modes to design against

- **The silent double-order:** executor times out, retries, both orders fill. Defense: client order tags + reconcile against Kite's order book before any retry.
- **Stale data day:** the evening job fails silently, signals compute on yesterday's data. Defense: assert candle dates == expected trading date; alert loudly and skip trading on mismatch.
- **Token expiry mid-morning:** daily access token flow fails, orders never place. Defense: auth check at 8:45 with an alert to your phone; system stands down safely if unauthenticated.
- **Strategy decay:** the edge fades over months. Defense: the journal's rolling 30-trade expectancy vs. backtest expectancy; a pre-written rule for when divergence means "stop."
- **You, at 11 PM, overriding the system** after a losing week. Defense: none technical — but write your own rules of engagement in the journal and treat manual overrides as incidents to be logged.

---

*Disclaimer: this is a technical blueprint, not investment advice. Trading involves risk of loss; figures above (charges, SEBI rules, API pricing) are as of July 2026 and should be re-verified against Zerodha and SEBI sources before you build.*
