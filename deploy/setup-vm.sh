#!/usr/bin/env bash
# One-time setup of the OCI VM (Oracle Linux 9, works on the 1GB E2.1.Micro).
# Run as the default 'opc' user:
#   curl -fsSL https://raw.githubusercontent.com/shrikanthavale/nifty-swing-trader/main/deploy/setup-vm.sh | bash
set -euo pipefail

echo "== nifty-swing-trader VM setup =="

# 1. swap (2GB) — the 1GB VM needs it for the one-time Maven build
if ! sudo swapon --show | grep -q /swapfile; then
  echo "-- creating 2GB swap file"
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

# 2. IST — the timer fires at 18:45 local time
sudo timedatectl set-timezone Asia/Kolkata

# 3. packages
echo "-- installing Java 21, git, maven"
sudo dnf -y -q install java-21-openjdk-headless git maven

# 4. the repo
cd "$HOME"
if [ ! -d nifty-swing-trader ]; then
  git clone https://github.com/shrikanthavale/nifty-swing-trader.git
fi
cd nifty-swing-trader

# 5. config scaffold (SECRETS ARE NEVER IN GIT — copy yours from your PC)
mkdir -p config backups
if [ ! -f config/config.properties ]; then
  cp config/config.properties.example config/config.properties
  echo "!! config/config.properties created from the example — copy your real one from your PC:"
  echo "   type config\\config.properties | ssh -i <key> opc@<vm-ip> \"cat > ~/nifty-swing-trader/config/config.properties\""
fi

# 6. build + install the evening timer
bash deploy/deploy.sh

echo "== done. Next: copy your config (step 5 above), then test with:"
echo "   cd ~/nifty-swing-trader && java -Xmx256m -jar target/nifty-swing-trader-*.jar universe"
