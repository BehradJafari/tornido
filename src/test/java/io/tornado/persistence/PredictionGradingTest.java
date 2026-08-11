package io.tornado.persistence;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;

class PredictionGradingTest {
    private Prediction prediction(Direction direction){return new Prediction(null,new Coin("BTC","BTCUSDT"),"test",Instant.now(),direction,new BigDecimal("100.00"),Duration.ofHours(1));}
    @Test void requiresFullPointThreePercentMove(){var tooSmall=prediction(Direction.UP);tooSmall.grade(new BigDecimal("100.29"),Instant.now(),new BigDecimal("0.003"));assertThat(tooSmall.getOutcome()).isEqualTo(Outcome.INCORRECT);var enough=prediction(Direction.UP);enough.grade(new BigDecimal("100.30"),Instant.now(),new BigDecimal("0.003"));assertThat(enough.getOutcome()).isEqualTo(Outcome.CORRECT);}
    @Test void appliesThresholdSymmetricallyToDownCalls(){var enough=prediction(Direction.DOWN);enough.grade(new BigDecimal("99.70"),Instant.now(),new BigDecimal("0.003"));assertThat(enough.getOutcome()).isEqualTo(Outcome.CORRECT);}
}
