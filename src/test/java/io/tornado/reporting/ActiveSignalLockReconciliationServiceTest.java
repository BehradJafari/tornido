package io.tornado.reporting;

import io.tornado.persistence.ActiveSignalLock;
import io.tornado.persistence.Coin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

class ActiveSignalLockReconciliationServiceTest {
    @Test
    void orderedHistoryIsRecoveredThroughDeadlineBeforeTimeoutIsAttempted() {
        ActiveSignalLockService locks = mock(ActiveSignalLockService.class);
        MixTradeSimulationService simulations = mock(MixTradeSimulationService.class);
        ActiveSignalLock lock = mock(ActiveSignalLock.class);
        Coin coin = new Coin("BTC", "BTCUSDT");
        Instant deadline = Instant.parse("2026-08-22T12:00:00Z");
        Instant now = deadline.plusSeconds(5);
        when(lock.getId()).thenReturn(9L);
        when(lock.getCoin()).thenReturn(coin);
        when(lock.getExpectedCloseAt()).thenReturn(deadline);
        when(locks.dueLocks(now)).thenReturn(List.of(lock));

        new ActiveSignalLockReconciliationService(locks, simulations).checkAndCloseDue(now);

        var order = inOrder(simulations, locks);
        order.verify(simulations).recover("BTCUSDT", deadline);
        order.verify(locks).closeTimedOut(9L, now);
    }

    @Test
    void unavailableOrderedHistoryDefersTimeoutInsteadOfGuessingFromPrice() {
        ActiveSignalLockService locks = mock(ActiveSignalLockService.class);
        MixTradeSimulationService simulations = mock(MixTradeSimulationService.class);
        ActiveSignalLock lock = mock(ActiveSignalLock.class);
        Coin coin = new Coin("BTC", "BTCUSDT");
        Instant deadline = Instant.parse("2026-08-22T12:00:00Z");
        Instant now = deadline.plusSeconds(5);
        when(lock.getId()).thenReturn(9L);
        when(lock.getCoin()).thenReturn(coin);
        when(lock.getExpectedCloseAt()).thenReturn(deadline);
        when(locks.dueLocks(now)).thenReturn(List.of(lock));
        doThrow(new IllegalStateException("ordered trades unavailable"))
                .when(simulations).recover("BTCUSDT", deadline);

        new ActiveSignalLockReconciliationService(locks, simulations).checkAndCloseDue(now);

        verify(locks, never()).closeTimedOut(anyLong(), any());
    }
}
