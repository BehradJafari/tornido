package io.tornado.reporting;

import io.tornado.notification.TelegramMessageFormatter;
import io.tornado.notification.TelegramNotificationService;
import io.tornado.persistence.ActiveSignalLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ActiveSignalLockTelegramListener {
    private static final Logger log = LoggerFactory.getLogger(ActiveSignalLockTelegramListener.class);
    private final ActiveSignalLockRepository locks;
    private final TelegramNotificationService telegram;
    private final TelegramMessageFormatter messages;

    public ActiveSignalLockTelegramListener(ActiveSignalLockRepository locks,
                                            TelegramNotificationService telegram,
                                            TelegramMessageFormatter messages) {
        this.locks = locks;
        this.telegram = telegram;
        this.messages = messages;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void updateFinalMessage(ActiveSignalLockClosedEvent event) {
        try {
            // This repository method fetches the simulation and coin required by the formatter.
            // AFTER_COMMIT listeners do not have the transaction that originally loaded the lock.
            var lock = locks.findBySimulationId(event.simulationId()).orElseThrow();
            Long messageId = lock.getSimulation().getTelegramMessageId();
            if (messageId == null) return;
            var result = telegram.edit(messageId, messages.activeLockClosed(lock));
            if (result.status() != TelegramNotificationService.DeliveryResult.Status.SENT) {
                log.warn("Active signal lock {} closed but Telegram edit returned {}: {}",
                        lock.getId(), result.status(), result.detail());
            }
        } catch (RuntimeException error) {
            log.warn("Active signal lock {} closure persisted but Telegram final update failed",
                    event.lockId(), error);
        }
    }
}
