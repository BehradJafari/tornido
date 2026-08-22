package io.tornado.datafetch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tornado.config.TornadoProperties;
import io.tornado.persistence.CoinRepository;
import io.tornado.reporting.MixTradeSimulationService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Relays Binance aggregate-trade ticks to the dashboard and feeds open mix-trade
 * simulations. Two properties keep the live feed safe and responsive:
 *
 * <ul>
 *   <li>The WebSocket is managed through a connection-generation token. Stale
 *       close/error/build callbacks from a superseded connection can never clear
 *       or replace a newer connection, and only one connection attempt is ever
 *       in flight.</li>
 *   <li>Historical recovery for open simulations runs on a bounded dedicated
 *       executor instead of blocking the single WebSocket dispatch thread.
 *       Recovery is coordinated per pair: live ticks arriving while a recovery
 *       is outstanding are buffered and replayed in aggregate-ID order so
 *       historical milestones can never be rejected as older.</li>
 * </ul>
 */
@Component
public class LivePriceStream {
    private static final Logger log = LoggerFactory.getLogger(LivePriceStream.class);
    private static final int MAX_PENDING_TICKS_PER_PAIR = 10_000;
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(60);
    private static final Duration BACKOFF_MAX = Duration.ofMinutes(30);
    private static final int BACKOFF_EXPONENT_CAP = 6;

    private final CoinRepository coins;
    private final TornadoProperties props;
    private final ObjectMapper json;
    private final MixTradeSimulationService simulations;
    private final Duration maximumRecoveryLookback;
    private final Clock clock;
    private final ScheduledExecutorService recoveryExecutor;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Sinks.Many<PriceTick> sink = Sinks.many().multicast().directBestEffort();
    private final Map<String, PriceTick> latest = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAggregateIds = new ConcurrentHashMap<>();
    private final Map<String, PairRecoveryState> recoveryStates = new ConcurrentHashMap<>();

    private volatile ConnectionState active;
    private volatile boolean shuttingDown;
    private long generationCounter;

