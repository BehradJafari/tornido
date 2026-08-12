package io.tornado.persistence;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface PredictionRepository extends JpaRepository<Prediction,Long> {
    List<Prediction> findByOutcomeAndSignalVersionOrderByPredictedAtAsc(Outcome outcome,int signalVersion);
    @Query("select p from Prediction p where p.signalVersion=:signalVersion and (:coin is null or p.coin.symbol=:coin) and (:method is null or p.methodName=:method) and p.predictedAt between :from and :to order by p.predictedAt desc")
    List<Prediction> searchCurrent(@Param("signalVersion") int signalVersion,@Param("coin") String coin,@Param("method") String method,@Param("from") Instant from,@Param("to") Instant to);
    @Query("select p from Prediction p where (:coin is null or p.coin.symbol=:coin) and (:method is null or p.methodName=:method) and p.predictedAt between :from and :to order by p.predictedAt desc")
    List<Prediction> searchAll(@Param("coin") String coin,@Param("method") String method,@Param("from") Instant from,@Param("to") Instant to);
    @Query("select p.methodName, count(p), sum(case when p.outcome='CORRECT' then 1 else 0 end), sum(case when (p.predictedDirection='UP' and p.priceAtGrading>p.priceAtPrediction) or (p.predictedDirection='DOWN' and p.priceAtGrading<p.priceAtPrediction) then 1 else 0 end) from Prediction p where p.signalVersion=2 and p.outcome in ('CORRECT','INCORRECT') and p.predictedAt>=:from and (:coin is null or p.coin.symbol=:coin) and p.horizonSeconds=:horizon group by p.methodName")
    List<Object[]> leaderboard(@Param("coin") String coin,@Param("from") Instant from,@Param("horizon") long horizon);
    List<Prediction> findTop200ByOrderByPredictedAtDesc();
    List<Prediction> findByAnalysisRunIdOrderByCoinSymbolAscMethodNameAsc(Long runId);
    List<Prediction> findByMethodNameOrderByPredictedAtDesc(String methodName);
    List<Prediction> findByMethodNameAndSignalVersionOrderByPredictedAtDesc(String methodName,int signalVersion);
    @Query("select p from Prediction p where p.signalVersion=2 and p.outcome in ('CORRECT','INCORRECT') order by p.predictedAt desc") List<Prediction> findAllGraded();
}
