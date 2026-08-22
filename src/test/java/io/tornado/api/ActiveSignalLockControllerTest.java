package io.tornado.api;

import io.tornado.persistence.ActiveSignalLock;
import io.tornado.persistence.Coin;
import io.tornado.persistence.Direction;
import io.tornado.persistence.MixTradeSimulation;
import io.tornado.reporting.ActiveSignalLockService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveSignalLockControllerTest {
    @Test
    void openEndpointIncludesServerTimeBoundariesPricesAndMethods() {
        ActiveSignalLockService service = mock(ActiveSignalLockService.class);
        ActiveSignalLock lock = mock(ActiveSignalLock.class);
        MixTradeSimulation simulation = mock(MixTradeSimulation.class);
        Coin coin = new Coin("BTC", "BTCUSDT");
        Instant openedAt = Instant.now().minusSeconds(600);
        Instant expectedCloseAt = openedAt.plusSeconds(3600);
        when(lock.getId()).thenReturn(1L);
        when(lock.getCoin()).thenReturn(coin);
        when(lock.getHorizonSeconds()).thenReturn(3600L);
        when(lock.getSimulation()).thenReturn(simulation);
        when(lock.getOpenedAt()).thenReturn(openedAt);
        when(lock.getExpectedCloseAt()).thenReturn(expectedCloseAt);
        when(lock.getEntryPrice()).thenReturn(new BigDecimal("100"));
        when(simulation.getId()).thenReturn(2L);
        when(simulation.getDirection()).thenReturn(Direction.UP);
        when(simulation.getTp1Price()).thenReturn(new BigDecimal("100.30"));
        when(simulation.getSl1Price()).thenReturn(new BigDecimal("99.70"));
        when(simulation.getMethodNames()).thenReturn(List.of("EMA", "RSI"));
        when(simulation.getNotificationDeliveryStatus())
                .thenReturn(MixTradeSimulation.NotificationDeliveryStatus.SENT);
        when(service.openLocks()).thenReturn(List.of(lock));

        ActiveSignalLockController.OpenLocksResponse response =
                new ActiveSignalLockController(service).open();

        assertThat(response.serverNow()).isBetween(openedAt, Instant.now().plusSeconds(1));
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.pair()).isEqualTo("BTCUSDT");
            assertThat(item.elapsedSeconds()).isGreaterThanOrEqualTo(600);
            assertThat(item.remainingSeconds()).isBetween(2998L, 3000L);
            assertThat(item.expectedCloseAt()).isEqualTo(expectedCloseAt);
            assertThat(item.tp1Price()).isEqualByComparingTo("100.30");
            assertThat(item.sl1Price()).isEqualByComparingTo("99.70");
            assertThat(item.methods()).containsExactly("EMA", "RSI");
        });
    }
}
