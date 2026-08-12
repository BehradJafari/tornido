import { useEffect, useMemo, useState } from "react";
import {
  Activity,
  ArrowDownRight,
  ArrowUpRight,
  BarChart3,
  ChevronRight,
  Clock3,
  Coins,
  DollarSign,
  Download,
  LineChart,
  LoaderCircle,
  Play,
  Plus,
  Settings,
  Trash2,
  Tornado as TornadoIcon,
  X,
} from "lucide-react";
type Coin = { id: number; symbol: string; pair: string };
type Run = {
  id: number;
  name: string;
  createdAt: string;
  horizonSeconds: number;
  status: string;
  predictions: number;
  pending: number;
  correct: number;
  accuracy: number;
  errors: number;
};
type Prediction = {
  id: number;
  coin: Coin;
  methodName: string;
  predictedAt: string;
  horizonSeconds: number;
  predictedDirection: "UP" | "DOWN";
  priceAtPrediction: number;
  priceAtGrading?: number;
  outcome: "PENDING" | "CORRECT" | "INCORRECT";
};
type Leader = {
  method: string;
  total: number;
  correct: number;
  accuracy: number;
};
type Candle = {
  time: number;
  open: string;
  high: string;
  low: string;
  close: string;
};
type View =
  | "super"
  | "analyses"
  | "methods"
  | "coins"
  | "mixes"
  | "money"
  | "history"
  | "settings";
const api = async <T,>(url: string, init?: RequestInit): Promise<T> => {
  requestStarted();
  try {
    const r = await fetch(url, {
      headers: { "Content-Type": "application/json" },
      ...init,
    });
    if (!r.ok) throw new Error((await r.json()).error || r.statusText);
    return r.json();
  } finally {
    requestFinished();
  }
};
let activeRequests = 0;
const requestListeners = new Set<(count: number) => void>();
const publishRequests = () => requestListeners.forEach((x) => x(activeRequests));
const requestStarted = () => {
  activeRequests++;
  publishRequests();
};
const requestFinished = () => {
  activeRequests = Math.max(0, activeRequests - 1);
  publishRequests();
};
const money = (v: number | string | undefined) =>
  v == null
    ? "—"
    : new Intl.NumberFormat("en-US", {
        maximumFractionDigits: Number(v) < 1 ? 7 : 2,
      }).format(Number(v));
