package io.tornado.persistence;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface PredictionRepository extends JpaRepository<Prediction,Long> {
    List<Prediction> findByOutcomeAndSignalVersionOrderByPredictedAtAsc(Outcome outcome,int signalVersion);
    @Query("select p from Prediction p where (:coin is null or p.coin.symbol=:coin) and (:method is null or p.methodName=:method) and p.predictedAt between :from and :to order by p.predictedAt desc")
    List<Prediction> search(@Param("coin") String coin,@Param("method") String method,@Param("from") Instant from,@Param("to") Instant to);
    @Query("select p.methodName, count(p), sum(case when p.outcome='CORRECT' then 1 else 0 end) from Prediction p where p.signalVersion=2 and p.outcome<>'PENDING' and p.predictedAt>=:from and (:coin is null or p.coin.symbol=:coin) and (:horizon=0 or p.horizonSeconds=:horizon) group by p.methodName")
    List<Object[]> leaderboard(@Param("coin") String coin,@Param("from") Instant from,@Param("horizon") long horizon);
    List<Prediction> findTop200ByOrderByPredictedAtDesc();
    List<Prediction> findByAnalysisRunIdOrderByCoinSymbolAscMethodNameAsc(Long runId);
    List<Prediction> findByMethodNameOrderByPredictedAtDesc(String methodName);
    List<Prediction> findByMethodNameAndSignalVersionOrderByPredictedAtDesc(String methodName,int signalVersion);
    @Query("select p from Prediction p where p.signalVersion=2 and p.outcome <> 'PENDING' order by p.predictedAt desc") List<Prediction> findAllGraded();
}
