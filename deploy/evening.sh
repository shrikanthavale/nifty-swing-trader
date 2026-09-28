#!/usr/bin/env bash
# The evening ritual (forward-campaign.md §10), run by swingtrader-evening.timer:
# instruments refresh -> candles download -> cycle+live (dry run unless
# live.enabled=true) -> 7-day rotating DB backup. Any failure stops the chain
# (a stale download exits 2 and nothing trades), and the summary/warnings go
# to Telegram if configured.
set -uo pipefail
cd "$(dirname "$0")/.."
JAR=$(ls -t target/nifty-swing-trader-*.jar 2>/dev/null | head -1)
if [ -z "${JAR:-}" ]; then echo "no jar built — run deploy/deploy.sh first"; exit 1; fi

java -Xmx256m -jar "$JAR" instruments || exit $?
java -Xmx256m -jar "$JAR" download    || exit $?
java -Xmx256m -jar "$JAR" live        || exit $?

mkdir -p backups
cp -f swingtrader.db "backups/swingtrader-$(date +%u).db"