const ago = (s: string) => {
  const m = Math.floor((Date.now() - new Date(s).getTime()) / 60000);
  return m < 1
    ? "just now"
    : m < 60
      ? `${m}m ago`
      : m < 1440
        ? `${Math.floor(m / 60)}h ago`
        : `${Math.floor(m / 1440)}d ago`;
};
export default function App() {
  const [view, setView] = useState<View>("super"),
    [coins, setCoins] = useState<Coin[]>([]),
    [runs, setRuns] = useState<Run[]>([]),
    [preds, setPreds] = useState<Prediction[]>([]),
    [leaders, setLeaders] = useState<Leader[]>([]),
    [prices, setPrices] = useState<Record<string, string>>({}),
    [selectedRun, setSelectedRun] = useState<number>(),
    [method, setMethod] = useState<string>(),
    [showRun, setShowRun] = useState(false),
    [notice, setNotice] = useState(""),
    [requestCount, setRequestCount] = useState(activeRequests);
  const failed = (e: unknown) => setNotice(String(e));
  const loadCoins = () => api<Coin[]>("/api/coins").then(setCoins).catch(failed);
  const loadPrices = () =>
    api<Record<string, { price: string }>>("/api/prices")
      .then((t) =>
        setPrices((x) => ({
          ...x,
          ...Object.fromEntries(
            Object.entries(t).map(([k, v]) => [k, v.price]),
          ),
        })),
      )
      .catch(failed);
  const loadPage = () => {
    setNotice("");
    if (view === "super") loadPrices();
    if (view === "analyses") {
      loadCoins();
      api<Run[]>("/api/runs").then(setRuns).catch(failed);
      loadPrices();
    }
    if (view === "methods") {
      api<Leader[]>("/api/leaderboard?window=30d")
        .then(setLeaders)
        .catch(failed);
      loadPrices();
    }
    if (view === "coins" || view === "money" || view === "settings") {
      loadCoins();
    }
    if (view === "history") {
      api<Prediction[]>("/api/predictions").then(setPreds).catch(failed);
      loadPrices();
    }
  };
  useEffect(() => {
    requestListeners.add(setRequestCount);
    return () => {
      requestListeners.delete(setRequestCount);
    };
  }, []);
  useEffect(() => {
    loadPage();
  }, [view]);
  useEffect(() => {
    const needsLivePrices =
      view === "super" ||
      view === "analyses" ||
      view === "methods" ||
      view === "history" ||
      selectedRun != null;
    if (!needsLivePrices) return;
    const es = new EventSource("/api/prices/stream");
    es.onmessage = (e) => {
      const t = JSON.parse(e.data);
      setPrices((p) => ({ ...p, [t.pair]: t.price }));
    };
    return () => {
      es.close();
    };
  }, [view, selectedRun]);
  const openRun = async (id: number) => {
    const r = await api<{ predictions: Prediction[] }>(`/api/runs/${id}`);
    setPreds(r.predictions);
    setSelectedRun(id);
    setMethod(undefined);
  };
  return (
    <div className="shell">
      {requestCount > 0 && (
        <div className="request-loading" role="status" aria-live="polite">
          <i />
          <span>
            <LoaderCircle /> Loading{requestCount > 1 ? ` ${requestCount} requests` : ""}…
          </span>
        </div>
      )}
      <aside>
        <div className="brand">
          <span>
            <TornadoIcon />
          </span>
          <div>
            TORNADO<small>SIGNAL INTELLIGENCE</small>
          </div>
        </div>
        <nav>
          {(
            [
              ["super", TornadoIcon, "Super analysis"],
              ["analyses", Activity, "Analyses"],
              ["methods", BarChart3, "Method reports"],
              ["coins", Coins, "Coin reports"],
              ["mixes", LineChart, "Method mixes"],
              ["money", DollarSign, "Money report"],
              ["history", Clock3, "Prediction log"],
              ["settings", Settings, "Settings"],
            ] as const
          ).map(([id, I, label]) => (
            <button
              className={view === id ? "active" : ""}
              onClick={() => {
                setView(id);
                setSelectedRun(undefined);
                setMethod(undefined);
              }}
            >
              <I />
              {label}
            </button>
          ))}
        </nav>
        <div className="status">
          <i />
          BINANCE CONNECTED<small>{coins.length} MARKETS LIVE</small>
        </div>
      </aside>
      <main>
        <header>
          <div>
            <p>
              TORNADO / <b>{view.toUpperCase()}</b>
            </p>
            <h1>
              {selectedRun
                ? runs.find((r) => r.id === selectedRun)?.name
                : method ||
                  {
                    super: "Super analysis",
                    analyses: "Analysis center",
                    methods: "Method intelligence",
                    coins: "Coin intelligence",
                    mixes: "Best method combinations",
                    money: "Futures money simulator",
                    history: "Prediction log",
                    settings: "Workspace settings",
                  }[view]}
            </h1>
          </div>
          <button className="run" onClick={() => setShowRun(true)}>
            <Play />
            New analysis
          </button>
        </header>
        {notice && <div className="notice">{notice}</div>}
        {selectedRun ? (
          <RunDetail
            run={runs.find((r) => r.id === selectedRun)}
            predictions={preds}
            prices={prices}
            method={method}
            setMethod={setMethod}
            back={() => {
              setSelectedRun(undefined);
              loadPage();
            }}
          />
        ) : method ? (
          <MethodDetail
            name={method}
            prices={prices}
            back={() => setMethod(undefined)}
          />
        ) : (
          <>
            {view === "super" && <SuperAnalysis prices={prices} />}{" "}
            {view === "analyses" && (
              <Analyses
                runs={runs}
                coins={coins}
                prices={prices}
                open={openRun}
              />
            )}{" "}
            {view === "methods" && <Methods rows={leaders} open={setMethod} />}{" "}
            {view === "coins" && <CoinReports coins={coins} />}{" "}
            {view === "mixes" && <MixReports />}{" "}
            {view === "money" && <MoneyReport coins={coins} />}{" "}
            {view === "history" && <History rows={preds} prices={prices} />}{" "}
            {view === "settings" && (
              <SettingsView coins={coins} reload={loadPage} />
            )}
          </>
        )}
        {showRun && (
          <RunDialog
            close={() => setShowRun(false)}
            done={(id) => {
              setShowRun(false);
              loadPage();
              openRun(id);
            }}
          />
        )}
      </main>
    </div>
  );
}
function SuperAnalysis({ prices }: { prices: Record<string, string> }) {
  type C = {
    coin: string;
    samples: number;
    rawAccuracy: number;
    valueScore: number;
    bestMethod?: string;
    bestMethodAccuracy: number;
    bestMix: string[];
    bestMixAccuracy: number;
    bestMixSamples: number;
    currentDirection: string;
    currentConfidence: number;
    currentSignals: number;
  };
  const [data, setData] = useState<{
      coins: C[];
      topMixes: {
        methods: string[];
        samples: number;
        correct: number;
        accuracy: number;
      }[];
    }>(),
    [min, setMin] = useState(1),
    [horizon, setHorizon] = useState(3600),
    [exporting, setExporting] = useState(false);
  useEffect(() => {
    api<any>(`/api/reports/super?minSamples=${min}&horizon=${horizon}`).then(
      setData,
    );
  }, [min, horizon]);
  const exportExcel = async () => {
    setExporting(true);
    requestStarted();
    try {
      const response = await fetch("/api/reports/super/excel");
      if (!response.ok) throw new Error(response.statusText);
      const href = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = href;
      link.download = "super-analysis.xlsx";
      link.click();
      URL.revokeObjectURL(href);
    } finally {
      requestFinished();
      setExporting(false);
    }
  };
  const top = data?.coins[0],
    mix = data?.topMixes[0];
  return (
    <>
      <div className="super-export-row">
        <HorizonFilter value={horizon} setValue={setHorizon} />
        <button className="run" onClick={exportExcel} disabled={exporting}>
          <Download /> {exporting ? "Building Excel…" : "Export all slices"}
        </button>
      </div>
      <section className="super-hero">
        <div>
          <span>CONFIDENCE-ADJUSTED MARKET EDGE</span>
          <h2>{top?.coin || "Collecting evidence"}</h2>
          <p>
            {top
              ? `${top.currentDirection} consensus · ${top.currentConfidence.toFixed(0)}% agreement across ${top.currentSignals} current signals`
              : "Run and grade more analyses to identify the strongest market."}
          </p>
        </div>
        <div className="hero-score">
          <small>VALUE SCORE</small>
          <strong>{top ? top.valueScore.toFixed(1) : "—"}</strong>
          <em>Conservative target-hit score</em>
        </div>
        <div
          className={`hero-call ${(top?.currentDirection || "").toLowerCase()}`}
        >
          {top?.currentDirection || "WAIT"}
        </div>
      </section>
      <div className="report-filter super-filter">
        <div>
          <small>MINIMUM EVIDENCE PER METHOD / MIX</small>
          <input
            type="number"
            min="1"
            value={min}
            onChange={(e) => setMin(Math.max(1, +e.target.value))}
          />
        </div>
        <p>
          Value score uses the 95% Wilson lower bound. It rewards target hits and
          sample size while penalizing small, lucky samples.
        </p>
      </div>
      <section className="metrics">
        <Metric
          label="MOST VALUABLE COIN"
          value={top?.coin || "—"}
          sub={
            top
              ? `${top.rawAccuracy.toFixed(1)}% raw · ${top.samples} samples`
              : "No graded data"
          }
        />
        <Metric
          label="STRONGEST METHOD"
          value={top?.bestMethod || "—"}
          sub={
            top?.bestMethod
              ? `${top.bestMethodAccuracy.toFixed(1)}% on ${top.coin}`
              : "More evidence needed"
          }
        />
        <Metric
          label="STRONGEST GLOBAL MIX"
          value={mix ? `${mix.accuracy.toFixed(1)}%` : "—"}
          sub={
            mix
              ? `${mix.methods.join(" + ")} · ${mix.samples} votes`
              : "More evidence needed"
          }
        />
      </section>
      <div className="section-title">
        <h2>Most valuable coins now</h2>
        <span>HISTORY + CURRENT CONSENSUS</span>
      </div>
      <section className="opportunity-grid">
        {(data?.coins || []).map((c, i) => (
          <article>
            <div className="opp-head">
              <i>#{i + 1}</i>
              <div>
                <strong>{c.coin}/USDT</strong>
                <small>${money(prices[`${c.coin}USDT`])}</small>
              </div>
              <em className={c.currentDirection.toLowerCase()}>
                {c.currentDirection}
              </em>
            </div>
            <div className="value-line">
              <span>VALUE SCORE</span>
              <b>{c.valueScore.toFixed(1)}</b>
              <u>
                <i style={{ width: `${c.valueScore}%` }} />
              </u>
            </div>
            <dl>
              <div>
                <dt>Raw target-hit rate</dt>
                <dd>
                  {c.rawAccuracy.toFixed(1)}% <small>({c.samples})</small>
                </dd>
              </div>
              <div>
                <dt>Current confidence</dt>
                <dd>{c.currentConfidence.toFixed(0)}%</dd>
              </div>
              <div>
                <dt>Best method</dt>
                <dd>
                  {c.bestMethod || "—"}{" "}
                  <small>
                    {c.bestMethod ? `${c.bestMethodAccuracy.toFixed(0)}%` : ""}
                  </small>
                </dd>
              </div>
              <div>
                <dt>Best 3-method mix</dt>
                <dd>
                  {c.bestMix.length ? c.bestMix.join(" + ") : "—"}{" "}
                  <small>
                    {c.bestMix.length
                      ? `${c.bestMixAccuracy.toFixed(0)}% / ${c.bestMixSamples} votes`
                      : ""}
                  </small>
                </dd>
              </div>
            </dl>
          </article>
        ))}
        {!data?.coins.length && (
          <Empty text="Super analysis needs graded predictions. Run several named analyses and let their checker time expire." />
        )}
      </section>
      <div className="section-title">
        <h2>Highest-value method mixes</h2>
        <span>GLOBAL TOP 10</span>
      </div>
      <section className="mix-grid super-mixes">
        {(data?.topMixes || []).map((x, i) => (
          <article>
            <div className="rank">#{String(i + 1).padStart(2, "0")}</div>
            <div className="mix-methods">
              {x.methods.map((m) => (
                <span>{m}</span>
              ))}
            </div>
            <div className="mix-score">
              <strong>{x.accuracy.toFixed(1)}%</strong>
              <small>
                {x.correct}/{x.samples} correct
              </small>
            </div>
          </article>
        ))}
      </section>
    </>
  );
}
function Analyses({
  runs,
  coins,
  prices,
  open,
}: {
  runs: Run[];
  coins: Coin[];
  prices: Record<string, string>;
  open: (id: number) => void;
}) {
  const [coin, setCoin] = useState(coins[0]?.pair || "BTCUSDT");
  useEffect(() => {
    if (!coin && coins[0]) setCoin(coins[0].pair);
  }, [coins]);
  return (
    <>
      <section className="metrics">
        <Metric
          label="TOTAL ANALYSES"
          value={String(runs.length)}
          sub={`${runs.filter((r) => r.pending > 0).length} awaiting checks`}
        />
        <Metric
          label="SIGNALS LOGGED"
          value={String(runs.reduce((n, r) => n + r.predictions, 0))}
          sub="Across 1h / 4h / 12h / 24h"
        />
        <Metric
          label="GRADING MARGIN"
          value="0.30%"
          sub="Minimum directional movement"
        />
      </section>
      <div className="chart-panel panel">
        <div className="chart-toolbar">
          <div>
            <small>LIVE MARKET</small>
            <h2>
              {coin.replace("USDT", " / USDT")} <b>${money(prices[coin])}</b>
            </h2>
          </div>
          <select value={coin} onChange={(e) => setCoin(e.target.value)}>
            {coins.map((c) => (
              <option value={c.pair}>{c.symbol}/USDT</option>
            ))}
          </select>
        </div>
        <MarketChart pair={coin} />
      </div>
      <div className="section-title">
        <h2>Analysis runs</h2>
        <span>{runs.length} SAVED</span>
      </div>
      <section className="run-list">
        {runs.map((r) => (
          <button className="run-card" onClick={() => open(r.id)}>
            <div className="run-state">
              <i className={r.pending ? "waiting" : "done"} />
            </div>
            <div>
              <strong>{r.name}</strong>
              <small>
                {new Date(r.createdAt).toLocaleString()} · automatic 1h / 4h /
                12h / 24h slices
              </small>
            </div>
            <div className="run-stat">
              <small>SIGNALS</small>
              <b>{r.predictions}</b>
            </div>
            <div className="run-stat">
              <small>CHECKED</small>
              <b>
                {r.predictions - r.pending}/{r.predictions}
              </b>
            </div>
            <div className="run-stat">
              <small>ACCURACY</small>
              <b className="green">
                {r.predictions === r.pending
                  ? "Pending"
                  : `${r.accuracy.toFixed(1)}%`}
              </b>
            </div>
            <ChevronRight />
          </button>
        ))}
        {!runs.length && (
          <Empty text="Create your first named analysis to start tracking signals." />
        )}
      </section>
    </>
  );
}
function RunDetail({
  run,
  predictions,
  prices,
  method,
  setMethod,
  back,
}: {
  run?: Run;
  predictions: Prediction[];
  prices: Record<string, string>;
  method?: string;
  setMethod: (s: string | undefined) => void;
  back: () => void;
}) {
  const [horizon, setHorizon] = useState(3600);
  const visible = predictions.filter((p) => p.horizonSeconds === horizon);
  if (method)
    return (
      <MethodSlice
        name={method}
        rows={visible.filter((p) => p.methodName === method)}
        prices={prices}
        back={() => setMethod(undefined)}
      />
    );
  const methods = [...new Set(visible.map((p) => p.methodName))],
    pending = visible.filter((p) => p.outcome === "PENDING").length,
    graded = visible.length - pending,
    correct = visible.filter((p) => p.outcome === "CORRECT").length;
  return (
    <>
      <button className="back" onClick={back}>
        ← All analyses
      </button>
      <HorizonFilter value={horizon} setValue={setHorizon} includeAll={false} />
      <section className="metrics">
        <Metric
          label="TIME SLICE"
          value={horizonLabel(horizon)}
          sub={pending ? `${pending} signals pending` : "All signals checked"}
        />
        <Metric
          label="DIRECTION MIX"
          value={`${visible.filter((p) => p.predictedDirection === "UP").length} UP / ${visible.filter((p) => p.predictedDirection === "DOWN").length} DOWN`}
          sub={`${visible.length} predictions in this slice`}
        />
        <Metric
          label="ACCURACY"
          value={
            graded ? `${((correct / graded) * 100).toFixed(1)}%` : "Pending"
          }
          sub={`${correct}/${graded} correct · 0.30% margin`}
        />
      </section>
      <div className="section-title">
        <h2>All methods at a glance</h2>
        <span>CLICK TO INSPECT</span>
      </div>
      <section className="method-grid">
        {methods.map((m) => {
          const rows = visible.filter((p) => p.methodName === m),
            checked = rows.filter((p) => p.outcome !== "PENDING"),
            hits = checked.filter((p) => p.outcome === "CORRECT").length,
            up = rows.filter((p) => p.predictedDirection === "UP").length;
          return (
            <button onClick={() => setMethod(m)}>
              <div>
                <strong>{m}</strong>
                <small>{rows.length} markets analyzed</small>
              </div>
              <div className="method-consensus">
                <em className={up >= rows.length / 2 ? "up" : "down"}>
                  {up >= rows.length / 2 ? "UP" : "DOWN"} BIAS
                </em>
                <b>
                  {checked.length
                    ? `${((hits / checked.length) * 100).toFixed(0)}%`
                    : "PENDING"}
                </b>
              </div>
            </button>
          );
        })}
        {!visible.length && (
          <Empty text="This analysis does not contain this checker slice. Older analyses are not changed retroactively; run a new analysis after restarting Tornado." />
        )}
      </section>
      <div className="section-title">
        <h2>Prediction log with current price</h2>
        <span>{horizonLabel(horizon)} SLICE</span>
      </div>
      <History rows={visible} prices={prices} />
    </>
  );
}
function MethodSlice({
  name,
  rows,
  prices,
  back,
}: {
  name: string;
  rows: Prediction[];
  prices: Record<string, string>;
  back: () => void;
}) {
  const graded = rows.filter((p) => p.outcome !== "PENDING"),
    correct = graded.filter((p) => p.outcome === "CORRECT").length;
  return (
    <>
      <button className="back" onClick={back}>
        ← Run report
      </button>
      <section className="metrics">
        <Metric
          label="RUN ACCURACY"
          value={
            graded.length
              ? `${((correct / graded.length) * 100).toFixed(1)}%`
              : "Pending"
          }
          sub={`${correct}/${graded.length} correct`}
        />
        <Metric
          label="UP SIGNALS"
          value={String(
            rows.filter((p) => p.predictedDirection === "UP").length,
          )}
          sub="Across selected markets"
        />
        <Metric
          label="DOWN SIGNALS"
          value={String(
            rows.filter((p) => p.predictedDirection === "DOWN").length,
          )}
          sub="Across selected markets"
        />
      </section>
      <div className="section-title">
        <h2>{name} market breakdown</h2>
        <span>METHOD DETAIL</span>
      </div>
      <History rows={rows} prices={prices} />
    </>
  );
}
function Methods({
  rows,
  open,
}: {
  rows: Leader[];
  open: (s: string) => void;
}) {
  const [horizon, setHorizon] = useState(3600),
    [filtered, setFiltered] = useState(rows);
  useEffect(() => {
    api<Leader[]>(`/api/leaderboard?window=30d&horizon=${horizon}`).then(
      setFiltered,
    );
  }, [horizon]);
  return (
    <>
      <HorizonFilter value={horizon} setValue={setHorizon} />
      <section className="method-report-grid">
        {filtered.map((r, i) => (
          <button onClick={() => open(r.method)}>
            <div className="rank">#{String(i + 1).padStart(2, "0")}</div>
            <div>
              <strong>{r.method}</strong>
              <small>{r.total} graded predictions</small>
            </div>
            <div
              className="donut"
              style={{
                background: `conic-gradient(#72e3a2 ${r.accuracy}%,#232a30 0)`,
              }}
            >
              <span>{r.accuracy.toFixed(0)}%</span>
            </div>
            <ChevronRight />
          </button>
        ))}
        {!filtered.length && (
          <Empty text="Method reports appear after the first predictions are graded." />
        )}
      </section>
    </>
  );
}
function CoinReports({ coins }: { coins: Coin[] }) {
  const [data, setData] = useState<
      {
        coin: string;
        methods: {
          method: string;
          samples: number;
          correct: number;
          accuracy: number;
        }[];
      }[]
    >([]),
    [coinMixes, setCoinMixes] = useState<
      {
        coin: string;
        mixes: {
          methods: string[];
          totalPredictions: number;
          sameDirectionPredictions: number;
          sameDirectionCorrect: number;
          samples: number;
          correct: number;
          accuracy: number;
        }[];
      }[]
    >([]),
    [selected, setSelected] = useState(""),
    [min, setMin] = useState(1),
    [size, setSize] = useState(3),
    [horizon, setHorizon] = useState(3600);
  useEffect(() => {
    api<any[]>(`/api/reports/coins?minSamples=${min}&horizon=${horizon}`).then(
      (x) => {
        setData(x);
      },
    );
  }, [min, horizon]);
  useEffect(() => {
    if (coins.length && !coins.some((x) => x.symbol === selected)) {
      setSelected(coins[0].symbol);
    }
  }, [coins, selected]);
  useEffect(() => {
    api<any[]>(
      `/api/reports/coin-mixes?size=${size}&minSamples=${min}&horizon=${horizon}`,
    ).then(setCoinMixes);
  }, [size, min, horizon]);
  const report = data.find((x) => x.coin === selected),
    mixes = coinMixes.find((x) => x.coin === selected)?.mixes || [];
  return (
    <>
      <HorizonFilter value={horizon} setValue={setHorizon} />
      <ReportFilters
        label="COIN"
        value={selected}
        setValue={setSelected}
        options={coins.map((x) => x.symbol)}
        min={min}
        setMin={setMin}
      />
      <section className="metrics">
        <Metric
          label="MOST ACCURATE METHOD"
          value={report?.methods[0]?.method || "—"}
          sub={
            report?.methods[0]
              ? `${report.methods[0].accuracy.toFixed(1)}% · ${report.methods[0].samples} samples`
              : "More data required"
          }
        />
        <Metric
          label={`BEST ${size}-METHOD MIX`}
          value={mixes[0] ? `${mixes[0].accuracy.toFixed(1)}%` : "—"}
          sub={mixes[0]?.methods.join(" + ") || "More data required"}
        />
        <Metric
          label="MIX EVIDENCE"
          value={String(mixes[0]?.samples || 0)}
          sub="Decisive same-run votes"
        />
      </section>
      <div className="section-title">
        <h2>{selected || "Coin"} method ranking</h2>
        <span>ACCURACY BY COIN</span>
      </div>
      <RankTable
        rows={(report?.methods || []).map((x) => ({
          name: x.method,
          samples: x.samples,
          correct: x.correct,
          accuracy: x.accuracy,
        }))}
      />
      <div className="section-title">
        <h2>Best mixes for {selected || "this coin"}</h2>
        <div className="segmented compact">
          {[2, 3, 4].map((n) => (
            <button
              className={size === n ? "active" : ""}
              onClick={() => setSize(n)}
            >
              {n} methods
            </button>
          ))}
        </div>
      </div>
      <MixGrid
        data={mixes}
        empty={`No ${size}-method mixes meet the ${min}-sample requirement for ${selected}.`}
      />
      <p className="report-note">
        Results only combine predictions from the same coin and named analysis.
        Even-sized mixes skip tied votes.
      </p>
    </>
  );
}
function MixReports() {
  const [data, setData] = useState<
      {
        methods: string[];
        totalPredictions: number;
        sameDirectionPredictions: number;
        sameDirectionCorrect: number;
        samples: number;
        correct: number;
        accuracy: number;
      }[]
    >([]),
    [size, setSize] = useState(3),
    [min, setMin] = useState(1),
    [horizon, setHorizon] = useState(3600);
  useEffect(() => {
    api<any[]>(
      `/api/reports/mixes?size=${size}&minSamples=${min}&horizon=${horizon}`,
    ).then(setData);
  }, [size, min, horizon]);
  return (
    <>
      <HorizonFilter value={horizon} setValue={setHorizon} />
      <div className="report-filter">
        <div>
          <small>MIX SIZE</small>
          <div className="segmented">
            {[2, 3, 4].map((n) => (
              <button
                className={size === n ? "active" : ""}
                onClick={() => setSize(n)}
              >
                {n} methods
              </button>
            ))}
          </div>
        </div>
        <div>
          <small>MINIMUM SAMPLES</small>
          <input
            type="number"
            min="1"
            value={min}
            onChange={(e) => setMin(Math.max(1, +e.target.value))}
          />
        </div>
      </div>
      <section className="metrics">
        <Metric
          label="QUALIFYING MIXES"
          value={String(data.length)}
          sub={`At least ${min} same-market votes`}
        />
        <Metric
          label="BEST MIX"
          value={data[0] ? `${data[0].accuracy.toFixed(1)}%` : "—"}
          sub={data[0]?.methods.join(" + ") || "More data required"}
        />
        <Metric
          label="BEST MIX SAMPLES"
          value={String(data[0]?.samples || 0)}
          sub="Graded consensus decisions"
        />
      </section>
      <div className="section-title">
        <h2>Combination leaderboard</h2>
        <span>
          {size === 2
            ? "UNANIMOUS PAIRS"
            : size === 3
              ? "MAJORITY TRIPLES"
              : "DECISIVE FOUR-METHOD VOTES"}
        </span>
      </div>
      <MixGrid
        data={data}
        empty={`No combinations have ${min} graded samples yet. Lower the minimum or run more analyses.`}
      />
      <p className="report-note">
        Pairs must agree. Triples use majority vote. Four-method mixes use 3–1
        or 4–0 decisions and skip 2–2 ties. Every vote is matched within the
        same named analysis and coin.
      </p>
    </>
  );
}
function MoneyReport({ coins }: { coins: Coin[] }) {
  type R = {
    coin: string;
    horizon: number;
    methods: string[];
    executedTrades: number;
    tradeAmount: number;
    leverage: number;
    totalMarginUsed: number;
    grossPnl: number;
    endingValue: number;
    roiPercent: number;
    wins: number;
    losses: number;
    winRate: number;
    liquidations: number;
    maxDrawdown: number;
    averagePnlPerTrade: number;
    trades: {
      number: number;
      time: string;
      side: string;
      entryPrice: number;
      exitPrice: number;
      marketMovePercent: number;
      pnl: number;
      cumulativePnl: number;
      liquidated: boolean;
    }[];
  };
  const [methods, setMethods] = useState<string[]>([]),
    [selected, setSelected] = useState<string[]>([]),
    [coin, setCoin] = useState("BTC"),
    [horizon, setHorizon] = useState(3600),
    [amount, setAmount] = useState(100),
    [leverage, setLeverage] = useState(5),
    [report, setReport] = useState<R>(),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  useEffect(() => {
    api<{ name: string }[]>("/api/methods").then((x) => {
      const names = x.map((m) => m.name);
      setMethods(names);
      setSelected(names.slice(0, 3));
    });
  }, []);
  useEffect(() => {
    if (coins[0] && !coins.some((c) => c.symbol === coin))
      setCoin(coins[0].symbol);
  }, [coins]);
  const toggle = (m: string) =>
    setSelected((s) =>
      s.includes(m) ? s.filter((x) => x !== m) : s.length < 8 ? [...s, m] : s,
    );
  const run = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      setReport(
        await api<R>("/api/reports/money", {
          method: "POST",
          body: JSON.stringify({
            coin,
            horizon,
            methods: selected,
            tradeAmount: amount,
            leverage,
          }),
        }),
      );
    } catch (x) {
      setError(String(x));
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <div className="money-warning">
        <b>Historical simulator—not a profit guarantee.</b> Gross results
        exclude trading fees, funding, spread, slippage and exchange-specific
        maintenance-margin rules. Liquidation checks the full one-minute
        high/low path; signal success uses the same ±0.30% target-hit rule as
        Coin Mix.
      </div>
      <form className="money-builder panel" onSubmit={run}>
        <div className="money-fields">
          <label>
            COIN
            <select value={coin} onChange={(e) => setCoin(e.target.value)}>
              {coins.map((c) => (
                <option value={c.symbol}>{c.symbol}/USDT</option>
              ))}
            </select>
          </label>
          <label>
            TIME SLICE
            <select
              value={horizon}
              onChange={(e) => setHorizon(+e.target.value)}
            >
              {[60, 900, 1800, 3600, 14400, 43200, 86400].map((x) => (
                <option value={x}>{horizonLabel(x)}</option>
              ))}
            </select>
          </label>
          <label>
            MARGIN / TRADE
            <input
              type="number"
              min="1"
              step="1"
              value={amount}
              onChange={(e) => setAmount(+e.target.value)}
            />
            <span>USDT</span>
          </label>
          <label>
            FUTURES LEVERAGE
            <input
              type="number"
              min="1"
              max="125"
              value={leverage}
              onChange={(e) => setLeverage(+e.target.value)}
            />
            <span>×</span>
          </label>
        </div>
        <div className="method-picker">
          <small>METHOD MIX · SELECT 2–8</small>
          <div>
            {methods.map((m) => (
              <button
                type="button"
                className={selected.includes(m) ? "selected" : ""}
                onClick={() => toggle(m)}
              >
                {m}
              </button>
            ))}
          </div>
        </div>
        {error && <div className="form-error">{error}</div>}
        <button className="run simulate" disabled={busy || selected.length < 2}>
          <Play />
          {busy ? "Calculating…" : "Simulate historical trades"}
        </button>
      </form>
      {report && (
        <>
          <section className="metrics money-metrics">
            <Metric
              label="GROSS P&L"
              value={`${report.grossPnl >= 0 ? "+" : ""}${money(report.grossPnl)} USDT`}
              sub={`${report.roiPercent.toFixed(2)}% on deployed margin`}
            />
            <Metric
              label="SIGNAL SUCCESS RATE"
              value={`${report.winRate.toFixed(1)}%`}
              sub={`${report.wins} successful / ${report.losses} below threshold or wrong`}
            />
            <Metric
              label="ENDING VALUE"
              value={`${money(report.endingValue)} USDT`}
              sub={`${money(report.totalMarginUsed)} USDT total margin used`}
            />
          </section>
          <section className="metrics money-metrics">
            <Metric
              label="AVERAGE / TRADE"
              value={`${money(report.averagePnlPerTrade)} USDT`}
              sub={`${report.executedTrades} eligible trades from first to latest`}
            />
            <Metric
              label="MAX DRAWDOWN"
              value={`${money(report.maxDrawdown)} USDT`}
              sub="Peak-to-trough simulated loss"
            />
            <Metric
              label="LIQUIDATIONS"
              value={String(report.liquidations)}
              sub="Loss capped at trade margin"
            />
          </section>
          <div className="section-title">
            <h2>Simulated trade ledger</h2>
            <span>
              {report.coin} · {horizonLabel(report.horizon)} · {report.leverage}
              ×
            </span>
          </div>
          <section className="panel money-ledger">
            <div className="money-head">
              <span># / TIME</span>
              <span>SIDE</span>
              <span>ENTRY</span>
              <span>EXIT</span>
              <span>MARKET MOVE</span>
              <span>P&amp;L</span>
              <span>CUMULATIVE</span>
            </div>
            {report.trades.map((t) => (
              <article>
                <span>
                  #{t.number} · {new Date(t.time).toLocaleDateString()}
                </span>
                <em className={t.side === "LONG" ? "up" : "down"}>{t.side}</em>
                <span>${money(t.entryPrice)}</span>
                <span>${money(t.exitPrice)}</span>
                <span
                  className={t.marketMovePercent >= 0 ? "positive" : "negative"}
                >
                  {t.marketMovePercent.toFixed(2)}%
                </span>
                <b className={t.pnl >= 0 ? "positive" : "negative"}>
                  {t.liquidated
                    ? "LIQUIDATED"
                    : `${t.pnl >= 0 ? "+" : ""}${money(t.pnl)}`}
                </b>
                <strong>{money(t.cumulativePnl)}</strong>
              </article>
            ))}
          </section>
        </>
      )}
    </>
  );
}
function MixGrid({
  data,
  empty,
}: {
  data: {
    methods: string[];
    totalPredictions: number;
    sameDirectionPredictions: number;
    sameDirectionCorrect: number;
    samples: number;
    correct: number;
    accuracy: number;
  }[];
  empty: string;
}) {
  return (
    <section className="mix-grid">
      {data.map((x, i) => (
        <article>
          <div className="rank">#{String(i + 1).padStart(2, "0")}</div>
          <div className="mix-methods">
            {x.methods.map((m) => (
              <span>{m}</span>
            ))}
          </div>
          <div className="mix-score">
            <strong>{x.accuracy.toFixed(1)}%</strong>
            <small>ALL PREDICTIONS · {x.totalPredictions}</small>
            <small>SAME DIRECTION · {x.sameDirectionPredictions}</small>
            <small>SAME-DIRECTION CORRECT · {x.sameDirectionCorrect}</small>
            <small>DECISIVE PREDICTIONS · {x.samples}</small>
            <small>CORRECT PREDICTIONS · {x.correct}</small>
          </div>
        </article>
      ))}
      {!data.length && <Empty text={empty} />}
    </section>
  );
}
function ReportFilters({
  label,
  value,
  setValue,
  options,
  min,
  setMin,
}: {
  label: string;
  value: string;
  setValue: (s: string) => void;
  options: string[];
  min: number;
  setMin: (n: number) => void;
}) {
  return (
    <div className="report-filter">
      <div>
        <small>{label}</small>
        <select value={value} onChange={(e) => setValue(e.target.value)}>
          {options.map((x) => (
            <option>{x}</option>
          ))}
        </select>
      </div>
      <div>
        <small>MINIMUM SAMPLES</small>
        <input
          type="number"
          min="1"
          value={min}
          onChange={(e) => setMin(Math.max(1, +e.target.value))}
        />
      </div>
    </div>
  );
}
function RankTable({
  rows,
}: {
  rows: { name: string; samples: number; correct: number; accuracy: number }[];
}) {
  return (
    <section className="panel rank-table">
      <div className="rank-head">
        <span>RANK / METHOD</span>
        <span>TARGET HIT</span>
        <span>HITS</span>
        <span>SAMPLES</span>
      </div>
      {rows.map((x, i) => (
        <article>
          <span>
            <i>#{String(i + 1).padStart(2, "0")}</i>
            <b>{x.name}</b>
          </span>
          <span>
            <strong>{x.accuracy.toFixed(1)}%</strong>
            <u>
              <i style={{ width: `${x.accuracy}%` }} />
            </u>
          </span>
          <span>{x.correct}</span>
          <span>{x.samples}</span>
        </article>
      ))}
      {!rows.length && (
        <Empty text="No methods meet the selected sample requirement yet." />
      )}
    </section>
  );
}
function MethodDetail({
  name,
  prices,
  back,
}: {
  name: string;
  prices: Record<string, string>;
  back: () => void;
}) {
  const [data, setData] = useState<{
      total: number;
      graded: number;
      correct: number;
      accuracy: number;
      predictions: Prediction[];
    }>(),
    [horizon, setHorizon] = useState(3600);
  useEffect(() => {
    api<any>(
      `/api/method-report?method=${encodeURIComponent(name)}&horizon=${horizon}`,
    ).then(setData);
  }, [name, horizon]);
  if (!data) return <Empty text="Loading method report…" />;
  const byCoin = [...new Set(data.predictions.map((p) => p.coin.symbol))].map(
    (c) => {
      const x = data.predictions.filter(
        (p) => p.coin.symbol === c && p.outcome !== "PENDING",
      );
      return {
        coin: c,
        total: x.length,
        accuracy: x.length
          ? (x.filter((p) => p.outcome === "CORRECT").length / x.length) * 100
          : 0,
      };
    },
  );
  return (
    <>
      <button className="back" onClick={back}>
        ← All methods
      </button>
      <HorizonFilter value={horizon} setValue={setHorizon} />
      <section className="metrics">
        <Metric
          label="TARGET-HIT RATE"
          value={`${data.accuracy.toFixed(1)}%`}
          sub={`${data.correct}/${data.graded} correct`}
        />
        <Metric
          label="TOTAL SIGNALS"
          value={String(data.total)}
          sub={`${data.total - data.graded} still pending`}
        />
        <Metric
          label="BEST MARKET"
          value={byCoin.sort((a, b) => b.accuracy - a.accuracy)[0]?.coin || "—"}
          sub="By historical target-hit rate"
        />
      </section>
      <div className="section-title">
        <h2>Target-hit rate by market</h2>
        <span>HISTORICAL</span>
      </div>
      <section className="coin-bars panel">
        {byCoin.map((x) => (
          <div>
            <b>{x.coin}</b>
            <span>
              <i style={{ width: `${x.accuracy}%` }} />
            </span>
            <strong>{x.total ? x.accuracy.toFixed(1) : "—"}%</strong>
            <small>{x.total} graded</small>
          </div>
        ))}
      </section>
      <div className="section-title">
        <h2>Recent predictions</h2>
      </div>
      <History rows={data.predictions.slice(0, 100)} prices={prices} />
    </>
  );
}
function MarketChart({ pair }: { pair: string }) {
  const [data, setData] = useState<Candle[]>([]);
  useEffect(() => {
    if (pair)
      api<Candle[]>(`/api/chart/${pair}?interval=15m&limit=96`)
        .then(setData)
        .catch(() => setData([]));
  }, [pair]);
  if (data.length < 2)
    return <div className="chart-loading">Loading Binance candles…</div>;
  const nums = data.flatMap((d) => [+d.high, +d.low]),
    min = Math.min(...nums),
    max = Math.max(...nums),
    w = 1000,
    h = 260,
    pad = 18,
    x = (i: number) => pad + (i * (w - pad * 2)) / data.length,
    y = (v: number) => pad + ((max - v) / (max - min)) * (h - pad * 2),
    cw = Math.max(2, ((w - pad * 2) / data.length) * 0.55);
  return (
    <svg
      className="market-chart"
      viewBox={`0 0 ${w} ${h}`}
      preserveAspectRatio="none"
    >
      <defs>
        <linearGradient id="area" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#67dc9a" stopOpacity=".16" />
          <stop offset="1" stopColor="#67dc9a" stopOpacity="0" />
        </linearGradient>
      </defs>
      {[0.25, 0.5, 0.75].map((n) => (
        <line x1="0" x2={w} y1={h * n} y2={h * n} className="gridline" />
      ))}
      <path
        d={`M ${data.map((d, i) => `${x(i)},${y(+d.close)}`).join(" L ")} L ${x(data.length - 1)},${h} L 0,${h} Z`}
        fill="url(#area)"
      />
      {data.map((d, i) => {
        const up = +d.close >= +d.open;
        return (
          <g className={up ? "candle-up" : "candle-down"}>
            <line x1={x(i)} x2={x(i)} y1={y(+d.high)} y2={y(+d.low)} />
            <rect
              x={x(i) - cw / 2}
              y={Math.min(y(+d.open), y(+d.close))}
              width={cw}
              height={Math.max(1, Math.abs(y(+d.open) - y(+d.close)))}
            />
          </g>
        );
      })}
    </svg>
  );
}
function History({
  rows,
  prices,
}: {
  rows: Prediction[];
  prices: Record<string, string>;
}) {
  return (
    <section className="panel history">
      <div className="history-head">
        <span>TIME</span>
        <span>MARKET</span>
        <span>METHOD</span>
        <span>CALL</span>
        <span>ENTRY</span>
        <span>CURRENT / EXIT</span>
        <span>MOVE</span>
        <span>RESULT</span>
      </div>
      {rows.map((p) => {
        const current = Number(p.priceAtGrading || prices[p.coin.pair]),
          delta = current
            ? ((current - p.priceAtPrediction) / p.priceAtPrediction) * 100
            : 0;
        return (
          <article>
            <time>{ago(p.predictedAt)}</time>
            <b>{p.coin.symbol}</b>
            <span>{p.methodName}</span>
            <em className={p.predictedDirection.toLowerCase()}>
              {p.predictedDirection}
            </em>
            <span>${money(p.priceAtPrediction)}</span>
            <span>${money(current || undefined)}</span>
            <span className={delta >= 0 ? "positive" : "negative"}>
              {delta >= 0 ? <ArrowUpRight /> : <ArrowDownRight />}
              {delta.toFixed(2)}%
            </span>
            <mark className={p.outcome.toLowerCase()}>{p.outcome}</mark>
          </article>
        );
      })}
      {!rows.length && <Empty text="No predictions logged yet." />}
    </section>
  );
}
function RunDialog({
  close,
  done,
}: {
  close: () => void;
  done: (id: number) => void;
}) {
  const [name, setName] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      const r = await api<{ runId: number }>("/api/run-now", {
        method: "POST",
        body: JSON.stringify({ name }),
      });
      done(r.runId);
    } catch (x) {
      setError(String(x));
      setBusy(false);
    }
  };
  return (
    <div className="modal">
      <form onSubmit={submit}>
        <button type="button" className="close" onClick={close}>
          <X />
        </button>
        <div className="modal-icon">
          <LineChart />
        </div>
        <h2>Run new analysis</h2>
        <p>
          Name this snapshot so you can compare it later. Tornado automatically
          creates 1m, 15m, 30m, 1h, 4h, 12h and 24h checker slices for every
          signal.
        </p>
        <label>
          ANALYSIS NAME
          <input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Morning market scan"
            required
            maxLength={120}
          />
        </label>
        <div className="auto-slices">
          <span>1 MIN</span>
          <span>15 MIN</span>
          <span>30 MIN</span>
          <span>1 HOUR</span>
          <span>4 HOURS</span>
          <span>12 HOURS</span>
          <span>24 HOURS</span>
        </div>
        {error && <div className="form-error">{error}</div>}
        <button className="run submit" disabled={busy}>
          <Play />
          {busy ? "Analyzing all markets…" : "Start analysis"}
        </button>
      </form>
    </div>
  );
}
function horizonLabel(seconds: number) {
  return seconds === 0
    ? "All slices"
    : seconds === 60
      ? "1 min"
      : seconds === 900
        ? "15 min"
        : seconds === 1800
          ? "30 min"
          : seconds === 3600
            ? "1 hour"
            : seconds === 14400
              ? "4 hours"
              : seconds === 43200
                ? "12 hours"
                : "24 hours";
}
function HorizonFilter({
  value,
  setValue,
  includeAll = false,
}: {
  value: number;
  setValue: (n: number) => void;
  includeAll?: boolean;
}) {
  const slices = includeAll
    ? [0, 60, 900, 1800, 3600, 14400, 43200, 86400]
    : [60, 900, 1800, 3600, 14400, 43200, 86400];
  return (
    <div className="horizon-filter">
      <small>TIME SLICE</small>
      <div className="segmented">
        {slices.map((s) => (
          <button
            className={value === s ? "active" : ""}
            onClick={() => setValue(s)}
          >
            {horizonLabel(s)}
          </button>
        ))}
      </div>
      <em>Correct only after ±0.30% movement</em>
    </div>
  );
}
function Metric({
  label,
  value,
  sub,
}: {
  label: string;
  value: string;
  sub: string;
}) {
  return (
    <article>
      <small>{label}</small>
      <strong>{value}</strong>
      <p>{sub}</p>
    </article>
  );
}
function SettingsView({
  coins,
  reload,
}: {
  coins: Coin[];
  reload: () => void;
}) {
  const [symbol, setSymbol] = useState(""),
    [pair, setPair] = useState("");
  const add = async (e: React.FormEvent) => {
    e.preventDefault();
    await api("/api/coins", {
      method: "POST",
      body: JSON.stringify({ symbol, pair }),
    });
    setSymbol("");
    setPair("");
    reload();
  };
  return (
    <>
      <section className="panel settings">
        <h2>Add Binance market</h2>
        <form onSubmit={add}>
          <label>
            SYMBOL
            <input
              value={symbol}
              onChange={(e) => setSymbol(e.target.value)}
              placeholder="BTC"
              required
            />
          </label>
          <label>
            PAIR
            <input
              value={pair}
              onChange={(e) => setPair(e.target.value)}
              placeholder="BTCUSDT"
              required
            />
          </label>
          <button>
            <Plus />
            Add coin
          </button>
        </form>
      </section>
      <section className="panel settings">
        <h2>Active universe</h2>
        {coins.map((c) => (
          <div className="setting-row">
            <span>
              <b>{c.symbol}</b>
              <small>{c.pair}</small>
            </span>
            <button
              onClick={async () => {
                await fetch(`/api/coins/${c.id}`, { method: "DELETE" });
                reload();
              }}
            >
              <Trash2 />
            </button>
          </div>
        ))}
      </section>
    </>
  );
}
function Empty({ text }: { text: string }) {
  return <div className="empty">{text}</div>;
}
