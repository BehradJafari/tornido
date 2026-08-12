package io.tornado.api;

import io.tornado.datafetch.BinanceMarketDataClient;
import io.tornado.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportServiceTest {
    @Test void detectsLiquidationFromTheIntratradeLowEvenWhenExitIsProfitable(){
        var predictions=mock(PredictionRepository.class);var runs=mock(AnalysisRunRepository.class);var market=mock(BinanceMarketDataClient.class);
        Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);
        AnalysisRun run=new AnalysisRun("run",Duration.ZERO);ReflectionTestUtils.setField(run,"id",1L);
        Instant at=Instant.parse("2026-01-01T00:00:00Z");
        Prediction a=graded(run,coin,"A",at),b=graded(run,coin,"B",at),c=graded(run,coin,"C",at);
        when(predictions.findAllGraded()).thenReturn(List.of(a,b,c));
        when(market.priceRange(eq("BTCUSDT"),eq(at),eq(at.plus(Duration.ofHours(1))))).thenReturn(new BinanceMarketDataClient.PriceRange(new BigDecimal("94"),new BigDecimal("104")));

        var report=new ReportService(predictions,runs,market).moneyReport(new ReportService.MoneyRequest("BTC",3600,List.of("A","B","C"),100,20,0,0,0,0));

        assertThat(report.executedTrades()).isEqualTo(1);
        assertThat(report.liquidations()).isEqualTo(1);
        assertThat(report.netPnl()).isEqualTo(-100);
        assertThat(report.trades().getFirst().liquidated()).isTrue();
    }

    @Test void profitableTradeAndTargetHitAreReportedSeparately(){
        var predictions=mock(PredictionRepository.class);var runs=mock(AnalysisRunRepository.class);var market=mock(BinanceMarketDataClient.class);
        Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);AnalysisRun run=new AnalysisRun("run",Duration.ZERO);ReflectionTestUtils.setField(run,"id",1L);Instant at=Instant.parse("2026-01-01T00:00:00Z");
        List<Prediction> rows=List.of(gradedAt(run,coin,"A",at,"100.20"),gradedAt(run,coin,"B",at,"100.20"),gradedAt(run,coin,"C",at,"100.20"));when(predictions.findAllGraded()).thenReturn(rows);when(market.priceRange(anyString(),any(),any())).thenReturn(new BinanceMarketDataClient.PriceRange(new BigDecimal("99.5"),new BigDecimal("100.2")));

        var report=new ReportService(predictions,runs,market).moneyReport(new ReportService.MoneyRequest("BTC",3600,List.of("A","B","C"),100,1,0,0,0,0));

        assertThat(report.profitableTrades()).isEqualTo(1);assertThat(report.losingTrades()).isZero();assertThat(report.profitWinRate()).isEqualTo(100);assertThat(report.targetHits()).isZero();assertThat(report.targetHitRate()).isZero();
    }

    @Test void rejectsCombinedHorizonsForRankings(){
        var service=new ReportService(mock(PredictionRepository.class),mock(AnalysisRunRepository.class),mock(BinanceMarketDataClient.class));
        assertThatThrownBy(()->service.coinReports(1,0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one time horizon");
    }

    @Test void belowRandomOrStatisticallyWeakMethodsReceiveNoConsensusWeight(){
        var predictions=mock(PredictionRepository.class);var runs=mock(AnalysisRunRepository.class);var market=mock(BinanceMarketDataClient.class);Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);AnalysisRun run=new AnalysisRun("run",Duration.ZERO);ReflectionTestUtils.setField(run,"id",1L);List<Prediction> rows=new ArrayList<>();Instant at=Instant.parse("2026-01-01T00:00:00Z");for(int i=0;i<10;i++)rows.add(gradedAt(run,coin,"A",at.plusSeconds(i),i<4?"101":"99"));when(predictions.findAllGraded()).thenReturn(rows);when(runs.findTopByOrderByCreatedAtDesc()).thenReturn(Optional.of(run));when(predictions.findByAnalysisRunIdOrderByCoinSymbolAscMethodNameAsc(1L)).thenReturn(rows);

        var report=new ReportService(predictions,runs,market).superReport(3,3600);

        assertThat(report.coins()).hasSize(1);assertThat(report.coins().getFirst().currentDirection()).isEqualTo("WAIT");assertThat(report.coins().getFirst().consensusStrength()).isZero();assertThat(report.coins().getFirst().weightedSignals()).isZero();
    }

    private Prediction graded(AnalysisRun run,Coin coin,String method,Instant at){
        return gradedAt(run,coin,method,at,"103");
    }
    private Prediction gradedAt(AnalysisRun run,Coin coin,String method,Instant at,String exit){Prediction p=new Prediction(run,coin,method,at,Direction.UP,new BigDecimal("100"),Duration.ofHours(1));p.grade(new BigDecimal(exit),at.plus(Duration.ofHours(1)),new BigDecimal("0.003"));return p;}
}
