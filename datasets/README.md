# datasets/

## nifty50_membership.csv

Dated NIFTY 50 membership intervals (`symbol,from_date,to_date`; empty
`to_date` = still a member; `to_date` is exclusive). **This is the
survivorship-bias fix**: `universe history` loads it into the constituents
table, and backtests/sweeps/paper then only allow entries in symbols that
were index members on the signal date.

Provenance: derived from `NIFTY50_constituents_2016_to_2026.xlsx`
(marketcalls.in reconstruction of NSE Indices press releases, built
2026-08-30; 22 semi-annual snapshots, every one 50 companies with full
weights). Validated by replaying its change log from the 2016-03 baseline —
it reproduces all 21 later snapshots exactly. Symbols are mapped to
CURRENT NSE tickers so Kite candle history lines up across renames:
INFRATEL→INDUSTOWER (2020), ZOMATO→ETERNAL (2025), TATAMOTORS→TMPV (2025),
IBULHSGFIN→SAMMAANCAP (2024), LTIM→LTM (2026). Tata Motors DVR (an
"additional security", not a company) is excluded. Includes the announced
Sep-2026 change (WIPRO out / BSE in, effective 2026-09-30).

Known gap: **HDFC** (Housing Development Finance Corp) was a member
2016→2023 but delisted at its merger into HDFC Bank — Kite serves no
candles for it, so backtests silently lack one heavyweight member for
those years. The bias direction is roughly conservative but nonzero.

Coverage starts 2016-04-01 → honest backtests must not start earlier.