    @org.springframework.beans.factory.annotation.Autowired
    public LivePriceStream(CoinRepository coins, TornadoProperties props, ObjectMapper json, MixTradeSimulationService simulations) {
        this(coins, props, json, simulations, Clock.systemUTC(),
                Executors.newScheduledThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "tornado-recovery");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    LivePriceStream(CoinRepository coins, TornadoProperties props, ObjectMapper json, MixTradeSimulationService simulations,
                    Clock clock, ScheduledExecutorService recoveryExecutor) {
        this.coins = coins;
        this.props = props;
        this.json = json;
        this.simulations = simulations;
        this.clock = clock;
        this.recoveryExecutor = recoveryExecutor;
        this.maximumRecoveryLookback = props.binance().maximumRecoveryLookback();
    }

    public Flux<PriceTick> flux() {
        return sink.asFlux();
    }

    public Map<String, PriceTick> latest() {
        return Map.copyOf(latest);
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 30000)
    public synchronized void ensureSubscription() {
        if (shuttingDown) return;
        String streams = subscribedStreams();
        ConnectionState current = active;
        if (streams.isBlank()) {
            if (current != null) {
                current.superseded = true;
                active = null;
                WebSocket previous = current.socket;
                if (previous != null) {
                    try { previous.sendClose(WebSocket.NORMAL_CLOSURE, "no active streams"); } catch (RuntimeException ignored) { }
                }
                log.info("Binance live stream stopped: no active coin subscriptions");
            }
            return;
        }
        if (current != null && !current.superseded) {
            if (current.streams.equals(streams)) {
                if (current.connecting) return;        // an attempt is already in flight
                if (current.socket != null) return;    // healthy connection
            } else {
                log.info("Controlled resubscription: live stream generation {} switching {} -> {}",
                        current.generation, streamCount(current.streams), streamCount(streams));
            }
            current.superseded = true;
            WebSocket previous = current.socket;
            if (previous != null) {
                try { previous.sendClose(WebSocket.NORMAL_CLOSURE, "resubscribe"); } catch (RuntimeException ignored) { }
            }
        }
        ConnectionState next = new ConnectionState(++generationCounter, streams);
        active = next;
        log.info("Opening Binance WebSocket connection generation {} ({} streams)", next.generation, streamCount(streams));
        connect(next);
    }

    String subscribedStreams() {
        return coins.findAllByActiveTrueOrderBySymbol().stream()
                .map(coin -> coin.getPair().toLowerCase() + "@aggTrade")
                .collect(Collectors.joining("/"));
    }

    private void connect(ConnectionState state) {
        String url = props.binance().websocketBaseUrl() + "/stream?streams=" + state.streams;
        try {
            CompletableFuture<WebSocket> attempt = openSocket(state, url);
            if (attempt == null) {
                completeAttempt(state, null, new IllegalStateException("connection attempt was not created"));
                return;
            }
            attempt.whenComplete((ws, error) -> completeAttempt(state, ws, error));
        } catch (RuntimeException error) {
            completeAttempt(state, null, error);
        }
    }

    /** Test seam; production opens the real Binance socket. */
    CompletableFuture<WebSocket> openSocket(ConnectionState state, String url) {
        return client.newWebSocketBuilder().buildAsync(URI.create(url), new Listener(state));
    }

    private void completeAttempt(ConnectionState state, WebSocket ws, Throwable error) {
        synchronized (this) {
            if (active != state || state.superseded) {
                if (ws != null) {
                    try { ws.abort(); } catch (RuntimeException ignored) { }
                }
                log.info("Stale WebSocket connection attempt ignored (generation {})", state.generation);
                return;
            }
            state.connecting = false;
            if (error != null) {
                log.warn("Binance WebSocket connection attempt {} failed: {}", state.generation, error.getMessage());
                return; // active stays; the scheduled job retries
            }
            state.socket = ws;
            log.info("Binance live stream connected (generation {}, {} streams)", state.generation, streamCount(state.streams));
        }
    }

    /** Parsed tick entry point shared by the WebSocket listener and tests. */
    void handleTick(String pair, String price, long timestamp, long aggregateId) {
        PriceTick tick = new PriceTick(pair, price, timestamp);
        latest.put(pair, tick);
        sink.tryEmitNext(tick);
        BigDecimal tickPrice = new BigDecimal(price);
        Instant at = Instant.ofEpochMilli(timestamp);
        Long previous = lastAggregateIds.put(pair, aggregateId);
        boolean firstOrGap = previous == null || aggregateId > previous + 1;
        if (firstOrGap) {
            requestRecovery(pair, at);
        }
        PairRecoveryState state = recoveryStates.get(pair);
        boolean buffered = false;
        if (state != null) {
            synchronized (state) {
                // While a recovery is outstanding, hold live ticks so historical and live
                // observations are always applied in aggregate-ID order per pair.
                if (state.requestedThrough != null || state.running) {
                    state.pending.addLast(new PendingTick(tickPrice, at, aggregateId));
                    if (state.pending.size() > MAX_PENDING_TICKS_PER_PAIR) {
                        PendingTick dropped = state.pending.removeFirst();
                        // A dropped buffered tick must be recovered through REST. Without
                        // advancing this cursor, lastAggregateIds has already moved past it
                        // and no later gap can repair the lost TP/SL observation.
                        if (state.requestedThrough == null || dropped.at.isAfter(state.requestedThrough)) {
                            state.requestedThrough = dropped.at;
                        }
                        if (!state.overflowWarned) {
                            state.overflowWarned = true;
                            log.warn("Recovery tick buffer overflow for {}; tick {}@{} moved into the REST replay range",
                                    pair, dropped.aggregateId, dropped.at);
                        }
                    }
                    buffered = true;
                }
            }
        }
        if (!buffered) {
            try {
                simulations.observe(pair, tickPrice, at, aggregateId);
            } catch (Exception error) {
                log.warn("Mix simulation observation failed for {} without affecting live prices: {}", pair, error.getMessage());
            }
        }
    }

    private void requestRecovery(String pair, Instant through) {
        if (shuttingDown) return;
        PairRecoveryState state = recoveryStates.computeIfAbsent(pair, PairRecoveryState::new);
        boolean start;
        synchronized (state) {
            if (state.requestedThrough == null || through.isAfter(state.requestedThrough)) {
                state.requestedThrough = through;
            }
            start = !state.running && (state.retryNotBefore == null || !clock.instant().isBefore(state.retryNotBefore));
            if (start) {
                state.running = true;
            } else {
                log.debug("Recovery request for {} coalesced to {}", pair, state.requestedThrough);
            }
        }
        if (!start) return;
        log.info("Recovery scheduled for {} up to {}", pair, through);
        try {
            recoveryExecutor.execute(() -> runRecovery(state));
        } catch (RuntimeException error) {
            synchronized (state) { state.running = false; }
            log.warn("Could not submit recovery for {}: {}", pair, error.getMessage());
        }
    }

    /** Restarts recovery for pairs whose backoff deadline has passed. */
    @Scheduled(initialDelay = 15000, fixedDelay = 15000)
    void sweepRecovery() {
        if (shuttingDown) return;
        for (PairRecoveryState state : recoveryStates.values()) {
            boolean start;
            synchronized (state) {
                start = !state.running && state.requestedThrough != null
                        && (state.retryNotBefore == null || !clock.instant().isBefore(state.retryNotBefore));
                if (start) state.running = true;
            }
            if (start) {
                log.info("Retrying recovery for {} up to {}", state.pair, state.requestedThrough);
                try {
                    recoveryExecutor.execute(() -> runRecovery(state));
                } catch (RuntimeException error) {
                    synchronized (state) { state.running = false; }
                    log.warn("Could not submit retried recovery for {}: {}", state.pair, error.getMessage());
                }
            }
        }
    }

    private void runRecovery(PairRecoveryState state) {
        String pair = state.pair;
        Instant startedAt = clock.instant();
        try {
            if (!simulations.hasOpenSimulations(pair)) {
                synchronized (state) {
                    state.pending.clear();
                    state.requestedThrough = null;
                    state.running = false;
                    state.failureCount = 0;
                    state.retryNotBefore = null;
                }
                log.debug("Recovery skipped for {}: no open simulations", pair);
                return;
            }
            while (true) {
                Instant through;
                synchronized (state) { through = state.requestedThrough; }
                if (through == null) break;
                simulations.recover(pair, through, maximumRecoveryLookback);
                boolean more;
                synchronized (state) {
                    // Ticks at or before the replayed range were covered by the download.
                    if (!state.pending.isEmpty()) {
                        state.pending.removeIf(tick -> !tick.at.isAfter(through));
                    }
                    if (state.requestedThrough != null && !state.requestedThrough.isAfter(through)) {
                        state.requestedThrough = null;
                    }
                    more = state.requestedThrough != null; // a newer coalesced request arrived
                }
                if (more) continue; // loop and replay the newer range; ticks stay buffered until the final pass
                // Final pass: drain buffered ticks. Returns true only if a late tick advanced
                // the request while draining, in which case the loop must resume.
                if (!drainBufferedTicks(state)) break;
            }
            synchronized (state) {
                state.failureCount = 0;
                state.retryNotBefore = null;
            }
            log.info("Recovery completed for {} in {} ms", pair, Duration.between(startedAt, clock.instant()).toMillis());
        } catch (Exception error) {
            synchronized (state) {
                state.running = false;
                state.failureCount++;
                Duration delay = backoffDelay(state.failureCount);
                state.retryNotBefore = clock.instant().plus(delay);
            }
            log.warn("Recovery failed for {} (attempt {}, up to {}): {}; next retry at {}",
                    pair, state.failureCount, state.requestedThrough, error.getMessage(), state.retryNotBefore);
        }
    }

    /**
     * Applies buffered live ticks in arrival order. Returns {@code true} when the
     * caller should resume the recovery pass loop (a newer coalesced request
     * arrived), and {@code false} when the pair can be handed back to live
     * observation. The finish decision and the buffer are guarded by the same
     * lock as {@link #handleTick}, so no tick can be stranded between states.
     */
    private boolean drainBufferedTicks(PairRecoveryState state) {
        for (;;) {
            List<PendingTick> batch;
            synchronized (state) {
                if (state.pending.isEmpty()) {
                    if (state.requestedThrough == null) {
                        state.running = false;
                        return false;
                    }
                    return true; // newer request arrived; loop back to the pass loop
                }
                batch = orderedUniqueTicks(state.pending);
                state.pending.clear();
            }
            for (PendingTick tick : batch) {
                try {
                    simulations.observe(state.pair, tick.price, tick.at, tick.aggregateId);
                } catch (Exception error) {
                    log.warn("Buffered tick observation failed for {} (aggregate {}) without affecting other ticks: {}",
                            state.pair, tick.aggregateId, error.getMessage());
                }
            }
        }
    }

    static List<PendingTick> orderedUniqueTicks(Iterable<PendingTick> ticks) {
        List<PendingTick> ordered = new ArrayList<>();
        ticks.forEach(ordered::add);
        ordered.sort(Comparator.comparing(PendingTick::at).thenComparingLong(PendingTick::aggregateId));
        Set<Long> seenAggregateIds = new HashSet<>();
        ordered.removeIf(tick -> !seenAggregateIds.add(tick.aggregateId));
        return ordered;
    }

    private static Duration backoffDelay(int failureCount) {
        long exponent = Math.min(failureCount - 1, BACKOFF_EXPONENT_CAP);
        Duration delay = BACKOFF_BASE.multipliedBy(1L << exponent);
        return delay.compareTo(BACKOFF_MAX) > 0 ? BACKOFF_MAX : delay;
    }

    private static int streamCount(String streams) {
        return streams.split("/").length;
    }

    @PreDestroy
    void close() {
        shuttingDown = true;
        ConnectionState state = active;
        active = null;
        if (state != null && state.socket != null) {
            try { state.socket.abort(); } catch (RuntimeException ignored) { }
        }
        recoveryExecutor.shutdown();
        try {
            if (!recoveryExecutor.awaitTermination(5, TimeUnit.SECONDS)) recoveryExecutor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            recoveryExecutor.shutdownNow();
        }
    }

    ConnectionState activeState() {
        return active;
    }

    PairRecoveryState recoveryState(String pair) {
        return recoveryStates.get(pair);
    }

    /** A single WebSocket connection generation; stale generations never touch newer state. */
    static final class ConnectionState {
        final long generation;
        final String streams;
        volatile WebSocket socket;
        volatile boolean connecting = true;
        volatile boolean superseded;

        ConnectionState(long generation, String streams) {
            this.generation = generation;
            this.streams = streams;
        }
    }

    /** Per-pair recovery coordination; at most one recovery task per pair at any time. */
    static final class PairRecoveryState {
        final String pair;
        Instant requestedThrough;
        boolean running;
        Instant retryNotBefore;
        int failureCount;
        boolean overflowWarned;
        final ArrayDeque<PendingTick> pending = new ArrayDeque<>();

        PairRecoveryState(String pair) {
            this.pair = pair;
        }
    }

    record PendingTick(BigDecimal price, Instant at, long aggregateId) {}

    class Listener implements WebSocket.Listener {
        private final ConnectionState state;
        private final StringBuilder text = new StringBuilder();

        Listener(ConnectionState state) {
            this.state = state;
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                try {
                    if (active != state || state.superseded || shuttingDown) {
                        log.debug("Stale WebSocket data ignored (generation {})", state.generation);
                        return null;
                    }
                    var node = json.readTree(text.toString()).get("data");
                    String pair = node.get("s").asText();
                    long aggregateId = node.get("a").asLong();
                    long timestamp = node.get("T").asLong();
                    handleTick(pair, node.get("p").asText(), timestamp, aggregateId);
                } catch (Exception error) {
                    log.debug("Bad aggregate-trade message: {}", error.getMessage());
                } finally {
                    text.setLength(0);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            synchronized (LivePriceStream.this) {
                if (active == state && !state.superseded) {
                    active = null;
                    log.info("Binance live stream closed (generation {}, code {}: {})", state.generation, code, reason);
                } else {
                    log.info("Stale close callback ignored (generation {})", state.generation);
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("Binance live stream error (generation {}): {}", state.generation, error.getMessage());
            synchronized (LivePriceStream.this) {
                if (active == state && !state.superseded) {
                    active = null;
                } else {
                    log.info("Stale error callback ignored (generation {})", state.generation);
                }
            }
        }
    }

    public record PriceTick(String pair, String price, long timestamp) {}
}
