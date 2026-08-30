package io.tornado.reporting;

import io.tornado.api.ReportService;
import io.tornado.datafetch.BinanceMarketDataClient;
import io.tornado.persistence.*;
import io.tornado.persistence.PredictionRepository.ReportRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MixStatisticsCalculatorTest {
    @Test void coinReportAndBestMixEngineReturnIdenticalExactStrategyStatistics(){
        List<ReportRow> rows=new ArrayList<>();for(long run=1;run<=10;run++){rows.add(row(run,"EMA v2","EMA",2,Direction.UP,run<=8?"101":"99"));rows.add(row(run,"EMA v3","EMA",3,Direction.UP,run<=8?"101":"99"));}
        MixStatisticsCalculator calculator=new MixStatisticsCalculator();var shared=calculator.calculate(rows,new BigDecimal(".30"),List.of(2)).getFirst();
        PredictionRepository predictions=mock(PredictionRepository.class);when(predictions.findGradedReportRows(3600,3)).thenReturn(rows);
        ReportService reports=new ReportService(predictions,mock(AnalysisRunRepository.class),mock(BinanceMarketDataClient.class),null,calculator);

        ReportService.MixAccuracy report=reports.coinMixReports(1,2,3600,3,1).getFirst().mixes().getFirst();

        assertThat(shared.strategyIdentity()).isEqualTo("EMA@2,EMA@3");
        assertThat(report.samples()).isEqualTo(shared.samples());assertThat(report.targetCorrect()).isEqualTo(shared.targetHits());assertThat(report.targetHitRate()).isEqualTo(shared.targetHitRate());assertThat(report.directionalAccuracy()).isEqualTo(shared.directionalAccuracy());
    }

    private ReportRow row(long run,String name,String code,int version,Direction direction,String exit){return new R(run,1L,"BTC","BTCUSDT",name,code,version,Instant.EPOCH.plusSeconds(run),direction,new BigDecimal("100"),3600,new BigDecimal(exit),Outcome.CORRECT);}
    record R(Long runId,Long coinId,String coinSymbol,String coinPair,String methodName,String strategyCode,int strategyVersion,Instant predictedAt,Direction predictedDirection,BigDecimal priceAtPrediction,long horizonSeconds,BigDecimal priceAtGrading,Outcome outcome)implements ReportRow{public Long getRunId(){return runId;}public Long getCoinId(){return coinId;}public String getCoinSymbol(){return coinSymbol;}public String getCoinPair(){return coinPair;}public String getMethodName(){return methodName;}public String getStrategyCode(){return strategyCode;}public int getStrategyVersion(){return strategyVersion;}public Instant getPredictedAt(){return predictedAt;}public Direction getPredictedDirection(){return predictedDirection;}public BigDecimal getPriceAtPrediction(){return priceAtPrediction;}public long getHorizonSeconds(){return horizonSeconds;}public BigDecimal getPriceAtGrading(){return priceAtGrading;}public Outcome getOutcome(){return outcome;}}
}
