package io.tornado.datafetch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tornado.config.TornadoProperties;
import io.tornado.reporting.MixTradeSimulationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class LivePriceStreamTest {
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void staleOnCloseCannotClearNewerSocket() {
        TestStream stream = newStream();
        stream.ensureSubscription();
        LivePriceStream.ConnectionState a = stream.attemptedStates.get(0);
        StubWebSocket wsA = new StubWebSocket();
        stream.attempts.get(0).complete(wsA);
        stream.streams = "BTCUSDT@aggTrade/ETHUSDT@aggTrade";
        stream.ensureSubscription();
        LivePriceStream.ConnectionState b = stream.attemptedStates.get(1);
        StubWebSocket wsB = new StubWebSocket();
        stream.attempts.get(1).complete(wsB);
        assertThat(stream.activeState()).isSameAs(b);

        stream.new Listener(a).onClose(wsA, WebSocket.NORMAL_CLOSURE, "old connection closed");

        assertThat(stream.activeState()).isSameAs(b);
        assertThat(stream.activeState().socket).isSameAs(wsB);
    }

    @Test
    void staleOnErrorCannotClearNewerSocket() {
        TestStream stream = newStream();
        stream.ensureSubscription();
        LivePriceStream.ConnectionState a = stream.attemptedStates.get(0);
        StubWebSocket wsA = new StubWebSocket();
        stream.attempts.get(0).complete(wsA);
        stream.streams = "BTCUSDT@aggTrade/ETHUSDT@aggTrade";
        stream.ensureSubscription();
        LivePriceStream.ConnectionState b = stream.attemptedStates.get(1);
        StubWebSocket wsB = new StubWebSocket();
        stream.attempts.get(1).complete(wsB);

        stream.new Listener(a).onError(wsA, new RuntimeException("stale error"));

        assertThat(stream.activeState()).isSameAs(b);
        assertThat(stream.activeState().socket).isSameAs(wsB);
    }

    @Test
    void staleFailedBuildCannotClearNewerSocket() {
        TestStream stream = newStream();
        stream.ensureSubscription();
        LivePriceStream.ConnectionState a = stream.attemptedStates.get(0);
        stream.streams = "BTCUSDT@aggTrade/ETHUSDT@aggTrade";
        stream.ensureSubscription();                 // supersedes A while A is still connecting
        LivePriceStream.ConnectionState b = stream.attemptedStates.get(1);
        StubWebSocket wsB = new StubWebSocket();
        stream.attempts.get(1).complete(wsB);

        stream.attempts.get(0).completeExceptionally(new RuntimeException("connection refused"));

        assertThat(stream.activeState()).isSameAs(b);
        assertThat(stream.activeState().socket).isSameAs(wsB);
        assertThat(wsB.aborted).isFalse();
    }

    @Test
    void scheduledCallsDoNotCreateOverlappingConnectionAttempts() {
        TestStream stream = newStream();
        stream.ensureSubscription();
        stream.ensureSubscription();                 // connecting attempt already in flight
        assertThat(stream.attempts).hasSize(1);
        stream.attempts.get(0).complete(new StubWebSocket());
        stream.ensureSubscription();                 // healthy connection
        assertThat(stream.attempts).hasSize(1);
    }

    @Test
    void changingCoinSubscriptionsPerformsOneControlledResubscription() {
        TestStream stream = newStream();
        stream.ensureSubscription();
        LivePriceStream.ConnectionState a = stream.attemptedStates.get(0);
        StubWebSocket wsA = new StubWebSocket();
        stream.attempts.get(0).complete(wsA);
        stream.streams = "BTCUSDT@aggTrade/ETHUSDT@aggTrade";
        stream.ensureSubscription();
        LivePriceStream.ConnectionState b = stream.attemptedStates.get(1);
        assertThat(stream.attempts).hasSize(2);
        assertThat(wsA.sentCloses).isNotEmpty();     // previous socket closed safely
        StubWebSocket wsB = new StubWebSocket();
        stream.attempts.get(1).complete(wsB);
        assertThat(stream.activeState()).isSameAs(b);
        assertThat(stream.activeState().socket).isSameAs(wsB);
        assertThat(wsB.aborted).isFalse();
    }

    @Test
    void emptyCoinSubscriptionsCloseTheCurrentSocketWithoutOpeningAnother() {
        TestStream stream = newStream();
        stream.ensureSubscription();
        StubWebSocket ws = new StubWebSocket();
        stream.attempts.get(0).complete(ws);

        stream.streams = "";
        stream.ensureSubscription();

        assertThat(stream.activeState()).isNull();
        assertThat(stream.attempts).hasSize(1);
        assertThat(ws.sentCloses).anyMatch(close -> close.contains("no active streams"));
    }

    @Test
    void supersededListenerCannotPublishOrObserveLateTicks() {
        FakeSimulations simulations = new FakeSimulations();
        TestStream stream = new TestStream(simulations, new FakeClock(), executor(1));
        stream.ensureSubscription();
        LivePriceStream.ConnectionState old = stream.attemptedStates.get(0);
        StubWebSocket oldSocket = new StubWebSocket();
        stream.attempts.get(0).complete(oldSocket);
        stream.streams = "BTCUSDT@aggTrade/ETHUSDT@aggTrade";
        stream.ensureSubscription();

        String message = "{\"data\":{\"s\":\"BTCUSDT\",\"a\":42,\"T\":1000,\"p\":\"100.0\"}}";
        stream.new Listener(old).onText(oldSocket, message, true);

        assertThat(stream.latest()).doesNotContainKey("BTCUSDT");
        assertThat(simulations.recoverCalls).isEmpty();
        assertThat(simulations.observeCalls).isEmpty();
    }

    @Test
    void shutdownClosesSocketAndStopsFurtherReconnects() {
        ScheduledThreadPoolExecutor exec = executor(1);
        TestStream stream = new TestStream(new FakeSimulations(), new FakeClock(), exec);
        stream.ensureSubscription();
        StubWebSocket ws = new StubWebSocket();
        stream.attempts.get(0).complete(ws);

        stream.close();

        assertThat(ws.aborted).isTrue();
        assertThat(exec.isShutdown()).isTrue();
        int attempts = stream.attempts.size();
        stream.streams = "ETHUSDT@aggTrade";
        stream.ensureSubscription();
        assertThat(stream.attempts).hasSize(attempts);
    }

    // ---------------------------------------------------------------- recovery

    @Test
    void recoveryIsScheduledOffTheWebSocketThread() throws Exception {
        ScheduledThreadPoolExecutor exec = executor(1);
        CountDownLatch occupy = new CountDownLatch(1);
        exec.execute(() -> { try { occupy.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }); // hold the only worker
        FakeSimulations sims = new FakeSimulations();
        CountDownLatch recovered = new CountDownLatch(1);
        sims.recoverDone = recovered;
        TestStream stream = new TestStream(sims, new FakeClock(), exec);

        stream.handleTick("BTCUSDT", "100", 1000, 10);

        assertThat(sims.recoverCalls).isEmpty();     // recovery did NOT run synchronously on the tick thread
        occupy.countDown();
        assertThat(recovered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(sims.recoverCalls).hasSize(1);
    }

    @Test
    void onlyOneRecoveryPerPairRunsAndRequestsCoalesceToTheNewestTarget() throws Exception {
        ScheduledThreadPoolExecutor exec = executor(2);
        FakeSimulations sims = new FakeSimulations();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sims.recoverStarted = firstStarted;
        sims.recoverRelease = release;
        TestStream stream = new TestStream(sims, new FakeClock(), exec);

        stream.handleTick("BTCUSDT", "100", 1000, 10);     // T1: recovery starts and blocks
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        stream.handleTick("BTCUSDT", "100.2", 2000, 200);  // T2: coalesced + buffered while running
        stream.handleTick("BTCUSDT", "100.3", 3000, 300);  // T3: coalesces to the newest target
        assertThat(sims.recoverCalls).hasSize(1);          // still only the blocked first invocation

        release.countDown();
        awaitUntil(() -> sims.recoverCalls.size() >= 2);
        assertThat(sims.recoverCalls).extracting(c -> c.through().toEpochMilli()).containsExactly(1000L, 3000L);
        awaitUntil(() -> stream.recoveryState("BTCUSDT") != null && !stream.recoveryState("BTCUSDT").running);
        assertThat(sims.observeCalls).isEmpty();           // buffered ticks were covered by the replay, not double-applied
    }

    @Test
    void recoveryFailureUsesBoundedBackoffInsteadOfRetryingEveryTick() {
        ScheduledThreadPoolExecutor exec = executor(1);
        FakeClock clock = new FakeClock();
        FakeSimulations sims = new FakeSimulations();
        sims.failRecover = true;
        TestStream stream = new TestStream(sims, clock, exec);

        stream.handleTick("BTCUSDT", "100", 1000, 10);
        awaitUntil(() -> {
            LivePriceStream.PairRecoveryState s = stream.recoveryState("BTCUSDT");
            return s != null && !s.running && s.failureCount == 1;
        });
        LivePriceStream.PairRecoveryState state = stream.recoveryState("BTCUSDT");
        assertThat(state.retryNotBefore).isEqualTo(clock.instant().plus(Duration.ofSeconds(60)));
        assertThat(state.requestedThrough).isNotNull();

        int calls = sims.recoverCalls.size();
        stream.handleTick("BTCUSDT", "100.1", 2000, 20);
        stream.handleTick("BTCUSDT", "100.2", 3000, 30);
        assertThat(sims.recoverCalls).hasSize(calls);      // backoff: no retry on every tick

        clock.now = clock.now.plus(Duration.ofMinutes(2)); // backoff expired
        sims.failRecover = false;
        stream.sweepRecovery();
        awaitUntil(() -> sims.recoverCalls.size() == calls + 1);
        assertThat(sims.recoverCalls.get(calls).through().toEpochMilli()).isEqualTo(3000L); // coalesced through backoff
        awaitUntil(() -> {
            LivePriceStream.PairRecoveryState s = stream.recoveryState("BTCUSDT");
            return !s.running && s.failureCount == 0 && s.retryNotBefore == null;
        });
    }

    @Test
    void differentPairsRecoverIndependently() throws Exception {
        ScheduledThreadPoolExecutor exec = executor(2);
        FakeSimulations sims = new FakeSimulations();
        CountDownLatch aStarted = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        sims.blockedPair = "BTCUSDT";
        sims.recoverStarted = aStarted;
        sims.recoverRelease = releaseA;
        TestStream stream = new TestStream(sims, new FakeClock(), exec);

        stream.handleTick("BTCUSDT", "100", 1000, 10);
        assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();   // A blocked on thread 1
        stream.handleTick("ETHUSDT", "200", 1000, 5);
        awaitUntil(() -> sims.recoverCalls.stream().anyMatch(c -> c.pair().equals("ETHUSDT"))); // B progressed on thread 2
        assertThat(sims.recoverCalls).extracting(c -> c.pair()).contains("BTCUSDT", "ETHUSDT");

        releaseA.countDown();
        awaitUntil(() -> {
            LivePriceStream.PairRecoveryState s = stream.recoveryState("BTCUSDT");
            return s != null && !s.running;
        });
    }

    @Test
    void liveTicksDuringRecoveryAreNotAppliedOutOfOrder() throws Exception {
        ScheduledThreadPoolExecutor exec = executor(2);
        FakeSimulations sims = new FakeSimulations();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        sims.recoverStarted = started;
        sims.recoverRelease = release;
        TestStream stream = new TestStream(sims, new FakeClock(), exec);

        stream.handleTick("BTCUSDT", "100", 1000, 10);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        stream.handleTick("BTCUSDT", "100.1", 2000, 11);  // buffered while recovery is in flight
        stream.handleTick("BTCUSDT", "100.2", 3000, 12);
        assertThat(sims.observeCalls).isEmpty();          // nothing observed out of order during recovery

        release.countDown();
        awaitUntil(() -> {
            LivePriceStream.PairRecoveryState s = stream.recoveryState("BTCUSDT");
            return s != null && !s.running;
        });
        // Ticks 11/12 have sequential IDs (no gap), so the recovery only covered up to the
        // first tick; they were buffered and are flushed in ID order after the replay.
        assertThat(sims.observeCalls).extracting(FakeSimulations.ObserveCall::aggregateId).containsExactly(11L, 12L);

        stream.handleTick("BTCUSDT", "100.5", 4000, 50);  // normal observation resumes after recovery
        awaitUntil(() -> sims.observeCalls.size() == 3);
        assertThat(sims.observeCalls).extracting(FakeSimulations.ObserveCall::aggregateId).containsExactly(11L, 12L, 50L);
        int lastRecover = -1, lastObserve = -1;
        List<String> events = sims.eventLog;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith("recover:")) lastRecover = i;
            else if (events.get(i).startsWith("observe:")) lastObserve = i;
        }
        assertThat(lastObserve).isGreaterThan(lastRecover);
    }

    @Test
    void bufferOverflowExtendsRestReplayThroughEveryDroppedTick() throws Exception {
        ScheduledThreadPoolExecutor exec = executor(1);
        FakeSimulations simulations = new FakeSimulations();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        simulations.recoverStarted = started;
        simulations.recoverRelease = release;
        TestStream stream = new TestStream(simulations, new FakeClock(), exec);

        stream.handleTick("BTCUSDT", "100", 1_000, 1);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        for (long id = 2; id <= 10_002; id++) {
            stream.handleTick("BTCUSDT", "100", id * 1_000, id);
        }

        release.countDown();
        awaitUntil(() -> simulations.recoverCalls.size() >= 2);
        assertThat(simulations.recoverCalls.get(1).through()).isEqualTo(Instant.ofEpochMilli(2_000));
        awaitUntil(() -> {
            LivePriceStream.PairRecoveryState state = stream.recoveryState("BTCUSDT");
            return state != null && !state.running;
        });
        assertThat(simulations.observeCalls).extracting(FakeSimulations.ObserveCall::aggregateId)
                .startsWith(3L)
                .endsWith(10_002L);
    }

    @Test
    void bufferedTicksAreSortedByTimestampAndAggregateIdAndDeduplicated() {
        List<LivePriceStream.PendingTick> ordered = LivePriceStream.orderedUniqueTicks(List.of(
                new LivePriceStream.PendingTick(new BigDecimal("103"), Instant.ofEpochMilli(3_000), 13),
                new LivePriceStream.PendingTick(new BigDecimal("101"), Instant.ofEpochMilli(1_000), 11),
                new LivePriceStream.PendingTick(new BigDecimal("102"), Instant.ofEpochMilli(2_000), 12),
                new LivePriceStream.PendingTick(new BigDecimal("999"), Instant.ofEpochMilli(4_000), 12)
        ));

        assertThat(ordered).extracting(LivePriceStream.PendingTick::aggregateId)
                .containsExactly(11L, 12L, 13L);
    }

    // ---------------------------------------------------------------- helpers

    private TestStream newStream() {
        return new TestStream(new FakeSimulations(), new FakeClock(), executor(2));
    }

    private ScheduledThreadPoolExecutor executor(int threads) {
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(threads);
        executors.add(exec);
        return exec;
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within 5 seconds");
            LockSupport.parkNanos(100_000);
        }
    }

    private static TornadoProperties props() {
        var binance = new TornadoProperties.Binance("https://binance.test", "wss://binance.test", "5m", 250, Duration.ofHours(24));
        var scheduler = new TornadoProperties.Scheduler(Duration.ofMinutes(15), Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(15));
        return new TornadoProperties(binance, scheduler, List.of());
    }

    static final class TestStream extends LivePriceStream {
        final List<CompletableFuture<WebSocket>> attempts = new ArrayList<>();
        final List<ConnectionState> attemptedStates = new ArrayList<>();
        volatile String streams = "BTCUSDT@aggTrade";

        TestStream(MixTradeSimulationService simulations, Clock clock, ScheduledExecutorService executor) {
            super(null, props(), new ObjectMapper(), simulations, clock, executor);
        }

        @Override
        String subscribedStreams() {
            return streams;
        }

        @Override
        CompletableFuture<WebSocket> openSocket(ConnectionState state, String url) {
            attempts.add(new CompletableFuture<>());
            attemptedStates.add(state);
            return attempts.get(attempts.size() - 1);
        }
    }

    static final class FakeSimulations extends MixTradeSimulationService {
        final List<RecoverCall> recoverCalls = new CopyOnWriteArrayList<>();
        final List<ObserveCall> observeCalls = new CopyOnWriteArrayList<>();
        final List<String> eventLog = new CopyOnWriteArrayList<>();
        volatile boolean openSims = true;
        volatile boolean failRecover;
        volatile String blockedPair;
        volatile CountDownLatch recoverStarted;
        volatile CountDownLatch recoverRelease;
        volatile CountDownLatch recoverDone;

        FakeSimulations() {
            super(null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public boolean hasOpenSimulations(String pair) {
            return openSims;
        }

        @Override
        public void recover(String pair, Instant through, Duration maximumLookback) {
            recoverCalls.add(new RecoverCall(pair, through, maximumLookback));
            eventLog.add("recover:" + pair + ":" + through.toEpochMilli());
            if (recoverStarted != null) recoverStarted.countDown();
            try {
                if (recoverRelease != null && (blockedPair == null || blockedPair.equals(pair))) {
                    recoverRelease.await();
                }
                if (failRecover) throw new IllegalStateException("simulated recovery failure");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                if (recoverDone != null) recoverDone.countDown();
            }
        }

        @Override
        public void observe(String pair, BigDecimal price, Instant at, Long aggregateId) {
            observeCalls.add(new ObserveCall(pair, price, at, aggregateId));
            eventLog.add("observe:" + pair + ":" + aggregateId);
        }

        record RecoverCall(String pair, Instant through, Duration maximumLookback) {}
        record ObserveCall(String pair, BigDecimal price, Instant at, Long aggregateId) {}
    }

    static final class FakeClock extends Clock {
        volatile Instant now = Instant.parse("2026-08-18T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class StubWebSocket implements WebSocket {
        final List<String> sentCloses = new ArrayList<>();
        boolean aborted;

        @Override
        public void abort() {
            aborted = true;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            sentCloses.add(statusCode + ":" + reason);
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
    }
}
