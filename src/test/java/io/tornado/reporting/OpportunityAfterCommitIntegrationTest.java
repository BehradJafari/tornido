package io.tornado.reporting;

import io.tornado.notification.TelegramNotificationService;
import io.tornado.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:after-commit-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.task.scheduling.enabled=false","tornado.auth.username=test-admin","tornado.auth.password=test-admin-password","tornado.auth.jwt-secret=12345678901234567890123456789012"})
class OpportunityAfterCommitIntegrationTest {
    @Autowired CoinRepository coins;@Autowired BestMethodMixRepository mixes;@Autowired MixTradeSimulationRepository opportunities;@Autowired ApplicationEventPublisher events;
    @MockitoBean TelegramNotificationService telegram;

    @Test @Transactional
    void telegramRunsOnlyAfterOpportunityCommitAndDeliveryStateUsesAnotherTransaction() {
        when(telegram.send(anyString())).thenReturn(new TelegramNotificationService.DeliveryResult(TelegramNotificationService.DeliveryResult.Status.SENT,88L,null));
        Coin coin=coins.save(new Coin("TEST","TESTUSDT"));BestMethodMix mix=mixes.save(new BestMethodMix(coin,900,1,1,List.of("RSI"),List.of(1),List.of("RSI"),100,70,70,.6,1,new BigDecimal("0.30")));
        MixTradeSimulation opportunity=opportunities.saveAndFlush(new MixTradeSimulation(coin,mix,Direction.UP,1,new BigDecimal("100"),TpSlLevels.defaults(),Instant.parse("2026-08-15T10:00:00Z"),new BigDecimal("60"),true,null));
        events.publishEvent(new OpportunityCommittedEvent(opportunity.getId()));
        verify(telegram,never()).send(anyString());
        TestTransaction.flagForCommit();TestTransaction.end();
        verify(telegram).send(anyString());
        MixTradeSimulation committed=opportunities.findWithCoinById(opportunity.getId()).orElseThrow();
        assertThat(committed.getTelegramMessageId()).isEqualTo(88L);assertThat(committed.getNotificationDeliveryStatus()).isEqualTo(MixTradeSimulation.NotificationDeliveryStatus.SENT);
    }
}
