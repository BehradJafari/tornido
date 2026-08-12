#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if ! command -v npm >/dev/null 2>&1; then
  node_bin="$(find "$PWD/.tools" -maxdepth 2 -type f -path '*/bin/node' -print -quit 2>/dev/null || true)"
  if [[ -n "$node_bin" ]]; then
    export PATH="$(dirname "$node_bin"):$PATH"
  fi
fi

if command -v mvn >/dev/null 2>&1; then
  maven=(mvn)
else
  maven_bin="$(find "$PWD/.tools" -maxdepth 2 -type f -path '*/bin/mvn' -print -quit 2>/dev/null || true)"
  if [[ -z "$maven_bin" ]]; then
    echo "Maven 3.9+ is required. Install Maven or place it under .tools/." >&2
    exit 1
  fi
  maven=("$maven_bin" -Dmaven.repo.local="$PWD/.tools/m2")
fi

if [[ ! -f src/main/resources/static/index.html ]]; then
  echo "Building Tornado web UI..."
  (cd frontend && npm install && npm run build)
fi

echo "Starting Tornado at http://localhost:${PORT:-8080}"
exec "${maven[@]}" spring-boot:run
