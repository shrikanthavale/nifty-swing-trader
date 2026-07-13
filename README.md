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
- [x] Kite auth flow: daily login → request-token auto-capture → token exchange (`auth` command)
- [x] Instruments dump + dated NIFTY 100 universe (`instruments` / `universe` commands)
- [ ] **Phase 1:** candle downloader + backtester ← *currently here*
- [ ] Phase 2: strategy research & validation
- [ ] Phase 3: paper trading (live pipeline, no real orders)
- [ ] Phase 4: live at half size
- [ ] Phase 5: full size

## Getting started

Requires Java 21+ and Maven.

```bash
mvn test                 # runs unit tests (cost model, auth, etc.)
mvn package              # builds target/nifty-swing-trader-*.jar
cp config/config.properties.example config/config.properties
# fill in your Kite Connect api_key/secret — NEVER commit this file
```

### Daily Kite login

Kite Connect access tokens expire every morning, so each trading day starts
with a ~30-second login ritual (fully unattended token generation violates
Zerodha's ToS):

```bash
java -jar target/nifty-swing-trader-*.jar auth
```

This prints (and tries to open) the Kite login URL; you log in on Zerodha's
page, and a one-shot local listener on `http://127.0.0.1:5000/callback`
catches the redirect and exchanges the request token automatically. The day's
access token lands in `config/access_token.properties` (gitignored). Your
Kite app at [developers.kite.trade](https://developers.kite.trade) must have
exactly `http://127.0.0.1:5000/callback` registered as its Redirect URL
(port configurable via `kite.redirect_port`).

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
