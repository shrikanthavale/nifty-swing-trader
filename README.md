# nifty-swing-trader

A **personal** short-swing trading system for NSE (NIFTY 100 universe) built in Java on Zerodha's [Kite Connect](https://kite.trade) API. Signals are computed after market close; orders are placed at the next open as CNC (delivery) trades held 2–10 days.

> ⚠️ **Personal project, not a product.** This automates *my own* trading with *my own* money. It does not and cannot guarantee profit. Nothing here is investment advice. If you fork this, you take full responsibility for what it does with your account.

## How it works

```
evening (18:30)          morning (9:15–9:25)
─────────────────        ───────────────────
download EOD candles  →  authenticate (daily Kite token)
compute signals       →  risk-check & size orders
persist to SQLite     →  place CNC limit orders
                         journal everything
```

The core design rule: **the strategy and risk code that runs live is the identical code the backtester runs.** See [`docs/blueprint.md`](docs/blueprint.md) for the full design — architecture, cost model, candidate strategies, backtesting methodology, risk rules, and the 5-phase build roadmap.

## Status

- [x] Blueprint (docs/blueprint.md)
- [x] Project skeleton: strategy/risk/cost-model core with unit tests
- [ ] **Phase 1:** data downloader + backtester ← *currently here*
- [ ] Phase 2: strategy research & validation
- [ ] Phase 3: paper trading (live pipeline, no real orders)
- [ ] Phase 4: live at half size
- [ ] Phase 5: full size

## Getting started

Requires Java 21+ and Maven.

```bash
mvn test                 # runs unit tests (cost model, etc.)
mvn package              # builds target/nifty-swing-trader-*.jar
cp config/config.properties.example config/config.properties
# fill in your Kite Connect api_key/secret — NEVER commit this file
```

## Repository layout

```
docs/blueprint.md      the full system design — read this first
src/main/java/in/shrikant/swingtrader/
  data/       candles, market snapshots (anti-lookahead), downloader
  signal/     Strategy interface, indicators, strategies/
  risk/       position sizing, limits, kill switch
  backtest/   cost model (Zerodha CNC charges), backtest engine
  executor/   order placement (paper mode default)
  journal/    audit log, performance tracking
  db/         SQLite schema
config/       config template (real config is gitignored)
```

## Security note

This is a public repository. `config/config.properties`, tokens, and the local database are gitignored. **Never commit API keys, secrets, or access tokens.**
