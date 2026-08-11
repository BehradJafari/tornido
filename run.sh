#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f src/main/resources/static/index.html ]]; then
  echo "Building Tornado web UI..."
  (cd frontend && npm install && npm run build)
fi

echo "Starting Tornado at http://localhost:${PORT:-8080}"
exec mvn spring-boot:run
