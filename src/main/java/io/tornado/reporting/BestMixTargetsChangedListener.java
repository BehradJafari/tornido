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
            log.info("SIGNAL_NOTIFY_AUDIT best-mix-rebuild-start previousTargets={} currentTargets={}", event.previous(), event.current());
            int stale = maintenance.invalidateStale();
            log.info("SIGNAL_NOTIFY_AUDIT best-mix-rebuild-invalidated BEST_MIX_STALE_ROWS={}", stale);
            bestMixes.rebuildAll();
            log.info("SIGNAL_NOTIFY_AUDIT best-mix-rebuild-complete");
        } catch (RuntimeException error) {
            // Consumers independently enforce current targetPercent, so a failed rebuild exposes no stale rows.
            log.error("Best Mix rebuild failed after TP settings changed; stale rankings remain unusable", error);
        }
    }
}
