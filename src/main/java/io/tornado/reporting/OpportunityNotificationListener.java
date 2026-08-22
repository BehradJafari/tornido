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
        log.info("SIGNAL_NOTIFY_AUDIT after-commit-received opportunityId={}",event.opportunityId());
        MixTradeSimulation opportunity;
        try {
            opportunity=opportunities.findWithCoinById(event.opportunityId()).orElseThrow(() -> new IllegalStateException("Committed opportunity not found: "+event.opportunityId()));
        } catch (RuntimeException error) {
            log.error("SIGNAL_NOTIFY_AUDIT rejected reason={} opportunityId={}",SignalNotificationAuditReason.AFTER_COMMIT_LOAD_FAILED,event.opportunityId(),error);
            return;
        }
        String message;
        try {
            log.info("SIGNAL_NOTIFY_AUDIT format-start opportunityId={}",opportunity.getId());
            message=messages.open(opportunity);
            log.info("SIGNAL_NOTIFY_AUDIT format-success opportunityId={} chars={}",opportunity.getId(),message.length());
        } catch (RuntimeException error) {
            opportunity.notificationDelivery(MixTradeSimulation.NotificationDeliveryStatus.FAILED,error.getMessage());
            log.error("SIGNAL_NOTIFY_AUDIT rejected reason={} opportunityId={}",SignalNotificationAuditReason.FORMAT_FAILED,opportunity.getId(),error);
            return;
        }
        if(message.length()>4096){
            String detail="Telegram message exceeds 4096 characters: "+message.length();
            opportunity.notificationDelivery(MixTradeSimulation.NotificationDeliveryStatus.FAILED,detail);
            log.error("SIGNAL_NOTIFY_AUDIT rejected reason={} opportunityId={} chars={}",SignalNotificationAuditReason.MESSAGE_TOO_LONG,opportunity.getId(),message.length());
            return;
        }
        try {
            log.info("SIGNAL_NOTIFY_AUDIT telegram-send-start opportunityId={} chars={}",opportunity.getId(),message.length());
            var delivery=telegram.send(message);
            if(delivery.status()==TelegramNotificationService.DeliveryResult.Status.SENT) opportunity.telegramMessage(delivery.messageId());
            else opportunity.notificationDelivery(delivery.status()==TelegramNotificationService.DeliveryResult.Status.SKIPPED?MixTradeSimulation.NotificationDeliveryStatus.SKIPPED:MixTradeSimulation.NotificationDeliveryStatus.FAILED,delivery.detail());
            SignalNotificationAuditReason reason=delivery.status()==TelegramNotificationService.DeliveryResult.Status.RATE_LIMITED?SignalNotificationAuditReason.TELEGRAM_RATE_LIMITED:delivery.status()==TelegramNotificationService.DeliveryResult.Status.FAILED?SignalNotificationAuditReason.TELEGRAM_FAILED:null;
            log.info("SIGNAL_NOTIFY_AUDIT telegram-send-result opportunityId={} status={} messageId={} detail={} reason={}",opportunity.getId(),delivery.status(),delivery.messageId(),delivery.detail(),reason);
        } catch(RuntimeException error) {
            opportunity.notificationDelivery(MixTradeSimulation.NotificationDeliveryStatus.FAILED,error.getMessage());
            log.error("SIGNAL_NOTIFY_AUDIT rejected reason={} opportunityId={}",SignalNotificationAuditReason.TELEGRAM_FAILED,opportunity.getId(),error);
        }
    }
}
