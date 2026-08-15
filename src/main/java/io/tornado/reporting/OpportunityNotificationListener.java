package io.tornado.reporting;

import io.tornado.notification.TelegramMessageFormatter;
import io.tornado.notification.TelegramNotificationService;
import io.tornado.persistence.MixTradeSimulation;
import io.tornado.persistence.MixTradeSimulationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OpportunityNotificationListener {
    private static final Logger log=LoggerFactory.getLogger(OpportunityNotificationListener.class);
    private final MixTradeSimulationRepository opportunities;
    private final TelegramNotificationService telegram;
    private final TelegramMessageFormatter messages;

    public OpportunityNotificationListener(MixTradeSimulationRepository opportunities, TelegramNotificationService telegram, TelegramMessageFormatter messages) {
        this.opportunities=opportunities;this.telegram=telegram;this.messages=messages;
    }

    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void notifyAfterCommit(OpportunityCommittedEvent event) {
        MixTradeSimulation opportunity=opportunities.findWithCoinById(event.opportunityId()).orElseThrow();
        try {
            var delivery=telegram.send(messages.open(opportunity));
            if(delivery.status()==TelegramNotificationService.DeliveryResult.Status.SENT) opportunity.telegramMessage(delivery.messageId());
            else opportunity.notificationDelivery(delivery.status()==TelegramNotificationService.DeliveryResult.Status.SKIPPED?MixTradeSimulation.NotificationDeliveryStatus.SKIPPED:MixTradeSimulation.NotificationDeliveryStatus.FAILED,delivery.detail());
        } catch(RuntimeException error) {
            opportunity.notificationDelivery(MixTradeSimulation.NotificationDeliveryStatus.FAILED,error.getMessage());
            log.warn("Opportunity {} is committed but Telegram delivery failed",opportunity.getId(),error);
        }
    }
}
