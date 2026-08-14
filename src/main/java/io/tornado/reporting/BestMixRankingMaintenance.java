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
        var levels = settings.findById(1).orElseThrow().getTpSlLevels();
        List<BestMethodMix> stale = mixes.findBySignalVersionOrderByCoinSymbolAscHorizonSecondsAscMixSizeAscRankAsc(3)
                .stream().filter(mix -> !rankings.isCurrent(mix, levels)).toList();
        if (!stale.isEmpty()) mixes.deleteAllInBatch(stale);
        return stale.size();
    }
}
