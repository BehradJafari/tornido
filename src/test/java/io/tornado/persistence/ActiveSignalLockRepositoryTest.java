package io.tornado.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:active-lock-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ActiveSignalLockRepositoryTest {
    @Autowired ActiveSignalLockRepository locks;
    @Autowired TestEntityManager entityManager;

    @Test
    void databaseAllowsOnlyOneOpenLockPerCoinAndHorizon() {
        Coin coin = entityManager.persist(new Coin("BTC", "BTCUSDT"));
        BestMethodMix mix = entityManager.persist(mix(coin, 3600));
        MixTradeSimulation first = entityManager.persist(simulation(coin, mix, Instant.EPOCH));
        MixTradeSimulation second = entityManager.persist(simulation(coin, mix, Instant.EPOCH.plusSeconds(1)));
        entityManager.persistAndFlush(new ActiveSignalLock(
                coin, mix, first, new BigDecimal("100"), Instant.EPOCH));

        assertThatThrownBy(() -> locks.saveAndFlush(new ActiveSignalLock(
                coin, mix, second, new BigDecimal("101"), Instant.EPOCH.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseAllowsIndependentLocksForAnotherHorizonAndAnotherCoin() {
        Coin btc = entityManager.persist(new Coin("BTC2", "BTC2USDT"));
        Coin eth = entityManager.persist(new Coin("ETH2", "ETH2USDT"));
        BestMethodMix btcOneHour = entityManager.persist(mix(btc, 3600));
        BestMethodMix btcFourHours = entityManager.persist(mix(btc, 14400));
        BestMethodMix ethOneHour = entityManager.persist(mix(eth, 3600));
        MixTradeSimulation first = entityManager.persist(simulation(btc, btcOneHour, Instant.EPOCH));
        MixTradeSimulation second = entityManager.persist(simulation(btc, btcFourHours, Instant.EPOCH));
        MixTradeSimulation third = entityManager.persist(simulation(eth, ethOneHour, Instant.EPOCH));

        entityManager.persist(new ActiveSignalLock(btc, btcOneHour, first, BigDecimal.TEN, Instant.EPOCH));
        entityManager.persist(new ActiveSignalLock(btc, btcFourHours, second, BigDecimal.TEN, Instant.EPOCH));
        entityManager.persist(new ActiveSignalLock(eth, ethOneHour, third, BigDecimal.TEN, Instant.EPOCH));
        entityManager.flush();
    }

    @Test
    void closedTpAndSlLocksImmediatelyReleaseTheDatabaseAdmissionScope() {
        Coin coin = entityManager.persist(new Coin("RESUME", "RESUMEUSDT"));
        BestMethodMix mix = entityManager.persist(mix(coin, 3600));
        MixTradeSimulation firstSimulation = entityManager.persist(
                simulation(coin, mix, Instant.EPOCH));
        ActiveSignalLock first = new ActiveSignalLock(
                coin, mix, firstSimulation, BigDecimal.TEN, Instant.EPOCH);
        entityManager.persistAndFlush(first);
        first.close(ActiveSignalLock.Status.CLOSED_TP, Instant.EPOCH.plusSeconds(1), BigDecimal.TEN);
        entityManager.flush();

        MixTradeSimulation secondSimulation = entityManager.persist(
                simulation(coin, mix, Instant.EPOCH.plusSeconds(2)));
        ActiveSignalLock second = new ActiveSignalLock(
                coin, mix, secondSimulation, BigDecimal.TEN, Instant.EPOCH.plusSeconds(2));
        entityManager.persistAndFlush(second);
        second.close(ActiveSignalLock.Status.CLOSED_SL, Instant.EPOCH.plusSeconds(3), BigDecimal.TEN);
        entityManager.flush();

        MixTradeSimulation thirdSimulation = entityManager.persist(
                simulation(coin, mix, Instant.EPOCH.plusSeconds(4)));
        entityManager.persistAndFlush(new ActiveSignalLock(
                coin, mix, thirdSimulation, BigDecimal.TEN, Instant.EPOCH.plusSeconds(4)));
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
}
