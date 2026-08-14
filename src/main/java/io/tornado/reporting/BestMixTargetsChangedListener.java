package io.tornado.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BestMixTargetsChangedListener {
    private static final Logger log = LoggerFactory.getLogger(BestMixTargetsChangedListener.class);
    private final BestMixRankingMaintenance maintenance;
    private final BestMixService bestMixes;

    public BestMixTargetsChangedListener(BestMixRankingMaintenance maintenance, BestMixService bestMixes) {
        this.maintenance = maintenance;
        this.bestMixes = bestMixes;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void targetsChanged(BestMixTargetsChanged event) {
        try {
            maintenance.invalidateStale();
            bestMixes.rebuildAll();
        } catch (RuntimeException error) {
            // Consumers independently enforce current targetPercent, so a failed rebuild exposes no stale rows.
            log.error("Best Mix rebuild failed after TP settings changed; stale rankings remain unusable", error);
        }
    }
}
