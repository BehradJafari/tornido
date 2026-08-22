package io.tornado.reporting;

import io.tornado.persistence.ActiveSignalLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Replays ordered trades through each deadline before applying timeout. */
@Service
public class ActiveSignalLockReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ActiveSignalLockReconciliationService.class);
    private final ActiveSignalLockService locks;
    private final MixTradeSimulationService simulations;

    public ActiveSignalLockReconciliationService(ActiveSignalLockService locks,
                                                 MixTradeSimulationService simulations) {
        this.locks = locks;
        this.simulations = simulations;
    }

    public void checkAndCloseDue() {
        checkAndCloseDue(Instant.now());
    }

    void checkAndCloseDue(Instant now) {
        List<ActiveSignalLock> due = locks.dueLocks(now);
        for (ActiveSignalLock lock : due) {
            try {
                simulations.recover(lock.getCoin().getPair(), lock.getExpectedCloseAt());
                locks.closeTimedOut(lock.getId(), now);
            } catch (RuntimeException error) {
                // Missing ordered trade history is not evidence of timeout. Leave the lock open for retry.
                log.warn("Active signal lock {} reconciliation failed; timeout deferred: {}", lock.getId(), error.getMessage());
            }
        }
    }
}
