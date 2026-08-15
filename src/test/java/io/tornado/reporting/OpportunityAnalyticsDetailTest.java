package io.tornado.reporting;

import io.tornado.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OpportunityAnalyticsDetailTest {
    @Test void equalTimestampTimelineUsesAggregateTradeSequence() {
        Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);BestMethodMix mix=new BestMethodMix(coin,900,1,1,List.of("RSI"),List.of(1),List.of("RSI"),100,70,70,.6,1,new BigDecimal("0.30"));ReflectionTestUtils.setField(mix,"id",2L);Instant opened=Instant.parse("2026-08-15T10:00:00Z");MixTradeSimulation signal=new MixTradeSimulation(coin,mix,Direction.UP,1,new BigDecimal("100"),TpSlLevels.defaults(),opened);ReflectionTestUtils.setField(signal,"id",3L);Instant same=opened.plusSeconds(1);signal.observeMilestones(new BigDecimal("99.7"),same,100L);signal.observeMilestones(new BigDecimal("100.6"),same,101L);
        MixTradeSimulationRepository repository=mock(MixTradeSimulationRepository.class);when(repository.findWithCoinById(3L)).thenReturn(Optional.of(signal));OpportunityAnalyticsService service=new OpportunityAnalyticsService(mock(NamedParameterJdbcTemplate.class),repository,new FirstTouchOutcomeResolver());
        var detail=service.detail(3L,2,1);
        assertThat(detail.selectedOutcome()).isEqualTo("FAILED");assertThat(detail.timeline()).extracting(OpportunityAnalyticsService.Milestone::name).containsSubsequence("SL1","TP1","TP2");
    }
}
