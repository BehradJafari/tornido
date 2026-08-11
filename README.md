# Tornado

Tornado is a local crypto signal tracker and self-grading backtester. It downloads Binance OHLCV candles, evaluates a catalog of ta4j rules, records an UP/DOWN call for each method, automatically grades 1h/4h/12h/24h slices, and presents rolling accuracy in a dark React dashboard. A call is correct only when price moves at least 0.30% in the predicted direction.

## Stack

- Java 21, Spring Boot 3.5.4, ta4j-core 0.22.6
- Spring Data JPA + Flyway; file-backed H2 by default, PostgreSQL supported
- Binance public REST and WebSocket APIs (no key)
- React, TypeScript, and Vite

## Run locally

Prerequisites: Java 21, Maven 3.9+, Node 20+ and npm.

```bash
cd /home/behrad/tornado
chmod +x run.sh
./run.sh
```

The first launch installs/builds the UI and starts the combined application at <http://localhost:8080>. Data is stored under `./data`.

For split development with hot reload:

```bash
# terminal 1
mvn spring-boot:run

# terminal 2
cd frontend
npm install
npm run dev
```

Vite runs at <http://localhost:5173> and proxies `/api` to Spring Boot.

## Ubuntu VPS deployment

Production deployment files are under `deploy/`. The complete Ubuntu Server 24.04 guide covers PostgreSQL, systemd, Nginx, Basic Auth, TLS, firewall rules, backups, upgrades and rollback:

- [Ubuntu 24.04 deployment guide](docs/ubuntu-24.04-deployment.md)
- `deploy/bootstrap-ubuntu.sh` — one-time server provisioning
- `deploy/deploy.sh` — versioned build, install, restart and health check
- `deploy/backup-postgres.sh` — PostgreSQL backup with 14-day retention

## PostgreSQL

Start the included database, then run Tornado with its connection settings:

```bash
docker compose up -d postgres
DB_URL=jdbc:postgresql://localhost:5432/tornado DB_USERNAME=tornado DB_PASSWORD=tornado ./run.sh
```

Flyway applies `src/main/resources/db/migration/V1__initial_schema.sql` to either database.

## Configuration

Defaults live in `src/main/resources/application.yml`. Every operational value has an environment override:

| Variable | Default | Meaning |
|---|---:|---|
| `SNAPSHOT_INTERVAL` | `15m` | scheduled analysis cadence |
| `SNAPSHOT_INITIAL_DELAY` | `10s` | delay before the first automatic analysis after startup |
| `GRADING_INTERVAL` | `1m` | how often due calls are graded |
| `GRADING_HORIZON` | `15m` | legacy settings value; analyses use automatic 1h/4h/12h/24h slices |
| `CANDLE_INTERVAL` | `5m` | Binance kline interval |
| `CANDLE_LIMIT` | `250` | warm-up candle count |
| `BINANCE_REST_URL` | `https://api.binance.com` | public REST base URL |
| `BINANCE_WS_URL` | `wss://stream.binance.com:9443` | public stream base URL |

The Settings API/UI persists runtime snapshot interval and horizon values. The scheduling trigger itself uses the startup configuration, so restart after changing the snapshot cadence; new predictions immediately use the persisted grading horizon.

## Strategy catalog

`StrategyDefinition` contains small, independent ta4j-backed definitions for SMA, EMA, WMA, RSI, MACD, Stochastic, CCI, DMI, Williams %R, Bollinger Bands, ROC, Chaikin Money Flow, OBV, Ichimoku, Aroon and Parabolic SAR. Each uses ta4j indicators and built-in crossover/over/under rules. When no threshold crossing occurs on the latest candle, the current relative indicator position provides a deterministic direction.

“Every indicator” is not literally a valid trading-strategy set: ta4j also contains transforms, helpers, statistics, bar-price accessors, and indicators without directional semantics. Tornado therefore exposes an extensible catalog of applicable, interpretable strategy families. To add one, add an enum entry in `src/main/java/io/tornado/strategies/StrategyDefinition.java`; it automatically appears in snapshots and `GET /api/methods`.

## Coins and API

Twenty USDT pairs are seeded on an empty database. Coins added or removed in Settings are stored in `coins`; the Binance combined WebSocket subscription is refreshed automatically.

- `GET/POST /api/coins`, `DELETE /api/coins/{id}`
- `GET /api/methods`
- `POST /api/run-now` with `{ "name": "Morning scan" }` (creates 1m, 15m, 30m, 1h, 4h, 12h and 24h predictions)
- `GET /api/runs`, `GET /api/runs/{id}`
- `GET /api/method-report?method=RSI(14)`
- `GET /api/reports/coins?minSamples=3` (method accuracy ranked within each coin)
- `GET /api/reports/mixes?size=3&minSamples=3` (pair/triple consensus leaderboard)
- `GET /api/reports/coin-mixes?size=4&minSamples=3` (best 2-, 3-, or 4-method mixes ranked separately for every coin)
- `GET /api/reports/super?minSamples=5` (confidence-adjusted coin opportunities, current weighted consensus, best methods and mixes)
- `GET /api/reports/super/excel` (downloads an XLSX containing all coins and the best 1–6 method mixes for every checker slice)
- `POST /api/reports/money` with coin, horizon, 2–8 methods, USDT margin and 1–125x leverage (full-history gross futures simulation from the first eligible mixed prediction to the latest)

Report endpoints accept `horizon=0|60|900|1800|3600|14400|43200|86400`; `0` means all slices. Every new manual or scheduled analysis automatically creates 1-minute, 15-minute, 30-minute, 1-hour, 4-hour, 12-hour, and 24-hour checker predictions.

The Money report is a historical scenario tool, not a profit forecast. Its gross P&L intentionally excludes fees, funding, spread and slippage; simulated liquidation caps a trade loss at its supplied margin and does not reproduce an exchange's full maintenance-margin engine.
- `GET /api/chart/{pair}?interval=15m&limit=120`
- `GET /api/predictions?coin=&method=&from=&to=`
- `GET /api/leaderboard?coin=&window=7d`
- `GET /api/prices/stream` (SSE relay of Binance WebSocket ticks)
- `GET/PUT /api/settings`

Exchange errors use bounded retry/backoff and are isolated per coin so one unavailable Binance pair cannot crash a full scheduled cycle.

## Verification

```bash
mvn test
cd frontend && npm run build
```

This is research software, not financial advice. Signal accuracy does not account for fees, spread, slippage, or executable order timing.
