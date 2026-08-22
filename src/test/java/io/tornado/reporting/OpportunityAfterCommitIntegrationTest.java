package io.tornado.reporting;

import io.tornado.datafetch.LivePriceStream;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:after-commit-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.task.scheduling.enabled=false","tornado.scheduler.snapshot-initial-delay=24h","tornado.scheduler.snapshot-interval=24h","tornado.scheduler.grading-interval=24h","tornado.auth.username=test-admin","tornado.auth.password=test-admin-password","tornado.auth.jwt-secret=12345678901234567890123456789012"})
class OpportunityAfterCommitIntegrationTest {
    @Autowired CoinRepository coins;@Autowired BestMethodMixRepository mixes;@Autowired MixTradeSimulationRepository opportunities;@Autowired AppSettingsRepository settings;@Autowired MixTradeSimulationService simulationService;@Autowired ApplicationEventPublisher events;
    @MockitoBean TelegramNotificationService telegram;
    @MockitoBean LivePriceStream livePriceStream;

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

    @Test
    void controlledQualifyingSignalCompletesPersistenceEventFormatSendAndDeliveryStateChain() {
        when(telegram.configured()).thenReturn(true);
        when(telegram.send(anyString())).thenReturn(new TelegramNotificationService.DeliveryResult(TelegramNotificationService.DeliveryResult.Status.SENT,99L,null));
        AppSettings configuration=settings.findById(1).orElseThrow();configuration.updateTelegram(true);configuration.updateMixSignals(10,configuration.getTpSlLevels(),new BigDecimal("65"),false);settings.saveAndFlush(configuration);
        Coin coin=coins.save(new Coin("PIPE","PIPEUSDT"));
        BestMethodMix tp1=mixes.save(new BestMethodMix(coin,3600,3,1,List.of("A","B","C"),List.of(1,1,1),List.of("A","B","C"),100,58,70,.5,1,configuration.getTpSlLevels().tp1()));
        BestMethodMix tp2=mixes.save(new BestMethodMix(coin,3600,3,1,List.of("A","B","C"),List.of(1,1,1),List.of("A","B","C"),100,72,70,.6,2,configuration.getTpSlLevels().tp2()));
        Prediction a=new Prediction(null,coin,"A",1,"A",Instant.EPOCH,BigDecimal.ONE,Instant.EPOCH,BigDecimal.ONE,Direction.UP,java.time.Duration.ofHours(1),"15m");
        Prediction b=new Prediction(null,coin,"B",1,"B",Instant.EPOCH,BigDecimal.ONE,Instant.EPOCH,BigDecimal.ONE,Direction.UP,java.time.Duration.ofHours(1),"15m");

        simulationService.detect(coin,List.of(a,b),Instant.parse("2026-08-18T10:00:00Z"),new BigDecimal("100"));

        MixTradeSimulation result=opportunities.findTop500ByOrderByOpenedAtDesc().stream().filter(row->row.getOpenedAt().equals(Instant.parse("2026-08-18T10:00:00Z"))).findFirst().map(row->opportunities.findWithCoinById(row.getId()).orElseThrow()).orElseThrow();
        assertThat(result.getBestMix().getId()).isEqualTo(tp2.getId());
        assertThat(result.getBestMix().getId()).isNotEqualTo(tp1.getId());
        assertThat(result.isEligibleForNotification()).isTrue();
        assertThat(result.getTelegramMessageId()).isEqualTo(99L);
        assertThat(result.getNotificationDeliveryStatus()).isEqualTo(MixTradeSimulation.NotificationDeliveryStatus.SENT);
        verify(telegram).send(contains("PIPE/USDT"));
    }
}
