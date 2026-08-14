package io.tornado.reporting;

import io.tornado.persistence.BestMethodMix;
import io.tornado.persistence.TpSlLevels;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class BestMixRankingPolicy {
    public boolean isCurrent(BestMethodMix mix, TpSlLevels levels) {
        int tpLevel = mix.getTpLevel();
        return tpLevel >= 1 && tpLevel <= 3
                && mix.getTargetPercent() != null
                && mix.getTargetPercent().compareTo(levels.tp(tpLevel)) == 0;
    }

    public boolean sameTargets(TpSlLevels left, TpSlLevels right) {
        for (int level = 1; level <= 3; level++) {
            if (left.tp(level).compareTo(right.tp(level)) != 0) return false;
        }
        return true;
    }

    /** Stable identity shared by equivalent rankings from different TP levels. */
    public String liveMixKey(BestMethodMix mix) {
        List<String> strategies = new ArrayList<>();
        List<String> codes = mix.getStrategyCodes();
        List<Integer> versions = mix.getStrategyVersions();
        for (int i = 0; i < codes.size(); i++) strategies.add(codes.get(i) + "@" + versions.get(i));
        strategies.sort(Comparator.naturalOrder());
        return mix.getCoin().getId() + ":" + mix.getHorizonSeconds() + ":" + String.join(",", strategies);
    }

    public Comparator<BestMethodMix> representativeOrder() {
        return Comparator.comparingInt(BestMethodMix::getTpLevel)
                .thenComparingInt(BestMethodMix::getRank)
                .thenComparingInt(BestMethodMix::getMixSize)
                .thenComparing(m -> m.getId() == null ? Long.MAX_VALUE : m.getId());
    }
}
