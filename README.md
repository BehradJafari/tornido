# Tornado

Tornado is a local crypto signal tracker and self-grading backtester. It downloads completed Binance OHLCV candles, evaluates a catalog of ta4j rules, records actionable UP/DOWN calls, automatically grades 1m/15m/30m/1h/4h/12h/24h slices, and presents rolling target-hit rates in a dark React dashboard. A call hits its target only when price moves at least 0.30% in the predicted direction.

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

Production deployment files are under `deploy/`. The complete Ubuntu Server 24.04 guide covers PostgreSQL, systemd, application login/JWT security, Nginx, TLS, firewall rules, backups, upgrades and rollback:

- [Ubuntu 24.04 deployment guide](docs/ubuntu-24.04-deployment.md)
- `deploy/bootstrap-ubuntu.sh` — one-time server provisioning
- `deploy/deploy.sh` — versioned build, install, restart and health check
- `deploy/backup-postgres.sh` — PostgreSQL backup with 14-day retention

The first `ADMIN` account is created from `TORNADO_ADMIN_USERNAME` and `TORNADO_ADMIN_PASSWORD` during startup. Administrators can manage persistent `ADMIN` and `USER` accounts under **Settings → Application users**. Passwords are encoded in PostgreSQL; standard users cannot access `/api/users/**`.

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
| `GRADING_HORIZON` | `15m` | legacy settings value; analyses use the seven automatic slices |
| `CANDLE_INTERVAL` | `5m` | Binance kline interval |
| `CANDLE_LIMIT` | `250` | warm-up candle count |
| `BINANCE_REST_URL` | `https://api.binance.com` | public REST base URL |
| `BINANCE_WS_URL` | `wss://stream.binance.com:9443` | public stream base URL |

The Settings API/UI persists snapshot interval and legacy horizon values. The scheduling trigger uses startup configuration, so restart after changing the snapshot cadence. Predictions always use the seven automatic horizons.

## Strategy catalog

`StrategyDefinition` contains small, independent ta4j-backed definitions for SMA, EMA, WMA, RSI, MACD, Stochastic, CCI, ADX/DMI, Williams %R, Bollinger Bands, ROC, Chaikin Money Flow, OBV, Ichimoku, Aroon and Parabolic SAR. Trend strategies use current relative position when no fresh crossover occurs. Threshold oscillators and weak ADX conditions return `NEUTRAL`; neutral results are not persisted as predictions or counted as votes.

Signals use only fully closed candles. Tornado stores the signal candle timestamp/price separately from the later live-ticker execution timestamp/price. Due predictions are graded using the nearest Binance aggregate trade before or after the exact `executionTimestamp + horizon`. Sparse-market observations within five minutes are accepted with their signed timestamp difference persisted; larger gaps are rejected and left pending for bounded retry. One Binance lookup is shared by all strategies for the same coin and target. The actual price timestamp, signed delay, attempt count and latest grading error are persisted for auditability.

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
- `POST /api/reports/money` with coin, horizon, 2–8 methods, USDT margin, 1–125x leverage, and optional `takerFeePercent`, `slippagePercent`, `spreadPercent`, and `fundingRatePercent`

Ranked report endpoints accept `horizon=60|900|1800|3600|14400|43200|86400` and require one slice at a time. Combining the seven correlated outcomes from one signal would overstate the independent sample count. Every new manual or scheduled analysis creates predictions for all seven slices when a strategy emits an actionable signal.

The Money report is a historical scenario tool, not a profit forecast. It reports gross P&L, configurable taker fees/slippage/spread/funding estimates, and net P&L separately. Profitable-trade rate is based on positive net P&L; target-hit rate remains a separate ±0.30% research metric. Liquidation uses aggregate trades for partial first/last minutes and one-minute highs/lows only for fully covered middle minutes. Its `INDEPENDENT_TRADES_SIMPLE_LIQUIDATION` model uses approximate liquidation prices, not exchange maintenance-margin, mark-price, wallet-balance or risk-tier calculations.

Money reports also expose peak concurrent trades, peak required margin, net P&L divided by peak concurrent margin, and realized closed-trade drawdown. This is an independent-trade analysis, not a fixed-capital portfolio backtest: capital is not depleted or replenished by a portfolio engine. Realized drawdown does not claim to measure every intratrade unrealized equity fluctuation.
- `GET /api/chart/{pair}?interval=15m&limit=120`
- `GET /api/predictions?coin=&method=&from=&to=`
- `GET /api/predictions/history?coin=&method=&from=&to=` (all generations, including legacy predictions)
- `GET /api/leaderboard?coin=&window=7d`
- `GET /api/prices/stream` (SSE relay of Binance WebSocket ticks)
- `GET/PUT /api/settings`

Exchange errors use bounded retry/backoff and are isolated per coin so one unavailable Binance pair cannot crash a full scheduled cycle.

