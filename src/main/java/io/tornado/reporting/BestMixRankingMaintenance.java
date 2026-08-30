package io.tornado.reporting;

import io.tornado.persistence.AppSettingsRepository;
import io.tornado.persistence.BestMethodMix;
import io.tornado.persistence.BestMethodMixRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BestMixRankingMaintenance {
    private final BestMethodMixRepository mixes;
    private final AppSettingsRepository settings;
    private final BestMixRankingPolicy rankings;

    public BestMixRankingMaintenance(BestMethodMixRepository mixes, AppSettingsRepository settings, BestMixRankingPolicy rankings) {
        this.mixes = mixes;
        this.settings = settings;
        this.rankings = rankings;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int invalidateStale() {
        var configuration = settings.findById(1).orElseThrow();var levels=configuration.getTpSlLevels();
        List<BestMethodMix> stale = mixes.findBySignalVersionOrderByCoinSymbolAscHorizonSecondsAscMixSizeAscRankAsc(3)
                .stream().filter(mix -> !mix.getCoin().isActive()
                        || !PredictionServiceHorizons.supportsLiveSignal(mix.getHorizonSeconds())
                        || !rankings.isCurrent(mix, levels)
                        || mix.getSamples()<configuration.getMinimumMixSimulationTrades()
                        || java.math.BigDecimal.valueOf(mix.getTargetHitRate()).compareTo(configuration.getMinimumNotificationWinRatePercent())<0).toList();
        if (!stale.isEmpty()) mixes.deleteAllInBatch(stale);
        return stale.size();
    }
}
