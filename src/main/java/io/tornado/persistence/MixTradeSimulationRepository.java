package io.tornado.persistence;
import org.springframework.data.jpa.repository.*;import org.springframework.data.repository.query.Param;import jakarta.persistence.LockModeType;import java.time.*;import java.util.*;
public interface MixTradeSimulationRepository extends JpaRepository<MixTradeSimulation,Long>{
 @EntityGraph(attributePaths={"coin","bestMix"})List<MixTradeSimulation>findTop500ByOrderByOpenedAtDesc();@EntityGraph(attributePaths={"coin","bestMix"})List<MixTradeSimulation>findTop500ByStatusOrderByOpenedAtDesc(MixTradeSimulation.Status status);@EntityGraph(attributePaths={"coin","bestMix"})@Query("select s from MixTradeSimulation s where s.id=:id")Optional<MixTradeSimulation>findWithCoinById(@Param("id")Long id);
 @Lock(LockModeType.PESSIMISTIC_WRITE)@EntityGraph(attributePaths={"coin","bestMix"})@Query("select s from MixTradeSimulation s where s.status='OPEN' and s.coin.pair=:pair")List<MixTradeSimulation>lockOpenByPair(@Param("pair")String pair);
 @EntityGraph(attributePaths={"coin","bestMix"})List<MixTradeSimulation>findByOpenedAtGreaterThanEqualAndOpenedAtLessThan(Instant start,Instant end);@EntityGraph(attributePaths={"coin","bestMix"})List<MixTradeSimulation>findByClosedAtGreaterThanEqualAndClosedAtLessThan(Instant start,Instant end);long countByStatus(MixTradeSimulation.Status status);
}
