package io.tornado.scheduler;

import io.tornado.datafetch.BinanceMarketDataClient;
import io.tornado.persistence.*;
import io.tornado.reporting.ActiveSignalLockReconciliationService;
import io.tornado.reporting.BestMixService;
import io.tornado.reporting.MixTradeSimulationService;
import io.tornado.reporting.PredictionServiceHorizons;
import io.tornado.strategies.StrategyDefinition;
import io.tornado.strategies.StrategyProfilePolicy;
import io.tornado.strategies.StrategyProfileResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class PredictionService {
    private static final Logger log = LoggerFactory.getLogger(PredictionService.class);
    public static final List<Duration> AUTOMATIC_HORIZONS = PredictionServiceHorizons.ALL.stream()
            .map(Duration::ofSeconds)
            .toList();
    private static final BigDecimal MINIMUM_CORRECT_MOVE = new BigDecimal("0.003");
    private static final int MAX_GRADING_ATTEMPTS = 5;

    private final CoinRepository coins;
    private final PredictionRepository predictions;
    private final AnalysisRunRepository runs;
    private final BinanceMarketDataClient market;
    private final BestMixService bestMixes;
    private final MixTradeSimulationService simulations;
    private final StrategyProfileResolver profileResolver;
    private final ActiveSignalLockReconciliationService lockReconciliation;

    @Autowired
    public PredictionService(
            CoinRepository coins,
            PredictionRepository predictions,
            AnalysisRunRepository runs,
            BinanceMarketDataClient market,
            BestMixService bestMixes,
            MixTradeSimulationService simulations,
            StrategyProfileResolver profileResolver,
            ActiveSignalLockReconciliationService lockReconciliation
    ) {
        this.coins = coins;
        this.predictions = predictions;
        this.runs = runs;
        this.market = market;
        this.bestMixes = bestMixes;
        this.simulations = simulations;
        this.profileResolver = profileResolver;
        this.lockReconciliation = lockReconciliation;
    }

    public PredictionService(
            CoinRepository coins,
            PredictionRepository predictions,
            AnalysisRunRepository runs,
            BinanceMarketDataClient market,
            BestMixService bestMixes,
            MixTradeSimulationService simulations,
            StrategyProfileResolver profileResolver
    ) {
        this(coins, predictions, runs, market, bestMixes, simulations, profileResolver, null);
    }

    public PredictionService(CoinRepository coins, PredictionRepository predictions,
                             AnalysisRunRepository runs, BinanceMarketDataClient market) {
        this(coins, predictions, runs, market, null, null, null, null);
    }

    public RunResult snapshot() {
        return snapshot("Scheduled analysis " + Instant.now());
    }

    public RunResult snapshot(String name) {
        AnalysisRun run = runs.save(new AnalysisRun(name, Duration.ZERO));
        int saved = 0;
        List<String> errors = new ArrayList<>();
        List<Coin> activeCoins = coins.findAllByActiveTrueOrderBySymbol();
        Map<Long, List<StrategyHorizonProfile>> profiles = profileResolver == null
                ? fallbackProfiles(activeCoins)
                : profileResolver.resolve(activeCoins);
        Map<CandleKey, BarSeries> seriesCache = new HashMap<>();

        for (Coin coin : activeCoins) {
            List<Draft> drafts = evaluateProfiles(
                    coin, profiles.getOrDefault(coin.getId(), List.of()), seriesCache, errors);
            if (drafts.isEmpty()) continue;
            try {
                BigDecimal executionPrice = market.price(coin.getPair());
                Instant executionAt = Instant.now();
                List<Prediction> batch = drafts.stream()
                        .filter(draft -> !draft.signalAt().isAfter(executionAt))
                        .map(draft -> new Prediction(
                                run, coin, draft.profile(), draft.method(), draft.signalAt(),
                                draft.signalPrice(), executionAt, executionPrice, draft.direction()))
                        .toList();
                predictions.saveAll(batch);
                saved += batch.size();
                detectMixSignals(coin, batch, executionAt, executionPrice);
            } catch (Exception error) {
                log.error("Execution price failed for {}", coin.getPair(), error);
                errors.add(coin.getPair() + ": " + error.getMessage());
            }
        }

        run.complete(errors.size(), saved);
        runs.save(run);
        return new RunResult(run.getId(), saved, errors);
    }

    private List<Draft> evaluateProfiles(
            Coin coin,
            List<StrategyHorizonProfile> profiles,
            Map<CandleKey, BarSeries> seriesCache,
            List<String> errors
    ) {
        List<Draft> drafts = new ArrayList<>();
        for (StrategyHorizonProfile profile : profiles) {
            try {
                BarSeries series = seriesCache.computeIfAbsent(
                        new CandleKey(coin.getPair(), profile.getAnalysisTimeframe()),
                        key -> market.candles(key.pair(), key.timeframe()));
                StrategyDefinition strategy = StrategyDefinition.valueOf(profile.getStrategyCode());
                var signal = strategy.evaluate(series, profile.getParameterKey());
                if (signal.direction() == Direction.NEUTRAL) continue;

                var last = series.getLastBar();
                drafts.add(new Draft(profile, strategy.label(), last.getEndTime(),
                        new BigDecimal(last.getClosePrice().toString()), signal.direction()));
            } catch (Exception error) {
                log.warn("Horizon-aware signal failed for {} {} {}: {}",
                        coin.getPair(), profile.getStrategyCode(),
                        profile.getPredictionHorizonSeconds(), error.getMessage());
                errors.add(coin.getPair() + "/" + profile.getStrategyCode() + "/"
                        + profile.getPredictionHorizonSeconds() + ": " + error.getMessage());
            }
        }
        return drafts;
    }

    private void detectMixSignals(Coin coin, List<Prediction> batch,
                                  Instant executionAt, BigDecimal executionPrice) {
        if (simulations == null) return;
        try {
            simulations.detect(coin, batch, executionAt, executionPrice);
        } catch (Exception error) {
            log.error("Mix signal detection failed for {} without affecting snapshot",
                    coin.getPair(), error);
        }
    }

    private Map<Long, List<StrategyHorizonProfile>> fallbackProfiles(List<Coin> activeCoins) {
        Map<Long, List<StrategyHorizonProfile>> result = new HashMap<>();
        Instant now = Instant.now();
        for (Coin coin : activeCoins) {
            List<StrategyHorizonProfile> profiles = new ArrayList<>();
            for (StrategyDefinition strategy : StrategyDefinition.values()) {
                for (long horizon : StrategyProfilePolicy.HORIZONS) {
                    profiles.add(StrategyHorizonProfile.fallback(
                            strategy.code(), strategy.version(), horizon,
                            StrategyProfilePolicy.fallback(horizon),
                            strategy.defaultParameterKey(), now));
                }
            }
            result.put(coin.getId(), profiles);
        }
        return result;
    }

    @Transactional
    public int gradeDue() {
        Instant now = Instant.now();
        Set<BestMixService.Slice> affected = new HashSet<>();
        Map<TargetKey, List<Prediction>> dueByTarget = groupDuePredictions(now);
        Map<TargetKey, PriceLookup> priceCache = new HashMap<>();
        int graded = 0;

        for (Map.Entry<TargetKey, List<Prediction>> entry : dueByTarget.entrySet()) {
            TargetKey key = entry.getKey();
            PriceLookup lookup = priceCache.computeIfAbsent(key, this::lookup);
            if (lookup.price() != null) {
                graded += gradeBatch(entry.getValue(), lookup.price(), now, affected);
            } else {
                recordGradingFailure(key, entry.getValue(), lookup.error(), now);
            }
        }

        registerAfterCommitOperations(affected);
        return graded;
    }

    private Map<TargetKey, List<Prediction>> groupDuePredictions(Instant now) {
        List<Prediction> pending = new ArrayList<>(
                predictions.findDue(Outcome.PENDING, 2, now));
        pending.addAll(predictions.findDue(Outcome.PENDING, 3, now));
        Map<TargetKey, List<Prediction>> result = new LinkedHashMap<>();
        for (Prediction prediction : pending) {
            Instant target = prediction.getTargetAt() != null
                    ? prediction.getTargetAt()
                    : prediction.getPredictedAt().plusSeconds(prediction.getHorizonSeconds());
            result.computeIfAbsent(
                    new TargetKey(prediction.getCoin().getPair(), target),
                    ignored -> new ArrayList<>()).add(prediction);
        }
        return result;
    }

    private int gradeBatch(List<Prediction> batch, BinanceMarketDataClient.TimedPrice price,
                           Instant now, Set<BestMixService.Slice> affected) {
        for (Prediction prediction : batch) {
            prediction.grade(price.price(), price.observedAt(), now, MINIMUM_CORRECT_MOVE);
            if (bestMixes != null && prediction.getSignalVersion() == Prediction.CURRENT_SIGNAL_VERSION) {
                affected.add(new BestMixService.Slice(
                        prediction.getCoin().getId(), prediction.getHorizonSeconds()));
            }
        }
        return batch.size();
    }

    private void recordGradingFailure(TargetKey key, List<Prediction> batch,
                                      String error, Instant now) {
        int terminal = 0;
        for (Prediction prediction : batch) {
            prediction.recordGradingError(error);
            if (prediction.getGradingAttempts() >= MAX_GRADING_ATTEMPTS) {
                prediction.markUngradable(now);
                terminal++;
            }
        }
        int attempt = batch.stream()
                .mapToInt(Prediction::getGradingAttempts)
                .max().orElse(0);
        if (terminal > 0) {
            log.error("Could not grade {} predictions for {} at {}; {} became UNGRADABLE after {} attempts: {}",
                    batch.size(), key.pair(), key.target(), terminal,
                    MAX_GRADING_ATTEMPTS, error);
        } else {
            log.warn("Could not grade {} predictions for {} at {} (attempt {}/{}): {}",
                    batch.size(), key.pair(), key.target(), attempt,
                    MAX_GRADING_ATTEMPTS, error);
        }
    }

    /** DB changes commit before ranking rebuilds or lock reconciliation begins. */
    private void registerAfterCommitOperations(Set<BestMixService.Slice> affected) {
        if (bestMixes == null && lockReconciliation == null) return;
        Set<BestMixService.Slice> slices = Set.copyOf(affected);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refreshBestMixes(slices);
                reconcileActiveLocks();
            }
        });
    }

    private void refreshBestMixes(Set<BestMixService.Slice> slices) {
        if (bestMixes == null || slices.isEmpty()) return;
        try {
            bestMixes.refresh(slices);
        } catch (Exception error) {
            log.error("Best-mix refresh failed after grading committed", error);
        }
    }

    private void reconcileActiveLocks() {
        if (lockReconciliation == null) return;
        try {
            lockReconciliation.checkAndCloseDue();
        } catch (Exception error) {
            log.error("Active-signal reconciliation failed after grading committed", error);
        }
    }

    private PriceLookup lookup(TargetKey key) {
        try {
            return new PriceLookup(market.priceAt(key.pair(), key.target()), null);
        } catch (Exception error) {
            return new PriceLookup(null, error.getMessage());
        }
    }

    private record TargetKey(String pair, Instant target) {}
    private record PriceLookup(BinanceMarketDataClient.TimedPrice price, String error) {}
    private record CandleKey(String pair, String timeframe) {}
    private record Draft(StrategyHorizonProfile profile, String method, Instant signalAt,
                         BigDecimal signalPrice, Direction direction) {}
    public record RunResult(long runId, int predictionsCreated, List<String> errors) {}
}
