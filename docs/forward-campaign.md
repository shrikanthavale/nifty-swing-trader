# Forward Campaign — pre-registration (frozen 2026-09-23)

*This document is committed BEFORE any of the strategies below are backtested,
tuned, or traded. That is the whole point: everything here is written down
first, then tested on data that does not exist yet. Any change after this
commit goes in the Amendment Log at the bottom and resets that family's clock.*

Companion to `blueprint.md` (original design) and `../CLAUDE.md` (history —
including the 2016–2026 historical campaign, which concluded with both the
pullback and breakout families falsified on survivorship-corrected data).

---

## 1. Decision record

- **2026-09-23 — Shrikant:** the project goes forward; parking is a no-go.
- **2026-09-23 — Shrikant:** trade with real money, budget **₹50,000**,
  losses acceptable. This **overrides** blueprint §7's gate (backtest pass →
  4–8 weeks paper → half-size live). Recorded as a deliberate, informed owner
  decision after Claude recommended paper-first; the risk rails below are the
  compensating control and are non-negotiable.
- **2026-09-23 — Shrikant:** pursue all three new strategy families
  ("one by one or in combination") alongside the breakout shadow run.
- Claude's standing recommendation, on the record: expected value of going
  live with unproven strategies is negative after costs; the campaign's real
  product is honest forward evidence, not profit.

## 2. Why forward-only

The 2016–2026 historical dataset is burned for validation: it was used to
develop and judge two families, and any further mining of it produces fiction.
Every family below therefore faces exactly one exam: **months of forward data
it cannot have seen**. History may be used once per family for a pre-registered
sanity check (§7) — never for tuning.

## 3. Capital allocation (live account, ₹50,000)

| Sleeve | Family | Capital |
|---|---|---|
| A | Index mean-reversion (IMR-v1) | ₹16,000 |
| B | Sector rotation (ROT-v1) | ₹16,000 |
| C | Volatility regime (VRS-v1) | ₹16,000 |
| — | Cash buffer for charges/rounding | ₹2,000 |

Each sleeve is an independent virtual account: its family sizes positions
against its own ₹16,000 and is judged on its own equity curve. Sleeves may
hold the same symbol (A and C both trade NIFTYBEES); the broker account holds
the merged quantity, the journal keeps per-sleeve attribution.

**Breakout (falsified family) gets no real money.** It runs as a paper shadow
with a virtual ₹50,000, same rules as before (breakout 50d / vol 1.5× /
ATR-trail 3.0 / hold 10, membership-gated NIFTY 50 universe). Rationale:
(a) it failed its one out-of-sample exam; (b) individual-stock positions
inside a small sleeve would be so small that fixed DP charges alone
(₹15.93/scrip/sell) destroy the measurement. If its paper shadow is
convincingly positive after 12 months, promoting it is a new decision then.

## 4. The three live families — frozen parameters

All parameters are standard textbook values, taken off the shelf and
**not tuned on our data**. Fills: signal on close → order as AMO → fill at
next day's open, exactly as the backtester assumes. All three implement the
same `Strategy` interface and pass through the same `RiskManager`.

### A. IMR-v1 — index mean-reversion ("the dip-catcher, but on the whole market")
- Instrument: **NIFTYBEES** (NSE ETF tracking NIFTY 50).
- Regime filter: close > SMA(200).
- Entry: RSI(2) < 10 → buy next open, one position, sized to sleeve.
- Exit: RSI(2) > 65 at close → sell next open; hard cap 10 trading days held.
- Disaster stop: entry − 2.5 × ATR(14) (also the sizing stop).
- Lineage: Connors RSI-2 template, unmodified.

### B. ROT-v1 — sector rotation ("follow the leader, monthly")
- Universe (frozen): BANKBEES, ITBEES, PHARMABEES, PSUBNKBEES, AUTOBEES,
  NIFTYBEES. A symbol with insufficient history is simply not ranked.
- Schedule: first trading day of each month, decided on prior close data.
- Rank: 63-trading-day total return. Hold the top ETF iff its 63d return > 0;
  otherwise sit in cash for the month.
- Exit: at the next monthly review when leadership changes (or → cash).
- Disaster stop: 2.5 × ATR(14) below entry, checked daily between reviews.
- Lineage: classic 3-month relative momentum, unmodified.

### C. VRS-v1 — volatility regime switch ("in when calm, out when stormy")
- Instrument: NIFTYBEES.
- Signal: 20-day realized volatility (annualized stdev of daily log returns)
  vs its own trailing 252-day median.
- Invested iff vol20 < median252 **and** close > SMA(200); otherwise cash.
- Entry/exit at next open after the signal flips.
- Disaster stop: 2.5 × ATR(14).
- Lineage: standard volatility-regime literature, unmodified.

