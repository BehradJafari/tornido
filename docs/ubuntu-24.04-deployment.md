# Deploy Tornado on Ubuntu Server 24.04

This layout runs one Spring Boot process behind Nginx, stores data in PostgreSQL, starts Tornado with systemd, and protects the dashboard/API with an application login page and signed JWT authentication. Java listens only through the local Nginx proxy in the recommended firewall configuration.

## 1. Prepare the VPS

Point a DNS `A`/`AAAA` record such as `tornado.example.com` to the VPS. Connect over SSH, install Git, and obtain the source:

```bash
sudo apt update
sudo apt install -y git
git clone YOUR_REPOSITORY_URL tornado
cd tornado
chmod +x deploy/*.sh
```

Run the one-time bootstrap. Use strong, unique passwords; quote values containing shell characters.

```bash
sudo DOMAIN=tornado.example.com \
  TORNADO_ADMIN_PASSWORD='replace-with-a-strong-dashboard-password' \
  ./deploy/bootstrap-ubuntu.sh
```

The script installs Java 21, Nginx, PostgreSQL and Certbot; creates the `tornado` Linux/PostgreSQL users and database; and installs systemd/Nginx configuration. Random PostgreSQL and JWT signing secrets are generated unless supplied explicitly.

## 2. Install build tools and deploy

The deployment script builds on the VPS. Install Maven and Node.js 20+ (Ubuntu's Node package may be older, so the NodeSource repository is shown):

```bash
sudo apt install -y maven ca-certificates curl gnupg
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
node --version
mvn --version
./deploy/deploy.sh
```

Each deployment creates `/opt/tornado/releases/<UTC timestamp>/tornado.jar`, atomically updates `/opt/tornado/current`, restarts systemd, and checks `/api/coins` locally.

## 3. Firewall and HTTPS

Keep SSH open before enabling UFW:

```bash
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'
sudo ufw enable
sudo ufw status
```

Do not expose ports `8080` or `5432`. Then request and automatically configure a certificate:

```bash
sudo certbot --nginx -d tornado.example.com
sudo certbot renew --dry-run
```

Open `https://tornado.example.com` and use the Tornido login page to sign in as `tornado-admin` with the bootstrap password. API clients can POST the same credentials to `/api/auth/login`, then send the returned token as `Authorization: Bearer TOKEN`.

The bootstrap account is an administrator. Open **Settings → Application users** to create additional `USER` or `ADMIN` accounts, reset passwords, disable access, or delete accounts. Passwords are encoded in PostgreSQL. Disabling or deleting an account invalidates its existing JWT on the next request.

## 4. Configuration and operations

Runtime configuration is stored in `/etc/tornado/tornado.env` and readable only by root and the `tornado` service group.

```bash
sudoedit /etc/tornado/tornado.env
sudo systemctl restart tornado
sudo systemctl status tornado
sudo journalctl -u tornado -f
```

Useful checks:

```bash
TOKEN=$(curl -fsS https://tornado.example.com/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"tornado-admin","password":"YOUR_PASSWORD"}' | jq -r .accessToken)
curl -H "Authorization: Bearer $TOKEN" https://tornado.example.com/api/coins
sudo nginx -t
sudo ss -lntp
```

Change the dashboard password and JWT signing secret in the protected environment file:

```bash
sudoedit /etc/tornado/tornado.env
sudo systemctl restart tornado
```

## 5. Backups and restore

Create a PostgreSQL backup:

```bash
./deploy/backup-postgres.sh
```

The default directory is `/var/backups/tornado`, and dumps older than 14 days are removed. Copy backups off the VPS. To restore into an empty database:

```bash
sudo systemctl stop tornado
sudo -u postgres pg_restore --clean --if-exists --dbname=tornado /var/backups/tornado/tornado-TIMESTAMP.dump
sudo systemctl start tornado
```

## 6. Update and rollback

Deploy an update:

```bash
git pull --ff-only
./deploy/deploy.sh
```

List releases and roll back by changing the symlink:

```bash
ls -1 /opt/tornado/releases
sudo ln -sfn /opt/tornado/releases/PREVIOUS_TIMESTAMP /opt/tornado/current
sudo systemctl restart tornado
```

Application rollback does not roll back Flyway database migrations. Always take a database backup before deploying a release containing schema changes.

## Resource guidance

A practical minimum is 2 vCPU and 2 GB RAM; 4 GB is preferable for large 1–6-method Excel exports. Adjust `JAVA_OPTS` in `/etc/tornado/tornado.env`. The export is compute-heavy because it evaluates combinations across every coin, analysis and time slice.
