#!/usr/bin/env bash
# Update + rebuild + (re)install the evening timer. Run any time: bash deploy/deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."

git pull --ff-only
echo "-- building (tests run on the PC, not this 1GB box)"
MAVEN_OPTS=-Xmx512m mvn -q -DskipTests package

sudo cp deploy/swingtrader-evening.service deploy/swingtrader-evening.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now swingtrader-evening.timer
echo "-- installed. Next firings:"
systemctl list-timers swingtrader-evening.timer --no-pager | head -3
