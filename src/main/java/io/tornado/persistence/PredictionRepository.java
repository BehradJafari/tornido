package io.tornado.persistence;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface PredictionRepository extends JpaRepository<Prediction,Long> {
    @EntityGraph(attributePaths={"coin"})
    @Query("select p from Prediction p where p.outcome=:outcome and p.signalVersion=:signalVersion and p.targetAt<=:dueAt order by p.targetAt asc")
    List<Prediction> findDue(@Param("outcome") Outcome outcome,@Param("signalVersion") int signalVersion,@Param("dueAt") Instant dueAt);
    @EntityGraph(attributePaths={"coin","analysisRun"})
    @Query("select p from Prediction p where p.signalVersion=:signalVersion and (:coin is null or p.coin.symbol=:coin) and (:method is null or p.methodName=:method) and p.predictedAt between :from and :to order by p.predictedAt desc")
    List<Prediction> searchCurrent(@Param("signalVersion") int signalVersion,@Param("coin") String coin,@Param("method") String method,@Param("from") Instant from,@Param("to") Instant to);
    @EntityGraph(attributePaths={"coin","analysisRun"})
    @Query("select p from Prediction p where (:coin is null or p.coin.symbol=:coin) and (:method is null or p.methodName=:method) and p.predictedAt between :from and :to order by p.predictedAt desc")
    List<Prediction> searchAll(@Param("coin") String coin,@Param("method") String method,@Param("from") Instant from,@Param("to") Instant to);
    @Query("select p.methodName, count(p), sum(case when p.outcome='CORRECT' then 1 else 0 end), sum(case when (p.predictedDirection='UP' and p.priceAtGrading>p.priceAtPrediction) or (p.predictedDirection='DOWN' and p.priceAtGrading<p.priceAtPrediction) then 1 else 0 end) from Prediction p where p.signalVersion=:signalVersion and p.outcome in ('CORRECT','INCORRECT') and p.predictedAt>=:from and (:coin is null or p.coin.symbol=:coin) and p.horizonSeconds=:horizon group by p.methodName")
    List<Object[]> leaderboard(@Param("coin") String coin,@Param("from") Instant from,@Param("horizon") long horizon,@Param("signalVersion") int signalVersion);
    @EntityGraph(attributePaths={"coin","analysisRun"}) List<Prediction> findTop200ByOrderByPredictedAtDesc();
    @EntityGraph(attributePaths={"coin","analysisRun"})
    List<Prediction> findByAnalysisRunIdOrderByCoinSymbolAscMethodNameAsc(Long runId);
    @EntityGraph(attributePaths={"coin","analysisRun"}) List<Prediction> findByMethodNameOrderByPredictedAtDesc(String methodName);
    @EntityGraph(attributePaths={"coin","analysisRun"})
    List<Prediction> findByMethodNameAndSignalVersionAndHorizonSecondsOrderByPredictedAtDesc(String methodName,int signalVersion,long horizonSeconds);

    @Query("""
        select p.analysisRun.id as runId,p.coin.id as coinId,p.coin.symbol as coinSymbol,p.coin.pair as coinPair,
               p.methodName as methodName,p.strategyCode as strategyCode,p.strategyVersion as strategyVersion,p.predictedAt as predictedAt,p.predictedDirection as predictedDirection,
               p.priceAtPrediction as priceAtPrediction,p.horizonSeconds as horizonSeconds,
               p.priceAtGrading as priceAtGrading,p.outcome as outcome
        from Prediction p
        where p.signalVersion=:signalVersion and p.outcome in ('CORRECT','INCORRECT') and p.horizonSeconds=:horizon
        """)
    List<ReportRow> findGradedReportRows(@Param("horizon") long horizon,@Param("signalVersion") int signalVersion);

    @Query("""
        select p.analysisRun.id as runId,p.coin.id as coinId,p.coin.symbol as coinSymbol,p.coin.pair as coinPair,
               p.methodName as methodName,p.strategyCode as strategyCode,p.strategyVersion as strategyVersion,p.predictedAt as predictedAt,p.predictedDirection as predictedDirection,
               p.priceAtPrediction as priceAtPrediction,p.horizonSeconds as horizonSeconds,
               p.priceAtGrading as priceAtGrading,p.outcome as outcome
        from Prediction p
        where p.signalVersion=:signalVersion and p.outcome in ('CORRECT','INCORRECT')
        """)
    List<ReportRow> findAllGradedReportRows(@Param("signalVersion") int signalVersion);

    @Query("""
        select p.analysisRun.id as runId,p.coin.id as coinId,p.coin.symbol as coinSymbol,
               p.methodName as methodName,p.strategyCode as strategyCode,p.strategyVersion as strategyVersion,p.predictedDirection as predictedDirection,
               p.priceAtPrediction as priceAtPrediction,p.horizonSeconds as horizonSeconds,
               p.priceAtGrading as priceAtGrading
        from Prediction p
        where p.signalVersion=:signalVersion and p.outcome in ('CORRECT','INCORRECT')
          and p.coin.symbol in :coinSymbols and p.horizonSeconds in :horizons
        """)
    List<MixSourceRow> findExcelReportRows(@Param("signalVersion") int signalVersion,
                                           @Param("coinSymbols") Collection<String> coinSymbols,
                                           @Param("horizons") Collection<Long> horizons);

    @Query("""
        select max(p.gradedAt) from Prediction p
        where p.signalVersion=:signalVersion and p.outcome in ('CORRECT','INCORRECT')
        """)
    Instant findLatestGradedAt(@Param("signalVersion") int signalVersion);

    @Query("""
        select p.analysisRun.id as runId,p.coin.id as coinId,p.coin.symbol as coinSymbol,p.coin.pair as coinPair,
               p.methodName as methodName,p.strategyCode as strategyCode,p.strategyVersion as strategyVersion,p.predictedAt as predictedAt,p.predictedDirection as predictedDirection,
               p.priceAtPrediction as priceAtPrediction,p.horizonSeconds as horizonSeconds,
               p.priceAtGrading as priceAtGrading,p.outcome as outcome
        from Prediction p
        where p.signalVersion=:signalVersion and p.outcome in ('CORRECT','INCORRECT') and p.horizonSeconds=:horizon
          and p.coin.symbol=:coin and p.methodName in :methods
        """)
    List<ReportRow> findMoneyReportRows(@Param("coin") String coin,@Param("horizon") long horizon,@Param("methods") Collection<String> methods,@Param("signalVersion") int signalVersion);

    @Query("""
        select p.analysisRun.id as runId,p.coin.id as coinId,p.coin.symbol as coinSymbol,p.coin.pair as coinPair,
               p.methodName as methodName,p.strategyCode as strategyCode,p.strategyVersion as strategyVersion,p.predictedAt as predictedAt,p.predictedDirection as predictedDirection,
               p.priceAtPrediction as priceAtPrediction,p.horizonSeconds as horizonSeconds,
               p.priceAtGrading as priceAtGrading,p.outcome as outcome
        from Prediction p where p.analysisRun.id=:runId and p.signalVersion=:signalVersion and p.horizonSeconds=:horizon
        """)
    List<ReportRow> findLiveReportRows(@Param("runId") long runId,@Param("horizon") long horizon,@Param("signalVersion") int signalVersion);

    @Query("""
        select p.analysisRun.id as runId,p.coin.id as coinId,p.coin.symbol as coinSymbol,p.coin.pair as coinPair,
               p.methodName as methodName,p.strategyCode as strategyCode,p.strategyVersion as strategyVersion,p.predictedAt as predictedAt,p.predictedDirection as predictedDirection,
               p.priceAtPrediction as priceAtPrediction,p.horizonSeconds as horizonSeconds,
               p.priceAtGrading as priceAtGrading,p.outcome as outcome
        from Prediction p where p.signalVersion=3 and p.outcome in ('CORRECT','INCORRECT')
          and p.coin.id=:coinId and p.horizonSeconds=:horizon
        """)
    List<ReportRow> findGradedReportRows(@Param("coinId") long coinId,@Param("horizon") long horizon);

    @Query("""
        select p.analysisRun.id as runId,count(p) as predictions,
               sum(case when p.outcome='PENDING' then 1 else 0 end) as pending,
               sum(case when p.outcome='UNGRADABLE' then 1 else 0 end) as ungradable,
               sum(case when p.outcome='CORRECT' then 1 else 0 end) as targetCorrect,
               sum(case when p.outcome in ('CORRECT','INCORRECT') then 1 else 0 end) as graded,
               sum(case when p.outcome in ('CORRECT','INCORRECT') and
                    ((p.predictedDirection='UP' and p.priceAtGrading>p.priceAtPrediction) or
                     (p.predictedDirection='DOWN' and p.priceAtGrading<p.priceAtPrediction)) then 1 else 0 end) as directionalCorrect
        from Prediction p where p.analysisRun.id in :runIds group by p.analysisRun.id
        """)
    List<RunStats> summarizeRuns(@Param("runIds") Collection<Long> runIds);

    interface MixSourceRow {
        Long getRunId(); Long getCoinId(); String getCoinSymbol(); String getMethodName(); String getStrategyCode(); int getStrategyVersion();
        Direction getPredictedDirection(); BigDecimal getPriceAtPrediction();
        long getHorizonSeconds(); BigDecimal getPriceAtGrading();
    }
    interface ReportRow extends MixSourceRow {
        String getCoinPair();
        Instant getPredictedAt(); Outcome getOutcome();
    }
    interface RunStats {
        Long getRunId(); long getPredictions(); long getPending(); long getUngradable(); long getTargetCorrect();
        long getGraded(); long getDirectionalCorrect();
    }
}
