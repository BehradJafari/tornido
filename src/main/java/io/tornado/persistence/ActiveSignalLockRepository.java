package io.tornado.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ActiveSignalLockRepository extends JpaRepository<ActiveSignalLock, Long> {
    boolean existsByCoinIdAndHorizonSecondsAndStatus(long coinId, long horizonSeconds, ActiveSignalLock.Status status);

    boolean existsBySimulationId(long simulationId);

    long countByCoinIdAndHorizonSecondsAndStatus(
            long coinId,
            long horizonSeconds,
            ActiveSignalLock.Status status
    );

    @EntityGraph(attributePaths = {"coin", "bestMethodMix", "simulation", "simulation.coin"})
    List<ActiveSignalLock> findByStatusOrderByOpenedAtDesc(ActiveSignalLock.Status status);

    @EntityGraph(attributePaths = {"coin", "bestMethodMix", "simulation", "simulation.coin"})
    Optional<ActiveSignalLock> findBySimulationId(long simulationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"coin", "bestMethodMix", "simulation", "simulation.coin"})
    @Query("select l from ActiveSignalLock l where l.simulation.id=:simulationId and l.status=:status")
    Optional<ActiveSignalLock> lockOpenBySimulationId(@Param("simulationId") long simulationId,
                                                       @Param("status") ActiveSignalLock.Status status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"coin", "bestMethodMix", "simulation", "simulation.coin"})
    @Query("select l from ActiveSignalLock l where l.id=:id and l.status=:status")
    Optional<ActiveSignalLock> lockOpenById(@Param("id") long id,
                                            @Param("status") ActiveSignalLock.Status status);

    @EntityGraph(attributePaths = {"coin", "bestMethodMix", "simulation", "simulation.coin"})
    List<ActiveSignalLock> findByStatusAndExpectedCloseAtLessThanEqualOrderByExpectedCloseAtAsc(
            ActiveSignalLock.Status status, Instant through);
}
