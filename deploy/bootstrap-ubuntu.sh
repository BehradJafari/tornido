#!/usr/bin/env bash
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Run as root: sudo DOMAIN=example.com TORNADO_ADMIN_PASSWORD='...' $0" >&2
  exit 1
fi

: "${DOMAIN:?Set DOMAIN to the DNS name pointing at this VPS}"
: "${TORNADO_ADMIN_PASSWORD:?Set TORNADO_ADMIN_PASSWORD for the protected dashboard}"
if [[ ! "$DOMAIN" =~ ^[A-Za-z0-9.-]+$ ]]; then
  echo "DOMAIN contains unsupported characters" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y \
  openjdk-21-jre-headless nginx postgresql postgresql-contrib \
  certbot python3-certbot-nginx curl ca-certificates

DB_PASSWORD="${TORNADO_DB_PASSWORD:-$(openssl rand -hex 24)}"
JWT_SECRET="${TORNADO_JWT_SECRET:-$(openssl rand -hex 48)}"
if [[ ! "$DB_PASSWORD" =~ ^[A-Za-z0-9._~-]+$ ]]; then
  echo "TORNADO_DB_PASSWORD may contain only letters, digits, dot, underscore, tilde, and hyphen" >&2
  exit 1
fi

if ! id tornado >/dev/null 2>&1; then
  useradd --system --home /var/lib/tornado --shell /usr/sbin/nologin tornado
fi
install -d -o root -g tornado -m 0750 /etc/tornado
install -d -o tornado -g tornado -m 0750 /var/lib/tornado
install -d -o root -g root -m 0755 /opt/tornado/releases

sudo -u postgres psql --set=ON_ERROR_STOP=1 --set=db_password="$DB_PASSWORD" <<'SQL'
SELECT 'CREATE ROLE tornado LOGIN PASSWORD ' || quote_literal(:'db_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'tornado') \gexec
ALTER ROLE tornado WITH LOGIN PASSWORD :'db_password';
SELECT 'CREATE DATABASE tornado OWNER tornado'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'tornado') \gexec
SQL

if [[ ! -f /etc/tornado/tornado.env ]]; then
  sed -e "s/^DB_PASSWORD=.*/DB_PASSWORD=$DB_PASSWORD/" \
      -e "s/^TORNADO_ADMIN_PASSWORD=.*/TORNADO_ADMIN_PASSWORD=$TORNADO_ADMIN_PASSWORD/" \
      -e "s/^TORNADO_JWT_SECRET=.*/TORNADO_JWT_SECRET=$JWT_SECRET/" "$SCRIPT_DIR/env.example" \
    > /etc/tornado/tornado.env
  chown root:tornado /etc/tornado/tornado.env
  chmod 0640 /etc/tornado/tornado.env
fi

install -m 0644 "$SCRIPT_DIR/tornado.service" /etc/systemd/system/tornado.service
sed "s/__DOMAIN__/$DOMAIN/g" "$SCRIPT_DIR/nginx.conf" > /etc/nginx/sites-available/tornado
ln -sfn /etc/nginx/sites-available/tornado /etc/nginx/sites-enabled/tornado
if [[ -L /etc/nginx/sites-enabled/default ]]; then unlink /etc/nginx/sites-enabled/default; fi
nginx -t
systemctl daemon-reload
systemctl enable postgresql nginx tornado
systemctl restart nginx

echo
echo "Bootstrap complete. Database credentials are in /etc/tornado/tornado.env."
echo "Next: run deploy/deploy.sh from the Tornado source directory."
echo "After HTTP works, enable TLS: sudo certbot --nginx -d $DOMAIN"
