#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/tornado}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
sudo install -d -o postgres -g postgres -m 0750 "$BACKUP_DIR"
sudo -u postgres pg_dump --format=custom --file="$BACKUP_DIR/tornado-$STAMP.dump" tornado
sudo -u postgres find "$BACKUP_DIR" -type f -name 'tornado-*.dump' -mtime +14 -delete
echo "Created $BACKUP_DIR/tornado-$STAMP.dump"
