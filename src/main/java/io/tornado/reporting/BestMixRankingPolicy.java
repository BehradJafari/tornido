package io.tornado.reporting;

import io.tornado.persistence.BestMethodMix;
import io.tornado.persistence.TpSlLevels;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class BestMixRankingPolicy {
    public boolean isCurrent(BestMethodMix mix, TpSlLevels levels) {
        int tpLevel = mix.getTpLevel();
        return tpLevel == 1
                && mix.getTargetPercent() != null
                && mix.getTargetPercent().compareTo(levels.tp1()) == 0;
    }

    public boolean sameTargets(TpSlLevels left, TpSlLevels right) {
        return left.tp1().compareTo(right.tp1()) == 0;
    }

    /** Stable identity shared by equivalent rankings from different TP levels. */
    public String liveMixKey(BestMethodMix mix) {
        List<String> strategies = new ArrayList<>();
        List<String> codes = mix.getStrategyCodes();
        List<Integer> versions = mix.getStrategyVersions();
        for (int i = 0; i < codes.size(); i++) strategies.add(codes.get(i) + "@" + versions.get(i));
        strategies.sort(String::compareTo);
        return mix.getCoin().getId() + ":" + mix.getHorizonSeconds() + ":" + String.join(",", strategies);
    }
}
