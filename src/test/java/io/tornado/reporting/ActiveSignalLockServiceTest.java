package io.tornado.reporting;

import io.tornado.persistence.ActiveSignalLock;
import io.tornado.persistence.ActiveSignalLockRepository;
import io.tornado.persistence.BestMethodMix;
import io.tornado.persistence.Coin;
import io.tornado.persistence.Direction;
import io.tornado.persistence.MixTradeSimulation;
import io.tornado.persistence.TpSlLevels;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ActiveSignalLockServiceTest {
    private static final Instant OPENED_AT = Instant.parse("2026-08-22T10:00:00Z");

    @Test
    void existingOpenPositionIsAControlledSuppression() {
        Fixture fixture = new Fixture(3600);
        when(fixture.repository.existsByCoinIdAndHorizonSecondsAndStatus(
                1L, 3600, ActiveSignalLock.Status.OPEN)).thenReturn(true);

        ActiveSignalLockService.AdmissionResult result = fixture.service.tryOpen(
                fixture.coin, fixture.mix, fixture.simulation, new BigDecimal("100"), OPENED_AT);

        assertThat(result.admitted()).isFalse();
        assertThat(result.reason()).isEqualTo(SignalNotificationAuditReason.POSITION_ALREADY_OPEN);
        verify(fixture.repository, never()).saveAndFlush(any());
    }

    @Test
    void unsupportedShortHorizonNeverCreatesALiveLock() {
        Fixture fixture = new Fixture(900);

        ActiveSignalLockService.AdmissionResult result = fixture.service.tryOpen(
                fixture.coin, fixture.mix, fixture.simulation, new BigDecimal("100"), OPENED_AT);

        assertThat(result.admitted()).isFalse();
        assertThat(result.reason()).isEqualTo(SignalNotificationAuditReason.UNSUPPORTED_LIVE_HORIZON);
        verifyNoInteractions(fixture.repository);
    }

    @Test
    void tp1FirstClosesOnceAndPublishesOneClosureEvent() {
        Fixture fixture = new Fixture(3600);
        fixture.simulation.observeMilestones(new BigDecimal("100.31"), OPENED_AT.plusSeconds(10), 100L);
        fixture.simulation.observeMilestones(new BigDecimal("99.69"), OPENED_AT.plusSeconds(10), 101L);
        when(fixture.repository.lockOpenBySimulationId(20L, ActiveSignalLock.Status.OPEN))
                .thenReturn(Optional.of(fixture.lock));

        assertThat(fixture.service.synchronizeFromSimulation(fixture.simulation)).isTrue();
        assertThat(fixture.service.synchronizeFromSimulation(fixture.simulation)).isFalse();
        assertThat(fixture.lock.getStatus()).isEqualTo(ActiveSignalLock.Status.CLOSED_TP);
        assertThat(fixture.lock.getClosedAt()).isEqualTo(OPENED_AT.plusSeconds(10));
        verify(fixture.events).publishEvent(any(ActiveSignalLockClosedEvent.class));
    }

    @Test
    void sl1FirstClosesAsFailureUsingAggregateTradeSequence() {
        Fixture fixture = new Fixture(3600);
        fixture.simulation.observeMilestones(new BigDecimal("99.69"), OPENED_AT.plusSeconds(10), 100L);
        fixture.simulation.observeMilestones(new BigDecimal("100.31"), OPENED_AT.plusSeconds(10), 101L);
        when(fixture.repository.lockOpenBySimulationId(20L, ActiveSignalLock.Status.OPEN))
                .thenReturn(Optional.of(fixture.lock));

        assertThat(fixture.service.synchronizeFromSimulation(fixture.simulation)).isTrue();
        assertThat(fixture.lock.getStatus()).isEqualTo(ActiveSignalLock.Status.CLOSED_SL);
    }

    @Test
    void firstTouchBeforeDeadlineWinsEvenWhenReconciledAfterDeadline() {
        Fixture fixture = new Fixture(3600);
        Instant beforeDeadline = fixture.lock.getExpectedCloseAt().minusMillis(100);
        fixture.simulation.observeMilestones(new BigDecimal("100.31"), beforeDeadline, 100L);
        when(fixture.repository.lockOpenById(30L, ActiveSignalLock.Status.OPEN))
                .thenReturn(Optional.of(fixture.lock));

        assertThat(fixture.service.closeTimedOut(30L, fixture.lock.getExpectedCloseAt().plusSeconds(5))).isTrue();
        assertThat(fixture.lock.getStatus()).isEqualTo(ActiveSignalLock.Status.CLOSED_TP);
        assertThat(fixture.lock.getClosedAt()).isEqualTo(beforeDeadline);
    }

    @Test
    void unresolvedPositionClosesAtTheExactHorizonDeadline() {
        Fixture fixture = new Fixture(3600);
        when(fixture.repository.lockOpenById(30L, ActiveSignalLock.Status.OPEN))
                .thenReturn(Optional.of(fixture.lock));

        assertThat(fixture.service.closeTimedOut(30L, fixture.lock.getExpectedCloseAt())).isTrue();
        assertThat(fixture.lock.getStatus()).isEqualTo(ActiveSignalLock.Status.CLOSED_TIMEOUT);
        assertThat(fixture.lock.getClosedAt()).isEqualTo(fixture.lock.getExpectedCloseAt());
    }

    private static final class Fixture {
        final ActiveSignalLockRepository repository = mock(ActiveSignalLockRepository.class);
        final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        final Coin coin = new Coin("BTC", "BTCUSDT");
        final BestMethodMix mix;
        final MixTradeSimulation simulation;
        final ActiveSignalLock lock;
        final ActiveSignalLockService service;

        Fixture(long horizon) {
            ReflectionTestUtils.setField(coin, "id", 1L);
            List<String> methods = List.of("A", "B");
            mix = new BestMethodMix(coin, horizon, 2, 1, methods,
                    Collections.nCopies(2, 1), methods, 100, 70, 70, .60,
                    1, TpSlLevels.defaults().tp1());
            ReflectionTestUtils.setField(mix, "id", 10L);
            simulation = new MixTradeSimulation(coin, mix, Direction.UP, 2,
                    new BigDecimal("100"), TpSlLevels.defaults(), OPENED_AT);
            ReflectionTestUtils.setField(simulation, "id", 20L);
            lock = new ActiveSignalLock(coin, mix, simulation, new BigDecimal("100"), OPENED_AT);
            ReflectionTestUtils.setField(lock, "id", 30L);
            service = new ActiveSignalLockService(repository, new FirstTouchOutcomeResolver(),
                    events, mock(EntityManager.class));
        }
    }
}
