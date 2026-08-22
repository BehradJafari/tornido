package io.tornado.reporting;

import io.tornado.notification.TelegramMessageFormatter;
import io.tornado.notification.TelegramNotificationService;
import io.tornado.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OpportunityNotificationListenerTest {
    @Test void successfulDeliveryPersistsMessageIdentity() {
        Fixture f=new Fixture();when(f.telegram.send(anyString())).thenReturn(new TelegramNotificationService.DeliveryResult(TelegramNotificationService.DeliveryResult.Status.SENT,77L,null));
        f.listener.notifyAfterCommit(new OpportunityCommittedEvent(9));
        assertThat(f.opportunity.getTelegramMessageId()).isEqualTo(77);assertThat(f.opportunity.isTelegramSent()).isTrue();assertThat(f.opportunity.getNotificationDeliveryStatus()).isEqualTo(MixTradeSimulation.NotificationDeliveryStatus.SENT);
    }

    @Test void failedDeliveryLeavesCommittedOpportunityAndRecordsFailure() {
        Fixture f=new Fixture();when(f.telegram.send(anyString())).thenReturn(new TelegramNotificationService.DeliveryResult(TelegramNotificationService.DeliveryResult.Status.FAILED,null,"timeout"));
        f.listener.notifyAfterCommit(new OpportunityCommittedEvent(9));
        assertThat(f.opportunity.getId()).isEqualTo(9);assertThat(f.opportunity.isTelegramSent()).isFalse();assertThat(f.opportunity.getNotificationDeliveryStatus()).isEqualTo(MixTradeSimulation.NotificationDeliveryStatus.FAILED);assertThat(f.opportunity.getNotificationError()).isEqualTo("timeout");
    }

    @Test void deliveryExceptionLeavesCommittedOpportunityAndRecordsFailure() {
        Fixture f=new Fixture();when(f.telegram.send(anyString())).thenThrow(new IllegalStateException("network down"));
        f.listener.notifyAfterCommit(new OpportunityCommittedEvent(9));
        assertThat(f.opportunity.getId()).isEqualTo(9);assertThat(f.opportunity.getNotificationDeliveryStatus()).isEqualTo(MixTradeSimulation.NotificationDeliveryStatus.FAILED);assertThat(f.opportunity.getNotificationError()).isEqualTo("network down");
    }

    @Test void formatterFailureIsCaughtAndPersisted() {
        Fixture f=new Fixture();TelegramMessageFormatter formatter=mock(TelegramMessageFormatter.class);when(formatter.open(f.opportunity)).thenThrow(new IllegalStateException("bad lazy field"));
        new OpportunityNotificationListener(f.repository,f.telegram,formatter).notifyAfterCommit(new OpportunityCommittedEvent(9));
        assertThat(f.opportunity.getNotificationDeliveryStatus()).isEqualTo(MixTradeSimulation.NotificationDeliveryStatus.FAILED);
        assertThat(f.opportunity.getNotificationError()).isEqualTo("bad lazy field");verifyNoInteractions(f.telegram);
    }

    @Test void missingCommittedOpportunityIsCaughtBeforeTelegram() {
        Fixture f=new Fixture();when(f.repository.findWithCoinById(9L)).thenReturn(Optional.empty());
        f.listener.notifyAfterCommit(new OpportunityCommittedEvent(9));
        verifyNoInteractions(f.telegram);
    }

    @Test void rateLimitedDeliveryIsPersistedAsExplicitFailure() {
        Fixture f=new Fixture();when(f.telegram.send(anyString())).thenReturn(new TelegramNotificationService.DeliveryResult(TelegramNotificationService.DeliveryResult.Status.RATE_LIMITED,null,"retry_after=17"));
        f.listener.notifyAfterCommit(new OpportunityCommittedEvent(9));
        assertThat(f.opportunity.getNotificationDeliveryStatus()).isEqualTo(MixTradeSimulation.NotificationDeliveryStatus.FAILED);
        assertThat(f.opportunity.getNotificationError()).isEqualTo("retry_after=17");
    }

    static class Fixture {
        final MixTradeSimulationRepository repository=mock(MixTradeSimulationRepository.class);final TelegramNotificationService telegram=mock(TelegramNotificationService.class);final MixTradeSimulation opportunity;final OpportunityNotificationListener listener;
        Fixture(){Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);BestMethodMix mix=new BestMethodMix(coin,900,1,1,List.of("RSI"),List.of(1),List.of("RSI"),100,70,70,.6,1,new BigDecimal("0.30"));ReflectionTestUtils.setField(mix,"id",2L);opportunity=new MixTradeSimulation(coin,mix,Direction.UP,1,new BigDecimal("100"),TpSlLevels.defaults(),Instant.EPOCH,new BigDecimal("60"),true,null);ReflectionTestUtils.setField(opportunity,"id",9L);when(repository.findWithCoinById(9L)).thenReturn(Optional.of(opportunity));listener=new OpportunityNotificationListener(repository,telegram,new TelegramMessageFormatter());}
    }
}
