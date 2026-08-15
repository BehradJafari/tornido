package io.tornado.reporting;

import io.tornado.persistence.MixTradeSimulation;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class FirstTouchOutcomeResolver {
    public enum Outcome { SUCCESS, FAILED, UNRESOLVED, AMBIGUOUS }

    public Outcome resolve(MixTradeSimulation opportunity, int tpLevel, int slLevel) {
        return resolve(opportunity.tpHitAt(tpLevel), opportunity.tpHitSequence(tpLevel),
                opportunity.slHitAt(slLevel), opportunity.slHitSequence(slLevel));
    }

    public Outcome resolve(Instant tpAt, Instant slAt) {
        return resolve(tpAt, null, slAt, null);
    }

    public Outcome resolve(Instant tpAt, Long tpSequence, Instant slAt, Long slSequence) {
        if (tpAt == null && slAt == null) return Outcome.UNRESOLVED;
        if (tpAt != null && slAt == null) return Outcome.SUCCESS;
        if (tpAt == null) return Outcome.FAILED;
        int order = tpAt.compareTo(slAt);
        if (order < 0) return Outcome.SUCCESS;
        if (order > 0) return Outcome.FAILED;
        if (tpSequence != null && slSequence != null) {
            int sequenceOrder = tpSequence.compareTo(slSequence);
            if (sequenceOrder < 0) return Outcome.SUCCESS;
            if (sequenceOrder > 0) return Outcome.FAILED;
        }
        return Outcome.AMBIGUOUS;
    }
}