Prediction rows carry both the global signal-semantics generation and stable per-strategy code/version fields. They also preserve signal, execution, target and grading timestamps/prices plus candle interval. Data created before the neutral-signal, closed-candle and exact-time grading corrections remains available in history but is excluded from current rankings and simulations.

Reports expose target-hit rate and directional accuracy independently. Live consensus is labeled consensus strength, not probability: only methods whose 95% Wilson lower bound is above 50% receive voting weight. This score is not an empirically calibrated chance of success.

The default predictions endpoint returns only corrected generation-2 signals. Historical grading retries failed price retrieval up to five times, then marks the row `UNGRADABLE`; these rows remain auditable but are excluded from accuracy denominators.

## Verification

```bash
mvn test
cd frontend && npm run build
```

This is research software, not financial advice. Signal accuracy does not account for fees, spread, slippage, or executable order timing.
# Best mix signals and Telegram

Tornido persists the top three statistically ranked strategy mixes for every active coin, supported horizon, and mix size from 2 through 8. A mix must have at least `minimumMixSimulationTrades` decisive historical samples. Ranking uses the 95% Wilson lower bound of target-hit accuracy, then sample count as a tie-breaker.

The Settings page controls the minimum history (default `30`), simulated stop loss (default `0.50%`), signal delivery, and the daily Tehran report. These are simulations only; Tornido never places exchange orders. The research target remains `0.30%`.

Telegram credentials are environment-only and are never stored in the database:

```text
TELEGRAM_BOT_TOKEN=123456789:replace-with-botfather-token
TELEGRAM_CHAT_ID=@your_channel_username
```

The bot must be able to post and edit messages in the configured destination. If either variable is missing, Telegram features are skipped without affecting analysis, grading, or reports. The daily report runs at `00:00 Asia/Tehran` and reports the previous Tehran calendar day.

## Horizon-aware strategy profiles (signal generation v3)

New analyses use immutable `StrategyHorizonProfile` records. Each strategy and prediction horizon independently resolves an analysis candle timeframe and a constrained parameter key. A coin-specific profile may override the global profile only after meeting the stricter coin sample requirement and materially beating the global score. Predictions retain the selected profile ID, strategy/version, timeframe, signal candle close/price, execution time/price, and horizon. Historical generation-2 predictions and their existing reports remain unchanged and are never mixed with generation 3.

Until historical research validates a challenger, Tornido uses deterministic fallbacks: `1m→1m`, `15m→5m`, `30m→15m`, `1h→15m`, `4h→1h`, `12h→4h`, and `24h→4h`. These are explicitly fallback profiles, not claims of optimality. Candidate timeframe spaces are:

- 1m horizon: `1m, 3m`
- 15m: `1m, 3m, 5m, 15m`
- 30m: `3m, 5m, 15m, 30m`
- 1h: `5m, 15m, 30m, 1h`
- 4h: `15m, 30m, 1h, 2h, 4h`
- 12h: `30m, 1h, 2h, 4h, 6h`
- 24h: `1h, 2h, 4h, 6h, 8h, 12h, 1d`

The constrained parameter grid covers moving-average periods `10/20/50`, RSI `7/14/21`, MACD `8/21/5` and `12/26/9`, Stochastic `7/14/21`, CCI `14/20/30`, ADX `10/14/20`, Williams %R `7/14/21`, Bollinger `14/2` and `20/2`, ROC `6/12/24`, CMF `10/20/30`, OBV smoothing `5/10/20`, Aroon `14/25/50`, and stable defaults for Ichimoku and Parabolic SAR.

Research is chronological: the first 40% of observations is training evidence, the next 40% is divided into four walk-forward validation windows, and the final 20% is an untouched test block. Candidate ranking never reads final-test outcomes. The displayed accuracy metrics are final-test results; the selection score is calculated only from validation data:

```text
score = 100 × (
  0.35 × directional Wilson lower bound
  + 0.25 × target-hit Wilson lower bound
  + 0.15 × profitable-trade Wilson lower bound
  + 0.15 × normalized average net return
  + 0.10 × positive-window consistency
  - 0.10 × normalized maximum drawdown penalty
)
```

Wilson bounds use 95% confidence (`z=1.96`). Net returns deduct the configured round-trip research cost. Defaults require 100 global validation samples, 250 coin-specific samples, and a two-point validation-score improvement before replacement. Profile history is retained instead of overwritten. Final-test outcomes are displayed as evidence only and never participate in candidate ranking or profile replacement.

Normal snapshots do not optimize. They load active profiles once, fetch each `pair + timeframe` candle series once per run, evaluate all dependent strategies from that cache, and use completed candles only. Historical research is available from the **Strategy profiles** page or `POST /api/reports/strategy-profiles/research`. Scheduled rolling research is opt-in because Binance backfills are network- and rate-limit-intensive.
