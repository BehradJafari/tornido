#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_ENV_FILE="/etc/tornado/tornado.env"
ENV_FILE="${1:-$DEFAULT_ENV_FILE}"
SERVICE_NAME="${TORNADO_SERVICE_NAME:-tornado}"
BACKUP_DIR="${TORNADO_BACKUP_DIR:-/var/backups/tornado}"

if [[ "$ENV_FILE" == "--help" || "$ENV_FILE" == "-h" ]]; then
  echo "Usage: sudo $0 [environment-file]"
  echo "Drops and recreates only the public schema in Tornido's configured PostgreSQL database."
  exit 0
fi

if [[ ! -f "$ENV_FILE" && "$ENV_FILE" == "$DEFAULT_ENV_FILE" && -f "$SCRIPT_DIR/.env" ]]; then
  ENV_FILE="$SCRIPT_DIR/.env"
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Environment file not found: $ENV_FILE" >&2
  echo "Pass its path explicitly, for example: sudo $0 /opt/tornado/deploy/.env" >&2
  exit 1
fi

for tool in psql pg_dump; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Required PostgreSQL tool '$tool' is not installed or not in PATH." >&2
    exit 1
  fi
done

while IFS='=' read -r key value; do
  key="${key%$'\r'}"
  value="${value%$'\r'}"
  case "$key" in
    DB_URL|DB_USERNAME|DB_PASSWORD)
      if [[ "$value" == \"*\" && "$value" == *\" ]]; then
        value="${value:1:${#value}-2}"
      elif [[ "$value" == \'*\' && "$value" == *\' ]]; then
        value="${value:1:${#value}-2}"
      fi
      printf -v "$key" '%s' "$value"
      ;;
  esac
done < "$ENV_FILE"

: "${DB_URL:?DB_URL is missing from $ENV_FILE}"
: "${DB_USERNAME:?DB_USERNAME is missing from $ENV_FILE}"
: "${DB_PASSWORD:?DB_PASSWORD is missing from $ENV_FILE}"

if [[ "$DB_URL" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/([^?]+)(\?.*)?$ ]]; then
  DB_HOST="${BASH_REMATCH[1]}"
  DB_PORT="${BASH_REMATCH[3]:-5432}"
  DB_NAME="${BASH_REMATCH[4]}"
else
  echo "Unsupported DB_URL. Expected jdbc:postgresql://host:port/database" >&2
  exit 1
fi

case "$DB_NAME" in
  ""|postgres|template0|template1)
    echo "Refusing to reset protected PostgreSQL database '$DB_NAME'." >&2
    exit 1
    ;;
esac

echo "WARNING: this permanently removes every Tornido table and row."
echo "Database: $DB_NAME on $DB_HOST:$DB_PORT"
echo "A PostgreSQL dump will be written before the reset."
read -r -p "Type RESET $DB_NAME to continue: " CONFIRMATION
if [[ "$CONFIRMATION" != "RESET $DB_NAME" ]]; then
  echo "Reset cancelled."
  exit 1
fi

SERVICE_WAS_ACTIVE=false
if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet "$SERVICE_NAME"; then
  SERVICE_WAS_ACTIVE=true
  systemctl stop "$SERVICE_NAME"
fi

restart_service() {
  if [[ "$SERVICE_WAS_ACTIVE" == true ]]; then
    systemctl start "$SERVICE_NAME" || true
  fi
}
trap restart_service EXIT

install -d -m 0700 "$BACKUP_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_FILE="$BACKUP_DIR/${DB_NAME}-before-reset-$STAMP.dump"

PGPASSWORD="$DB_PASSWORD" pg_dump \
  --host="$DB_HOST" \
  --port="$DB_PORT" \
  --username="$DB_USERNAME" \
  --dbname="$DB_NAME" \
  --format=custom \
  --file="$BACKUP_FILE"

PGPASSWORD="$DB_PASSWORD" psql \
  --host="$DB_HOST" \
  --port="$DB_PORT" \
  --username="$DB_USERNAME" \
  --dbname="$DB_NAME" \
  --set=ON_ERROR_STOP=1 \
  --set=db_owner="$DB_USERNAME" <<'SQL'
BEGIN;
DROP SCHEMA public CASCADE;
CREATE SCHEMA public AUTHORIZATION :"db_owner";
GRANT ALL ON SCHEMA public TO :"db_owner";
GRANT ALL ON SCHEMA public TO public;
COMMIT;
SQL

echo "Database '$DB_NAME' is empty. Backup: $BACKUP_FILE"
if [[ "$SERVICE_WAS_ACTIVE" == true ]]; then
  echo "Restarting $SERVICE_NAME; Flyway will recreate the schema and bootstrap defaults."
fi
