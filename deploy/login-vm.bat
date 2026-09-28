@echo off
REM Daily Kite login THROUGH the VM (run this on your Windows PC).
REM One-time edit: set your key path and the VM's public IP below.
REM What it does: opens an SSH tunnel PC:5000 -> VM:5000 and starts the
REM auth command on the VM. Click the login URL it prints, log in to
REM Zerodha in your browser, and the token lands ON THE VM. Ctrl+C when done.
set KEY=%USERPROFILE%\.ssh\oci-nifty-trader.key
set VM_IP=YOUR.VM.IP.HERE

ssh -i "%KEY%" -t -L 5000:127.0.0.1:5000 opc@%VM_IP% "cd ~/nifty-swing-trader && java -Xmx256m -jar target/nifty-swing-trader-*.jar auth"
pause
