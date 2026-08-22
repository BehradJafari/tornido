package io.tornado.reporting;

import io.tornado.datafetch.LivePriceStream;
import io.tornado.persistence.ActiveSignalLock;
import io.tornado.persistence.ActiveSignalLockRepository;
import io.tornado.persistence.BestMethodMix;
import io.tornado.persistence.BestMethodMixRepository;
import io.tornado.persistence.Coin;
import io.tornado.persistence.CoinRepository;
import io.tornado.persistence.Direction;
import io.tornado.persistence.MixTradeSimulation;
import io.tornado.persistence.MixTradeSimulationRepository;
import io.tornado.persistence.TpSlLevels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.task.scheduling.enabled=false",
        "tornado.scheduler.snapshot-initial-delay=24h",
        "tornado.scheduler.snapshot-interval=24h",
        "tornado.scheduler.grading-interval=24h",
        "tornado.auth.username=test-admin",
        "tornado.auth.password=test-admin-password",
        "tornado.auth.jwt-secret=12345678901234567890123456789012"
})
class ActiveSignalLockPostgresConcurrencyTest {
    private static final Instant OPENED_AT = Instant.parse("2026-08-23T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired CoinRepository coins;
    @Autowired BestMethodMixRepository mixes;
    @Autowired MixTradeSimulationRepository simulations;
    @Autowired ActiveSignalLockRepository locks;
    @Autowired ActiveSignalLockService service;
    @Autowired TransactionTemplate transactions;
    @MockitoBean LivePriceStream livePriceStream;

    @Test
    void concurrentAdmissionHasOneWinnerAndIndependentScopesRemainAvailable() throws Exception {
        Fixture fixture = transactions.execute(ignored -> createFixture());
        assertThat(fixture).isNotNull();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ActiveSignalLockService.AdmissionResult> first = executor.submit(() -> {
                start.await();
                return service.tryOpen(fixture.btc(), fixture.btcOneHour(),
                        fixture.firstSimulation(), new BigDecimal("100"), OPENED_AT);
            });
            Future<ActiveSignalLockService.AdmissionResult> second = executor.submit(() -> {
                start.await();
                return service.tryOpen(fixture.btc(), fixture.btcOneHour(),
                        fixture.secondSimulation(), new BigDecimal("101"), OPENED_AT.plusMillis(1));
            });

            start.countDown();
            List<ActiveSignalLockService.AdmissionResult> results =
                    List.of(first.get(), second.get());

            assertThat(results).filteredOn(ActiveSignalLockService.AdmissionResult::admitted)
                    .hasSize(1);
            assertThat(results).filteredOn(result -> !result.admitted())
                    .singleElement()
                    .extracting(ActiveSignalLockService.AdmissionResult::reason)
                    .isEqualTo(SignalNotificationAuditReason.POSITION_ALREADY_OPEN);
            assertThat(locks.countByCoinIdAndHorizonSecondsAndStatus(
                    fixture.btc().getId(), 3600, ActiveSignalLock.Status.OPEN)).isOne();
        } finally {
            executor.shutdownNow();
        }

        MixTradeSimulation winningSimulation = locks.findByStatusOrderByOpenedAtDesc(
                ActiveSignalLock.Status.OPEN).stream()
                .filter(lock -> lock.getCoin().getId().equals(fixture.btc().getId()))
                .findFirst().orElseThrow().getSimulation();

        assertThatThrownBy(() -> service.tryOpen(
                fixture.btc(), fixture.btcFourHours(), winningSimulation,
                new BigDecimal("102"), OPENED_AT.plusSeconds(2)))
                .isInstanceOf(DataIntegrityViolationException.class);

        ActiveSignalLockService.AdmissionResult otherHorizon = service.tryOpen(
                fixture.btc(), fixture.btcFourHours(), fixture.thirdSimulation(),
                new BigDecimal("102"), OPENED_AT.plusSeconds(3));
        ActiveSignalLockService.AdmissionResult otherCoin = service.tryOpen(
                fixture.eth(), fixture.ethOneHour(), fixture.fourthSimulation(),
                new BigDecimal("103"), OPENED_AT.plusSeconds(4));

        assertThat(otherHorizon.admitted()).isTrue();
        assertThat(otherCoin.admitted()).isTrue();
    }

    private Fixture createFixture() {
        Coin btc = coins.save(new Coin("PG_BTC", "PGBTCUSDT"));
        Coin eth = coins.save(new Coin("PG_ETH", "PGETHUSDT"));
        BestMethodMix btcOneHour = mixes.save(mix(btc, 3600));
        BestMethodMix btcFourHours = mixes.save(mix(btc, 14400));
        BestMethodMix ethOneHour = mixes.save(mix(eth, 3600));
        MixTradeSimulation first = simulations.save(simulation(btc, btcOneHour, OPENED_AT));
        MixTradeSimulation second = simulations.save(
                simulation(btc, btcOneHour, OPENED_AT.plusMillis(1)));
        MixTradeSimulation third = simulations.save(
                simulation(btc, btcFourHours, OPENED_AT.plusSeconds(3)));
        MixTradeSimulation fourth = simulations.save(
                simulation(eth, ethOneHour, OPENED_AT.plusSeconds(4)));
        return new Fixture(btc, eth, btcOneHour, btcFourHours, ethOneHour,
                first, second, third, fourth);
    }

    private BestMethodMix mix(Coin coin, long horizon) {
        return new BestMethodMix(coin, horizon, 2, 1,
                List.of("A", "B"), List.of(1, 1), List.of("A", "B"),
                100, 70, 70, .60, 1, TpSlLevels.defaults().tp1());
    }

    private MixTradeSimulation simulation(Coin coin, BestMethodMix mix, Instant openedAt) {
        return new MixTradeSimulation(coin, mix, Direction.UP, 2,
                new BigDecimal("100"), TpSlLevels.defaults(), openedAt);
    }

    private record Fixture(
            Coin btc,
            Coin eth,
            BestMethodMix btcOneHour,
            BestMethodMix btcFourHours,
            BestMethodMix ethOneHour,
            MixTradeSimulation firstSimulation,
            MixTradeSimulation secondSimulation,
            MixTradeSimulation thirdSimulation,
            MixTradeSimulation fourthSimulation
    ) {}
}
