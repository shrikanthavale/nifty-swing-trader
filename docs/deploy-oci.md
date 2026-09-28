# Running on the Oracle Cloud free VM

The bot lives on an always-free OCI VM (1 OCPU / 1 GB E2.1.Micro, Oracle
Linux 9, Mumbai). The VM does the evening work on a schedule; the only daily
human action is the 30-second Kite login, done from the PC through an SSH
tunnel. Scripts live in `deploy/`.

## One-time setup

1. **Reserve the IP.** The IP assigned at creation is *ephemeral* (lost if
   the instance is ever rebuilt). Convert it: Instance → Attached VNICs →
   IPv4 addresses → Edit → change Public IP type to **Reserved**. This
   reserved IP is what gets registered in the Kite developer profile
   (Profile → IP Whitelist) before live trading — orders from any other IP
   are rejected (SEBI rule, Apr 2026).
2. **SSH in** from PowerShell:
   `ssh -i <path-to-private-key> opc@<vm-ip>`
3. **Run the setup script** (swap, IST timezone, Java 21, git, maven, clone,
   build, evening timer):
   `curl -fsSL https://raw.githubusercontent.com/shrikanthavale/nifty-swing-trader/main/deploy/setup-vm.sh | bash`
4. **Copy the real config** from the PC (never in git!):
   `type config\config.properties | ssh -i <key> opc@<vm-ip> "cat > ~/nifty-swing-trader/config/config.properties"`
   Keep `live.enabled=false` until the go-live gates pass (see below).
5. **Set up the login shortcut on the PC:** copy `deploy/login-vm.bat`
   somewhere handy, edit its two variables (key path, VM IP), double-click
   to test: it opens the tunnel, prints the Kite login URL, you log in in
   the browser, the token is captured on the VM.

## The daily rhythm

- **You, once a day (any time before 18:45 IST):** double-click
  `login-vm.bat`, log in to Zerodha. That's it.
- **The VM, 18:45 IST Mon–Fri** (`swingtrader-evening.timer`):
  `instruments` → `download` → `live` (all four sleeves; dry run unless
  `live.enabled=true`) → rotating 7-day DB backup in `~/nifty-swing-trader/backups/`.
  Summary lands on Telegram if configured. Skipped login ⇒ stale-data guard
  aborts loudly and nothing trades — a missed day is always safe.
- **NSE holidays:** the run aborts as "stale" — expected noise (no holiday
  calendar yet).

## Operating it

- Did it run? `systemctl list-timers swingtrader-evening.timer`
- Logs: `journalctl -u swingtrader-evening.service -n 100 --no-pager`
- Run the evening manually: `bash ~/nifty-swing-trader/deploy/evening.sh`
- Update to the latest pushed code: `bash ~/nifty-swing-trader/deploy/deploy.sh`
- The DB (`swingtrader.db`) lives only on the VM once the VM takes over —
  don't run `cycle`/`live` from the PC in parallel, or the two ledgers fork.

## Go-live gates (forward-campaign.md §5/§6 — all must hold before live.enabled=true)

1. Reserved static IP registered in the Kite developer profile.
2. Fill-price readback + late-rejection reconciliation implemented (journal
   vs Kite order book) — the executor's two known gaps.
3. Several clean dry-run evenings from the VM, summaries reviewed.

## Free-tier housekeeping

- Upgrade the OCI account to Pay-As-You-Go (still ₹0 within always-free
  limits) so the idle-instance reclamation policy stops applying; set a
  budget alert.
- No inbound ports are open except SSH; the auth listener binds localhost
  and is reached only through the SSH tunnel.
