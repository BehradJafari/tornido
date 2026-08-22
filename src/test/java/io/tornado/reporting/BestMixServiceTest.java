package io.tornado.reporting;

import io.tornado.persistence.*;
import io.tornado.persistence.PredictionRepository.ReportRow;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BestMixServiceTest {
    @Test void refreshStoresExactlyOneWinnerAcrossAllMixSizesForEachTpLevel(){
        var predictions=mock(PredictionRepository.class);var mixes=mock(BestMethodMixRepository.class);var coins=mock(CoinRepository.class);var settings=mock(AppSettingsRepository.class);
        Coin coin=new Coin("BTC","BTCUSDT");org.springframework.test.util.ReflectionTestUtils.setField(coin,"id",1L);
        AppSettings configuration=new AppSettings(900,900);TpSlLevels rebuiltLevels=new TpSlLevels(new BigDecimal(".40"),new BigDecimal(".80"),new BigDecimal("1.50"),new BigDecimal(".30"),new BigDecimal(".50"),new BigDecimal("1.00"));configuration.updateMixSignals(1,rebuiltLevels,true);
        when(coins.findById(1L)).thenReturn(Optional.of(coin));when(settings.findById(1)).thenReturn(Optional.of(configuration));
        List<ReportRow>rows=new ArrayList<>();for(long run=1;run<=6;run++)for(int method=0;method<10;method++)rows.add(row(run,"M"+method,Direction.UP,"101"));
        when(predictions.findGradedReportRows(1L,900L)).thenReturn(rows);
        new BestMixService(predictions,mixes,coins,settings).refresh(1L,900);
        verify(mixes).deleteSlice(1L,900,3);
        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<Iterable<BestMethodMix>>capture=org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(mixes).saveAll(capture.capture());List<BestMethodMix>saved=new ArrayList<>();capture.getValue().forEach(saved::add);
        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(BestMethodMix::getTpLevel).containsExactly(1,2,3);
        assertThat(saved).allSatisfy(row -> {
            assertThat(row.getRank()).isEqualTo(1);
            assertThat(row.getMixSize()).isEqualTo(2);
            assertThat(row.getTargetPercent()).isEqualByComparingTo(rebuiltLevels.tp(row.getTpLevel()));
        });
        verify(predictions,times(1)).findGradedReportRows(1L,900L);
    }
    @Test void calculatesEligibleMixesForEverySizeTwoThroughEight(){BestMixService service=service();List<ReportRow>rows=new ArrayList<>();for(long run=1;run<=5;run++)for(int method=0;method<8;method++)rows.add(row(run,"M"+method,Direction.UP,"101"));var result=service.calculate(rows,5);assertThat(result).extracting(BestMixService.Candidate::size).contains(2,3,4,5,6,7,8);assertThat(result).allSatisfy(x->assertThat(x.samples()).isEqualTo(5));assertThat(service.calculate(rows,6)).isEmpty();}
    @Test void wilsonScoreOrdersCandidatesWithoutUsingRawAccuracyAlone(){BestMixService service=service();List<ReportRow>rows=new ArrayList<>();for(long run=1;run<=20;run++){rows.add(row(run,"A",Direction.UP,run<=12?"101":"99"));rows.add(row(run,"B",Direction.UP,run<=12?"101":"99"));rows.add(row(run,"C",Direction.UP,run<=11?"101":"99"));rows.add(row(run,"D",Direction.UP,run<=11?"101":"99"));}var pairs=service.calculate(rows,5).stream().filter(x->x.size()==2).toList();assertThat(pairs).isNotEmpty();assertThat(pairs).isSortedAccordingTo(Comparator.comparingDouble(BestMixService.Candidate::wilson).reversed().thenComparing(Comparator.comparingLong(BestMixService.Candidate::samples).reversed()));}
    @Test void differentMixesCanLeadTp1AndTp3FromOneCombinationPass(){BestMixService service=service();List<ReportRow>rows=new ArrayList<>();for(long run=1;run<=10;run++){rows.add(row(run,"A1",Direction.UP,"100.4"));rows.add(row(run,"A2",Direction.UP,"100.4"));}for(long run=11;run<=14;run++){rows.add(row(run,"B1",Direction.UP,"101.2"));rows.add(row(run,"B2",Direction.UP,"101.2"));}var all=service.calculateAll(rows,1,TpSlLevels.defaults());assertThat(all.get(1).stream().filter(x->x.size()==2).findFirst().orElseThrow().codes()).containsExactly("A1","A2");assertThat(all.get(3).stream().filter(x->x.size()==2).findFirst().orElseThrow().codes()).containsExactly("B1","B2");}

    @Test void deterministicRankingUsesSamplesThenSmallerSizeThenLexicalIdentity() {
        var fewerSamples = candidate(3, List.of("Z", "Y", "X"), 100, 70, 70, .60);
        var moreSamples = candidate(5, List.of("M", "N", "O", "P", "Q"), 200, 120, 120, .60);
        assertThat(List.of(fewerSamples, moreSamples).stream().sorted(BestMixService.STRONGEST_FIRST).findFirst())
                .contains(moreSamples);

        var larger = candidate(4, List.of("A", "B", "C", "D"), 100, 60, 60, .60);
        var lexicalLater = candidate(2, List.of("C", "D"), 100, 60, 60, .60);
        var lexicalFirst = candidate(2, List.of("A", "B"), 100, 60, 60, .60);
        assertThat(List.of(larger, lexicalLater, lexicalFirst).stream()
                .sorted(BestMixService.STRONGEST_FIRST).toList())
                .containsExactly(lexicalFirst, lexicalLater, larger);
    }

    private BestMixService.Candidate candidate(int size,List<String> codes,long samples,long hits,long directional,double wilson){return new BestMixService.Candidate(size,codes,Collections.nCopies(size,1),codes,samples,hits,directional,wilson);}
    private BestMixService service(){return new BestMixService(mock(PredictionRepository.class),mock(BestMethodMixRepository.class),mock(CoinRepository.class),mock(AppSettingsRepository.class));}
    private ReportRow row(long run,String method,Direction direction,String exit){return new R(run,1L,"BTC","BTCUSDT",method,method,1,Instant.EPOCH.plusSeconds(run),direction,new BigDecimal("100"),900,new BigDecimal(exit),Outcome.CORRECT);}
    record R(Long runId,Long coinId,String coinSymbol,String coinPair,String methodName,String strategyCode,int strategyVersion,Instant predictedAt,Direction predictedDirection,BigDecimal priceAtPrediction,long horizonSeconds,BigDecimal priceAtGrading,Outcome outcome)implements ReportRow{public Long getRunId(){return runId;}public Long getCoinId(){return coinId;}public String getCoinSymbol(){return coinSymbol;}public String getCoinPair(){return coinPair;}public String getMethodName(){return methodName;}public String getStrategyCode(){return strategyCode;}public int getStrategyVersion(){return strategyVersion;}public Instant getPredictedAt(){return predictedAt;}public Direction getPredictedDirection(){return predictedDirection;}public BigDecimal getPriceAtPrediction(){return priceAtPrediction;}public long getHorizonSeconds(){return horizonSeconds;}public BigDecimal getPriceAtGrading(){return priceAtGrading;}public Outcome getOutcome(){return outcome;}}
}
