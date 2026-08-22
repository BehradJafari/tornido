package io.tornado.reporting;

import io.tornado.datafetch.BinanceMarketDataClient;
import io.tornado.notification.TelegramMessageFormatter;
import io.tornado.notification.TelegramNotificationService;
import io.tornado.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class MixTradeSimulationService {
    private static final Logger log = LoggerFactory.getLogger(MixTradeSimulationService.class);

    /** Selects one eligible representative across the three current TP ranking rows. */
    private static final Comparator<LiveCandidate> LIVE_REPRESENTATIVE_ORDER = Comparator
            .comparingDouble((LiveCandidate candidate) -> candidate.mix().getTargetHitRate()).reversed()
            .thenComparing(Comparator.comparingDouble(
                    (LiveCandidate candidate) -> candidate.mix().getWilsonScore()).reversed())
            .thenComparing(Comparator.comparingLong(
                    (LiveCandidate candidate) -> candidate.mix().getSamples()).reversed())
            .thenComparingInt(candidate -> candidate.mix().getTpLevel())
            .thenComparing(LiveCandidate::strategyIdentity);

    private final BestMethodMixRepository mixes;
    private final MixTradeSimulationRepository simulations;
    private final AppSettingsRepository settings;
    private final TelegramNotificationService telegram;
    private final TelegramMessageFormatter messages;
    private final BinanceMarketDataClient market;
    private final BestMixRankingPolicy rankings;
    private final NotificationEligibilityPolicy eligibility;
    private final ApplicationEventPublisher events;
    private final ActiveSignalLockService activeLocks;

    public MixTradeSimulationService(
            BestMethodMixRepository mixes,
            MixTradeSimulationRepository simulations,
            AppSettingsRepository settings,
            TelegramNotificationService telegram,
            TelegramMessageFormatter messages,
            BinanceMarketDataClient market,
            BestMixRankingPolicy rankings,
            NotificationEligibilityPolicy eligibility,
            ApplicationEventPublisher events,
            ActiveSignalLockService activeLocks
    ) {
        this.mixes = mixes;
        this.simulations = simulations;
        this.settings = settings;
        this.telegram = telegram;
        this.messages = messages;
        this.market = market;
        this.rankings = rankings;
        this.eligibility = eligibility;
        this.events = events;
        this.activeLocks = activeLocks;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void detect(Coin coin, List<Prediction> currentPredictions,
                       Instant openedAt, BigDecimal entryPrice) {
        AppSettings configuration = settings.findById(1).orElseThrow();
        Map<Long, Map<String, Direction>> votesByHorizon = groupVotesByHorizon(currentPredictions);

        for (Map.Entry<Long, Map<String, Direction>> slice : votesByHorizon.entrySet()) {
            detectForHorizon(coin, slice.getKey(), slice.getValue(),
                    configuration, openedAt, entryPrice);
        }
    }

    private Map<Long, Map<String, Direction>> groupVotesByHorizon(List<Prediction> predictions) {
        Map<Long, Map<String, Direction>> result = new HashMap<>();
        for (Prediction prediction : predictions) {
            result.computeIfAbsent(prediction.getHorizonSeconds(), ignored -> new HashMap<>())
                    .put(strategyKey(prediction.getStrategyCode(), prediction.getStrategyVersion()),
                            prediction.getPredictedDirection());
        }
        return result;
    }

    private void detectForHorizon(Coin coin, long horizon, Map<String, Direction> currentVotes,
                                  AppSettings configuration, Instant openedAt,
                                  BigDecimal entryPrice) {
        if (!PredictionServiceHorizons.supportsLiveSignal(horizon)) {
            log.info("SIGNAL_NOTIFY_AUDIT rejected reason={} pair={} horizon={}",
                    SignalNotificationAuditReason.UNSUPPORTED_LIVE_HORIZON,
                    coin.getPair(), horizon);
            return;
        }

        TpSlLevels levels = configuration.getTpSlLevels();
        List<BestMethodMix> loaded = mixes
                .findByCoinIdAndHorizonSecondsAndSignalVersionOrderByMixSizeAscRankAsc(
                        coin.getId(), horizon, Prediction.CURRENT_SIGNAL_VERSION);
        if (loaded.isEmpty()) {
            log.info("SIGNAL_NOTIFY_AUDIT rejected reason={} pair={} horizon={}",
                    SignalNotificationAuditReason.NO_BEST_MIX, coin.getPair(), horizon);
            return;
        }

        List<LiveCandidate> qualified = loaded.stream()
                .filter(mix -> rankings.isCurrent(mix, levels))
                .map(mix -> evaluateLiveCandidate(
                        coin, horizon, mix, currentVotes, configuration))
                .flatMap(Optional::stream)
                .sorted(LIVE_REPRESENTATIVE_ORDER)
                .toList();
        if (qualified.isEmpty()) return;

        acceptCandidate(coin, qualified.getFirst(), configuration, openedAt, entryPrice);
    }

    private Optional<LiveCandidate> evaluateLiveCandidate(
            Coin coin, long horizon, BestMethodMix mix,
            Map<String, Direction> currentVotes, AppSettings configuration) {
        List<Direction> votes = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> codes = mix.getStrategyCodes();
        List<Integer> versions = mix.getStrategyVersions();
        for (int index = 0; index < codes.size(); index++) {
            String identity = strategyKey(codes.get(index), versions.get(index));
            Direction vote = currentVotes.get(identity);
            if (vote == null) missing.add(codes.get(index) + "@" + versions.get(index));
            else votes.add(vote);
        }

        MixConsensus.Result consensus = MixConsensus.decide(mix.getMixSize(), votes);
        if (!consensus.decisive()) {
            SignalNotificationAuditReason reason = missing.isEmpty()
                    ? SignalNotificationAuditReason.NO_DECISIVE_CONSENSUS
                    : SignalNotificationAuditReason.MISSING_STRATEGY_VOTES;
            log.info("SIGNAL_NOTIFY_AUDIT rejected reason={} pair={} horizon={} mixId={} missing={} agreement={} required={}",
                    reason, coin.getPair(), horizon, mix.getId(), missing,
                    consensus.agreement(), consensus.required());
            return Optional.empty();
        }

        NotificationEligibilityPolicy.Decision decision =
                eligibility.evaluate(mix, configuration, telegram.configured());
        if (!decision.eligible()) {
            log.info("SIGNAL_NOTIFY_AUDIT rejected reason={} pair={} horizon={} mixId={} samples={} hitRate={}",
                    decision.suppressionReason(), coin.getPair(), horizon, mix.getId(),
                    mix.getSamples(), mix.getTargetHitRate());
            return Optional.empty();
        }
        return Optional.of(new LiveCandidate(mix, consensus, rankings.liveMixKey(mix)));
    }

    private void acceptCandidate(Coin coin, LiveCandidate candidate,
                                 AppSettings configuration, Instant openedAt,
                                 BigDecimal entryPrice) {
        BestMethodMix mix = candidate.mix();
        MixConsensus.Result consensus = candidate.consensus();
        MixTradeSimulation simulation = simulations.saveAndFlush(new MixTradeSimulation(
                coin, mix, consensus.direction(), consensus.agreement(), entryPrice,
                configuration.getTpSlLevels(), openedAt,
                configuration.getMinimumNotificationWinRatePercent(), true, null));

        ActiveSignalLockService.AdmissionResult admission =
                activeLocks.tryOpen(coin, mix, simulation, entryPrice, openedAt);
        if (!admission.admitted()) {
            // The simulation and lock represent one accepted signal. Remove the
            // provisional simulation if DB admission loses the concurrency race.
            simulations.delete(simulation);
            simulations.flush();
            log.info("SIGNAL_NOTIFY_AUDIT rejected reason={} pair={} horizon={} mixId={}",
                    admission.reason(), coin.getPair(), mix.getHorizonSeconds(), mix.getId());
            return;
        }

        log.info("SIGNAL_NOTIFY_AUDIT accepted opportunityId={} pair={} horizon={} mixId={} tpLevel={} lockId={}",
                simulation.getId(), coin.getPair(), mix.getHorizonSeconds(), mix.getId(),
                mix.getTpLevel(), admission.lock().getId());
        try {
            events.publishEvent(new OpportunityCommittedEvent(simulation.getId()));
        } catch (RuntimeException error) {
            log.error("SIGNAL_NOTIFY_AUDIT rejected reason={} opportunityId={}",
                    SignalNotificationAuditReason.EVENT_PUBLISH_FAILED,
                    simulation.getId(), error);
            throw error;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void observe(String pair, BigDecimal price, Instant observedAt) {
        observe(pair, price, observedAt, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void observe(String pair, BigDecimal price, Instant observedAt,
                        Long aggregateTradeId) {
        for (MixTradeSimulation simulation : simulations.lockOpenByPair(pair)) {
            MixTradeSimulation.Observation observation =
                    simulation.observeMilestones(price, observedAt, aggregateTradeId);
            if (!observation.changed()) continue;
            ActiveSignalLockService.SynchronizationState lockState =
                    activeLocks.synchronizeFromSimulation(simulation);
            if (lockState.milestoneTelegramEditAllowed()) {
                safeTelegramMilestoneUpdate(simulation, observation);
            }
        }
    }

    public boolean hasOpenSimulations(String pair) {
        return simulations.existsOpenForPair(MixTradeSimulation.Status.OPEN, pair);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recover(String pair, Instant through) {
        recover(pair, through, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recover(String pair, Instant through, Duration maximumLookback) {
        List<MixTradeSimulation> open = simulations.lockOpenByPair(pair);
        if (open.isEmpty()) return;

        Instant start = recoveryStart(open, through, maximumLookback, pair);
        if (through.isBefore(start)) return;
        List<BinanceMarketDataClient.AggregateTrade> trades =
                market.historicalTrades(pair, start, through);
        for (MixTradeSimulation simulation : open) replayTrades(simulation, trades);
    }

    private Instant recoveryStart(List<MixTradeSimulation> open, Instant through,
                                  Duration maximumLookback, String pair) {
        Instant earliest = open.stream()
                .map(MixTradeSimulation::getLastCheckedAt)
                .min(Instant::compareTo)
                .orElse(through);
        if (maximumLookback == null || !maximumLookback.isPositive()) return earliest;

        Instant floor = through.minus(maximumLookback);
        if (!earliest.isBefore(floor)) return earliest;
        log.info("Recovery window clamped for {}: oldest cursor {} skipped; starting at {}",
                pair, earliest, floor);
        return floor;
    }

    private void replayTrades(MixTradeSimulation simulation,
                              List<BinanceMarketDataClient.AggregateTrade> trades) {
        List<MixTradeSimulation.Milestone> milestones = new ArrayList<>();
        boolean terminal = false;
        for (BinanceMarketDataClient.AggregateTrade trade : trades) {
            MixTradeSimulation.Observation observation = simulation.observeMilestones(
                    trade.price(), trade.observedAt(), trade.id());
            milestones.addAll(observation.milestones());
            if (observation.terminal()) {
                terminal = true;
                break;
            }
        }
        if (milestones.isEmpty()) return;

        MixTradeSimulation.Observation combined =
                new MixTradeSimulation.Observation(true, List.copyOf(milestones), terminal);
        ActiveSignalLockService.SynchronizationState lockState =
                activeLocks.synchronizeFromSimulation(simulation);
        if (lockState.milestoneTelegramEditAllowed()) {
            safeTelegramMilestoneUpdate(simulation, combined);
        }
    }

    private void safeTelegramMilestoneUpdate(MixTradeSimulation simulation,
                                             MixTradeSimulation.Observation observation) {
        if (simulation.getTelegramMessageId() == null) return;
        try {
            String message = messages.update(simulation, observation.milestones());
            TelegramNotificationService.DeliveryResult result =
                    telegram.edit(simulation.getTelegramMessageId(), message);
            if (result.status() == TelegramNotificationService.DeliveryResult.Status.FAILED) {
                telegram.send(message);
            }
        } catch (RuntimeException error) {
            log.warn("Simulation {} milestone persisted but Telegram update failed",
                    simulation.getId(), error);
        }
    }

    public List<MixTradeSimulation> list(MixTradeSimulation.Status status) {
        return status == null
                ? simulations.findTop500ByOrderByOpenedAtDesc()
                : simulations.findTop500ByStatusOrderByOpenedAtDesc(status);
    }

    public MixTradeSimulation get(long id) {
        return simulations.findWithCoinById(id).orElseThrow();
    }

    private String strategyKey(String code, int version) {
        return code + "\u0000" + version;
    }

    private record LiveCandidate(
            BestMethodMix mix,
            MixConsensus.Result consensus,
            String strategyIdentity
    ) {}
}
