package io.tornado.reporting;

import io.tornado.notification.TelegramMessageFormatter;
import io.tornado.notification.TelegramNotificationService;
import io.tornado.persistence.ActiveSignalLock;
import io.tornado.persistence.ActiveSignalLockRepository;
import io.tornado.persistence.MixTradeSimulation;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ActiveSignalLockTelegramListenerTest {
    @Test
    void telegramEditFailureNeverEscapesTheAfterCommitListener() {
        ActiveSignalLockRepository locks = mock(ActiveSignalLockRepository.class);
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        TelegramMessageFormatter messages = mock(TelegramMessageFormatter.class);
        ActiveSignalLock lock = mock(ActiveSignalLock.class);
        MixTradeSimulation simulation = mock(MixTradeSimulation.class);
        when(lock.getId()).thenReturn(7L);
        when(lock.getSimulation()).thenReturn(simulation);
        when(simulation.getTelegramMessageId()).thenReturn(42L);
        when(locks.findBySimulationId(9L)).thenReturn(Optional.of(lock));
        when(messages.activeLockClosed(lock)).thenReturn("final state");
        when(telegram.edit(42L, "final state")).thenThrow(new IllegalStateException("Telegram unavailable"));

        ActiveSignalLockTelegramListener listener =
                new ActiveSignalLockTelegramListener(locks, telegram, messages);

        assertThatCode(() -> listener.updateFinalMessage(new ActiveSignalLockClosedEvent(
                7L, 9L, ActiveSignalLock.Status.CLOSED_TP))).doesNotThrowAnyException();
        verify(telegram).edit(42L, "final state");
    }

    @Test
    void missingTelegramMessageIdCreatesNoSecondMessage() {
        ActiveSignalLockRepository locks = mock(ActiveSignalLockRepository.class);
        TelegramNotificationService telegram = mock(TelegramNotificationService.class);
        ActiveSignalLock lock = mock(ActiveSignalLock.class);
        MixTradeSimulation simulation = mock(MixTradeSimulation.class);
        when(lock.getSimulation()).thenReturn(simulation);
        when(simulation.getTelegramMessageId()).thenReturn(null);
        when(locks.findBySimulationId(9L)).thenReturn(Optional.of(lock));

        new ActiveSignalLockTelegramListener(locks, telegram, mock(TelegramMessageFormatter.class))
                .updateFinalMessage(new ActiveSignalLockClosedEvent(
                        7L, 9L, ActiveSignalLock.Status.CLOSED_TIMEOUT));

        verify(telegram, never()).edit(anyLong(), anyString());
        verify(telegram, never()).send(anyString());
    }
}
