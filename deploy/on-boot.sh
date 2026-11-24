#!/usr/bin/env bash
# Runs ON THE SERVER every time it starts (from the ubuntu user's crontab, see below).
# The server may have been off while new code was pushed: if so, rebuild; otherwise just start the stack.
#
# Install once:   (crontab -l 2>/dev/null; echo '@reboot sleep 30 && bash /home/ubuntu/app/deploy/on-boot.sh >> /home/ubuntu/on-boot.log 2>&1') | crontab -
set -euo pipefail

cd "$(dirname "$0")/.."
echo "== $(date -Is) boot"

# Wait for Docker and the network.
for i in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 2; done

git fetch --prune origin main
if [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]; then
  echo "New code found: deploying."
  bash deploy/deploy.sh
else
  echo "Up to date: starting."
  cd deploy && docker compose up -d
fi
