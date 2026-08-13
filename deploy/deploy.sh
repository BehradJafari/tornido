#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_ID="$(date -u +%Y%m%dT%H%M%SZ)"
RELEASE_DIR="/opt/tornado/releases/$RELEASE_ID"

for tool in node npm mvn curl; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Required deployment tool '$tool' is not installed or not in PATH." >&2
    echo "On Ubuntu 24.04 install prerequisites with:" >&2
    echo "  sudo apt update && sudo apt install -y maven ca-certificates curl gnupg" >&2
    echo "  curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -" >&2
    echo "  sudo apt install -y nodejs" >&2
    exit 1
  fi
done

NODE_MAJOR="$(node -p 'Number(process.versions.node.split(".")[0])')"
if (( NODE_MAJOR < 20 )); then
  echo "Node.js 20 or newer is required; found $(node --version)." >&2
  exit 1
fi

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
  if curl -fsS http://127.0.0.1:8080/ >/dev/null; then
    sudo systemctl --no-pager --full status tornado | sed -n '1,12p'
    echo "Deployed release $RELEASE_ID"
    exit 0
  fi
  sleep 2
done

echo "Deployment did not become healthy. Recent logs:" >&2
sudo journalctl -u tornado -n 80 --no-pager >&2
exit 1