Note: ETFs are much cheaper to trade than stocks (STT 0.001% on sell vs 0.1%
each way for delivery equity). CostModel gets an ETF profile; the DP charge
(₹15.93/sell) stays. Estimated round trip on a ₹16,000 ETF position:
~0.15–0.20% + spread/slippage — versus ~0.5% for stock round trips.

## 5. Risk rails (live account, enforced in code before any order leaves)

- Per-sleeve position ≤ sleeve capital; per-trade risk sized off the stop.
- Weekly loss > 3% of total (₹1,500) → no new entries until next week.
- Drawdown from equity peak > 6% (₹3,000) → **kill switch**: all new live
  entries halt, exits still processed, resumes only by manual
  `live reset-peak` after human review.
- Order-level: ≤ 10 orders/sec (we place ~0–4/day), market protection −1,
  every order carries an idempotent tag; reconcile-before-retry, never
  fire-and-forget.
- Dry-run mode is the default; real order placement requires
  `live.enabled=true` **and** a registered static IP.

## 6. Compliance checklist (SEBI/NSE algo rules, verified 2026-09-23)

- [ ] Static IP from ISP (order APIs reject unregistered IPs since Apr 2026).
- [ ] IP registered in Kite developer profile (max 2; 1 change/week allowed).
- Self-developed strategy below 10 orders/sec: **no exchange registration
  needed**. AMO orders permitted.

## 7. Pre-registered sanity checks (one run each, before go-live)

Purpose: catch bugs and obvious insanity — **not** to estimate returns or
select parameters. Run once on whatever ETF history exists, results published
in `reports/` and CLAUDE.md **regardless of outcome**. A family is stopped
before go-live only if its sanity run shows: max drawdown > 30%, or
pathological behavior (e.g., trading near-daily when designed to be monthly),
or an implementation bug. Mediocre returns are NOT a stop — that judgement
belongs to forward data.

## 8. Evaluation calendar and stop rules (frozen)

- **2026-12-31** — first checkpoint (qualitative: fills vs cost model,
  plumbing, any rail breaches).
- **2027-03-31** — 6-month check. A family's live money stops if its sleeve
  is down > 15% from start, or it caused two kill-switch firings.
- **2027-09-30** — 12-month verdict per family: continue live iff positive
  after all costs and behavior matched design intent. Anything else →
  demoted to paper or retired. Small-sample honesty: ROT-v1 makes ~12
  decisions/year; even the 12-month verdict is weak evidence, and the write-up
  must say so.
- **No parameter changes in flight.** A "small tweak" creates a NEW family
  name (e.g. IMR-v2), a new pre-registration entry, and a fresh clock. The
  old version keeps running unmodified until its checkpoint.

## 9. What real money buys us analytically

One thing paper cannot: **true fills**. Every live fill is journaled against
the previous close and the modeled cost, so by 2026-12-31 we have a measured
slippage number for ETFs instead of an assumption. Everything else — does the
family have an edge? — forward paper would have answered identically.

## 10. Daily ritual (unchanged, ~2 minutes)

Morning: `auth` (Kite login). Evening: `download` → `cycle` (all four
families: 3 live sleeves + breakout shadow) → AMO orders out → Telegram/stdout
summary. Miss an evening → the stale-data guard refuses to trade; nothing
bad happens except a skipped day.

## Amendment log

- **2026-09-24 — A1 (clarification, recorded before any strategy was tested
  or any ETF data downloaded; clocks unaffected):** §3/§4 assume full-sleeve
  positions (₹16,000) but did not say how that reconciles with the default
  risk rails (1% risk per trade, 25% position cap), which were designed for
  the multi-stock portfolio. Resolution: sleeves use an ADDITIVE RiskManager
  profile — max position 100% of sleeve capital, i.e. positions sized to the
  full sleeve — while the 1%-per-trade rail is measured against TOTAL account
  capital (₹50,000): a 2.5×ATR stop-out on a ₹16,000 ETF position ≈
  ₹400–600 ≈ 0.8–1.2% of total, which is accepted. The no-arg RiskManager
  used by the stock strategies is unchanged. Sanity backtests (§7) use the
  same sleeve profile so they measure what will actually trade.
- **2026-09-24 — A2 (factual correction, before any testing):** §4's cost
  note gave the DP charge as ₹15.93/scrip/sell; Zerodha's current published
  rate (zerodha.com/charges, verified 2026-09-24) is ₹15.34 (₹3.5 CDSL +
  ₹9.5 Zerodha + GST). The ETF cost profile uses ₹15.34. No strategy
  parameter is affected.
