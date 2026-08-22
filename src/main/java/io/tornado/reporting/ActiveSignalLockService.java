package io.tornado.reporting;

import io.tornado.persistence.*;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class ActiveSignalLockService {
    private static final Logger log = LoggerFactory.getLogger(ActiveSignalLockService.class);
    private final ActiveSignalLockRepository locks;
    private final FirstTouchOutcomeResolver outcomes;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;

    public ActiveSignalLockService(ActiveSignalLockRepository locks, FirstTouchOutcomeResolver outcomes,
                                   ApplicationEventPublisher events, EntityManager entityManager) {
        this.locks = locks;
        this.outcomes = outcomes;
        this.events = events;
        this.entityManager = entityManager;
    }

    /** Atomically admits a signal. PostgreSQL's partial unique index is the final concurrency authority. */
    @Transactional
    public AdmissionResult tryOpen(Coin coin, BestMethodMix mix, MixTradeSimulation simulation,
                                   BigDecimal entryPrice, Instant openedAt) {
        if (!PredictionServiceHorizons.supportsLiveSignal(mix.getHorizonSeconds())) {
            return AdmissionResult.rejected(SignalNotificationAuditReason.UNSUPPORTED_LIVE_HORIZON);
        }
        if (locks.existsByCoinIdAndHorizonSecondsAndStatus(coin.getId(), mix.getHorizonSeconds(), ActiveSignalLock.Status.OPEN)) {
            logSuppression(coin, mix.getHorizonSeconds(), simulation.getId());
            return AdmissionResult.rejected(SignalNotificationAuditReason.POSITION_ALREADY_OPEN);
        }

        ActiveSignalLock lock = new ActiveSignalLock(coin, mix, simulation, entryPrice, openedAt);
        if (isPostgreSql()) {
            int inserted = insertPostgres(lock);
            if (inserted == 0) {
                logSuppression(coin, mix.getHorizonSeconds(), simulation.getId());
                return AdmissionResult.rejected(SignalNotificationAuditReason.POSITION_ALREADY_OPEN);
            }
            lock = locks.findBySimulationId(simulation.getId()).orElseThrow();
        } else {
            try {
                lock = locks.saveAndFlush(lock);
            } catch (DataIntegrityViolationException conflict) {
                if (!isOpenScopeConflict(conflict)) throw conflict;
                logSuppression(coin, mix.getHorizonSeconds(), simulation.getId());
                return AdmissionResult.rejected(SignalNotificationAuditReason.POSITION_ALREADY_OPEN);
            }
        }
        log.info("ACTIVE_SIGNAL_LOCK_OPEN lockId={} coin={} pair={} horizon={} simulationId={}",
                lock.getId(), coin.getSymbol(), coin.getPair(), mix.getHorizonSeconds(), simulation.getId());
        return AdmissionResult.opened(lock);
    }

    @Transactional
    public SynchronizationState synchronizeFromSimulation(MixTradeSimulation simulation) {
        ActiveSignalLock lock = locks.lockOpenBySimulationId(
                simulation.getId(), ActiveSignalLock.Status.OPEN).orElse(null);
        if (lock == null) {
            return locks.existsBySimulationId(simulation.getId())
                    ? SynchronizationState.LOCK_ALREADY_CLOSED
                    : SynchronizationState.NO_LOCK;
        }
        if (closeFromFirstTouch(lock, simulation)) {
            return SynchronizationState.LOCK_JUST_CLOSED;
        }
        Instant lastCheckedAt = simulation.getLastCheckedAt();
        if (lastCheckedAt != null && lastCheckedAt.isAfter(lock.getExpectedCloseAt())) {
            return SynchronizationState.LOCK_OPEN_AFTER_DEADLINE;
        }
        return SynchronizationState.LOCK_OPEN_UNCHANGED;
    }

    @Transactional
    public boolean closeTimedOut(long lockId, Instant now) {
        ActiveSignalLock lock = locks.lockOpenById(lockId, ActiveSignalLock.Status.OPEN).orElse(null);
        if (lock == null) return false;
        if (closeFromFirstTouch(lock, lock.getSimulation())) return true;
        if (now.isBefore(lock.getExpectedCloseAt())) return false;
        return close(lock, ActiveSignalLock.Status.CLOSED_TIMEOUT, lock.getExpectedCloseAt(), null);
    }

    @Transactional(readOnly = true)
    public List<ActiveSignalLock> openLocks() {
        return locks.findByStatusOrderByOpenedAtDesc(ActiveSignalLock.Status.OPEN);
    }

    @Transactional(readOnly = true)
    public List<ActiveSignalLock> dueLocks(Instant through) {
        return locks.findByStatusAndExpectedCloseAtLessThanEqualOrderByExpectedCloseAtAsc(ActiveSignalLock.Status.OPEN, through);
    }

    private boolean closeFromFirstTouch(ActiveSignalLock lock, MixTradeSimulation simulation) {
        FirstTouchOutcomeResolver.Outcome outcome = outcomes.resolve(simulation, 1, 1);
        Instant outcomeAt = switch (outcome) {
            case SUCCESS -> simulation.getTp1HitAt();
            case FAILED -> simulation.getSl1HitAt();
            default -> null;
        };
        if (outcomeAt == null || outcomeAt.isAfter(lock.getExpectedCloseAt())) return false;
        return outcome == FirstTouchOutcomeResolver.Outcome.SUCCESS
                ? close(lock, ActiveSignalLock.Status.CLOSED_TP, outcomeAt, simulation.getTp1Price())
                : close(lock, ActiveSignalLock.Status.CLOSED_SL, outcomeAt, simulation.getSl1Price());
    }

    private boolean close(ActiveSignalLock lock, ActiveSignalLock.Status status, Instant at, BigDecimal price) {
        if (!lock.close(status, at, price)) return false;
        String action = switch (status) {
            case CLOSED_TP -> "ACTIVE_SIGNAL_LOCK_CLOSE_TP";
            case CLOSED_SL -> "ACTIVE_SIGNAL_LOCK_CLOSE_SL";
            case CLOSED_TIMEOUT -> "ACTIVE_SIGNAL_LOCK_CLOSE_TIMEOUT";
            case OPEN -> throw new IllegalArgumentException("cannot close lock as OPEN");
        };
        log.info("{} lockId={} coin={} pair={} horizon={} simulationId={}", action, lock.getId(),
                lock.getCoin().getSymbol(), lock.getCoin().getPair(), lock.getHorizonSeconds(), lock.getSimulation().getId());
        events.publishEvent(new ActiveSignalLockClosedEvent(lock.getId(), lock.getSimulation().getId(), status));
        return true;
    }

    private int insertPostgres(ActiveSignalLock lock) {
        return entityManager.createNativeQuery("""
                INSERT INTO active_signal_locks
                  (coin_id, horizon_seconds, best_method_mix_id, mix_trade_simulation_id,
                   opened_at, entry_price, expected_close_at, status, created_at, updated_at)
                VALUES (:coinId, :horizon, :mixId, :simulationId,
                        :openedAt, :entryPrice, :expectedCloseAt, 'OPEN', :openedAt, :openedAt)
                ON CONFLICT (coin_id, horizon_seconds) WHERE status = 'OPEN'
                DO NOTHING
                """)
                .setParameter("coinId", lock.getCoin().getId())
                .setParameter("horizon", lock.getHorizonSeconds())
                .setParameter("mixId", lock.getBestMethodMix().getId())
                .setParameter("simulationId", lock.getSimulation().getId())
                .setParameter("openedAt", lock.getOpenedAt())
                .setParameter("entryPrice", lock.getEntryPrice())
                .setParameter("expectedCloseAt", lock.getExpectedCloseAt())
                .executeUpdate();
    }

    private boolean isPostgreSql() {
        String productName = entityManager.unwrap(org.hibernate.Session.class)
                .doReturningWork(connection -> connection.getMetaData().getDatabaseProductName());
        return productName.toLowerCase().contains("postgresql");
    }

    private boolean isOpenScopeConflict(DataIntegrityViolationException conflict) {
        Throwable current = conflict;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains(
                    "uk_active_signal_lock_open_coin_horizon")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void logSuppression(Coin coin, long horizon, Long simulationId) {
        log.info("ACTIVE_SIGNAL_LOCK_SUPPRESS reason=POSITION_ALREADY_OPEN coin={} pair={} horizon={} simulationId={}",
                coin.getSymbol(), coin.getPair(), horizon, simulationId);
    }

    public record AdmissionResult(boolean admitted, ActiveSignalLock lock, SignalNotificationAuditReason reason) {
        static AdmissionResult opened(ActiveSignalLock lock) { return new AdmissionResult(true, lock, null); }
        static AdmissionResult rejected(SignalNotificationAuditReason reason) { return new AdmissionResult(false, null, reason); }
    }

    public enum SynchronizationState {
        NO_LOCK(true),
        LOCK_OPEN_UNCHANGED(true),
        LOCK_OPEN_AFTER_DEADLINE(false),
        LOCK_JUST_CLOSED(false),
        LOCK_ALREADY_CLOSED(false);

        private final boolean milestoneTelegramEditAllowed;

        SynchronizationState(boolean milestoneTelegramEditAllowed) {
            this.milestoneTelegramEditAllowed = milestoneTelegramEditAllowed;
        }

        public boolean milestoneTelegramEditAllowed() {
            return milestoneTelegramEditAllowed;
        }
    }
}
