#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RELEASE_DIR="/opt/tornado/releases/$RELEASE_ID"

cd "$PROJECT_DIR/frontend"
npm ci
npm run build

cd "$PROJECT_DIR"
mvn -q clean package

sudo install -d -o root -g root -m 0755 "$RELEASE_DIR"
sudo install -o root -g root -m 0644 target/tornado-1.0.0.jar "$RELEASE_DIR/tornado.jar"
sudo ln -sfn "$RELEASE_DIR" /opt/tornado/current
sudo systemctl restart tornado

echo "Waiting for Tornado health check..."
for attempt in {1..30}; do
  if curl -fsS http://127.0.0.1:8080/api/coins >/dev/null; then
    sudo systemctl --no-pager --full status tornado | sed -n '1,12p'
    echo "Deployed release $RELEASE_ID"
    exit 0
  fi
  sleep 2
done

echo "Deployment did not become healthy. Recent logs:" >&2
sudo journalctl -u tornado -n 80 --no-pager >&2
exit 1
