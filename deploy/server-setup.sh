#!/usr/bin/env bash
# One-time setup of a fresh Ubuntu EC2 server. Run as the ubuntu user:  bash server-setup.sh
# Java and Node are NOT installed on the host: they run inside the Docker build.
set -euo pipefail

echo "== 1. Swap (2 GB): the build needs it on a small server"
if ! swapon --show | grep -q /swapfile; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
  echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf >/dev/null
  sudo sysctl -p /etc/sysctl.d/99-swappiness.conf >/dev/null
fi

echo "== 2. Docker + git"
if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER"
fi
sudo apt-get install -y git certbot unattended-upgrades

echo "== 3. Folders"
sudo mkdir -p /srv/amanah/files
sudo chown 1000:1000 /srv/amanah/files   # the backend container runs as uid 1000

echo "Done."
