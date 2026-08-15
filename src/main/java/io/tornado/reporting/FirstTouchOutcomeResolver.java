package io.tornado.reporting;

import io.tornado.persistence.MixTradeSimulation;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class FirstTouchOutcomeResolver {
    public enum Outcome { SUCCESS, FAILED, UNRESOLVED, AMBIGUOUS }

    public Outcome resolve(MixTradeSimulation opportunity, int tpLevel, int slLevel) {
        return resolve(opportunity.tpHitAt(tpLevel), opportunity.slHitAt(slLevel));
    }

    public Outcome resolve(Instant tpAt, Instant slAt) {
        if (tpAt == null && slAt == null) return Outcome.UNRESOLVED;
        if (tpAt != null && slAt == null) return Outcome.SUCCESS;
        if (tpAt == null) return Outcome.FAILED;
        int order = tpAt.compareTo(slAt);
        if (order < 0) return Outcome.SUCCESS;
        if (order > 0) return Outcome.FAILED;
        return Outcome.AMBIGUOUS;
    }
}
