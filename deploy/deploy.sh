#!/usr/bin/env bash
# Runs ON THE SERVER, started by the GitHub Actions deploy job (or by hand):  bash ~/app/deploy/deploy.sh
# Pulls the latest main, rebuilds the images, restarts the stack and waits until the API is healthy.
set -euo pipefail

cd "$(dirname "$0")/.."
BRANCH="${BRANCH:-main}"

echo "== Pull $BRANCH"
git fetch --prune origin "$BRANCH"
git reset --hard "origin/$BRANCH"
echo "Deploying $(git rev-parse --short HEAD): $(git log -1 --pretty=%s)"

cd deploy
test -f .env || { echo "deploy/.env is missing on the server"; exit 1; }

echo "== Build (one image at a time: the server has 2 GB)"
export COMPOSE_PARALLEL_LIMIT=1 DOCKER_BUILDKIT=1
docker compose build

echo "== Restart"
docker compose up -d --remove-orphans

echo "== Wait for the API"
for i in $(seq 1 40); do
  status="$(docker inspect -f '{{.State.Health.Status}}' amanah-backend 2>/dev/null || echo none)"
  if [ "$status" = "healthy" ]; then
    echo "Backend is healthy."
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 6
done

echo "Backend did not become healthy. Last logs:"
docker compose logs --tail=60 backend
exit 1
