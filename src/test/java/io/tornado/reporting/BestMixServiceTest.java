package io.tornado.reporting;

import io.tornado.persistence.*;
import io.tornado.persistence.PredictionRepository.ReportRow;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BestMixServiceTest {
    @Test void refreshStoresExactlyOneEligibleTp1WinnerAcrossAllMixSizes(){
        Fixture f=new Fixture(1,"80");List<ReportRow> rows=new ArrayList<>();
        for(long run=1;run<=6;run++)for(int method=0;method<8;method++)rows.add(row(run,"M"+method,Direction.UP,"101"));
        when(f.predictions.findGradedReportRows(1L,3600L)).thenReturn(rows);

        f.service.refresh(1L,3600);

        verify(f.mixes).deleteSlice(1L,3600,3);verify(f.mixes).flush();
        var capture=org.mockito.ArgumentCaptor.forClass(BestMethodMix.class);verify(f.mixes).save(capture.capture());
        BestMethodMix saved=capture.getValue();assertThat(saved.getTpLevel()).isEqualTo(1);assertThat(saved.getTargetPercent()).isEqualByComparingTo(".30");assertThat(saved.getRank()).isEqualTo(1);assertThat(saved.getMixSize()).isEqualTo(2);assertThat(saved.getTargetHitRate()).isEqualTo(100);
    }

    @Test void candidatesBelowConfiguredRawWinRateAreDeletedAndNeverPersisted(){
        Fixture f=new Fixture(5,"87");List<ReportRow> rows=new ArrayList<>();
        for(long run=1;run<=10;run++)for(String method:List.of("A","B"))rows.add(row(run,method,Direction.UP,run<=5?"101":"99"));
        when(f.predictions.findGradedReportRows(1L,3600L)).thenReturn(rows);

        f.service.refresh(1L,3600);

        verify(f.mixes,never()).save(any());verify(f.mixes).deleteSlice(1L,3600,3);
    }

    @Test void perfectCandidateBelowMinimumSamplesIsNeverPersisted(){
        Fixture f=new Fixture(50,"87");when(f.predictions.findGradedReportRows(1L,3600L)).thenReturn(List.of(row(1,"A",Direction.UP,"101"),row(1,"B",Direction.UP,"101")));
        f.service.refresh(1L,3600);
        verify(f.mixes,never()).save(any());
    }

    @Test void onlyLiveHorizonsParticipate(){
        Fixture f=new Fixture(1,"0");f.service.refresh(1L,900);
        verify(f.mixes).deleteSlice(1L,900,3);verifyNoInteractions(f.predictions);verify(f.mixes,never()).save(any());
    }

    @Test void affectedSliceRefreshDoesNotRebuildUnrelatedCoinsOrShortHorizons(){
        Fixture f=new Fixture(50,"87");Coin eth=new Coin("ETH","ETHUSDT");ReflectionTestUtils.setField(eth,"id",2L);when(f.coins.findById(2L)).thenReturn(Optional.of(eth));when(f.predictions.findGradedReportRows(2L,14400L)).thenReturn(List.of());
        f.service.refresh(Set.of(new BestMixService.Slice(1L,900L),new BestMixService.Slice(2L,14400L)));
        verify(f.mixes,never()).deleteSlice(1L,900L,3);verify(f.mixes).deleteSlice(2L,14400L,3);verify(f.predictions).findGradedReportRows(2L,14400L);verify(f.predictions,never()).findGradedReportRows(1L,3600L);
    }

    @Test void calculatesCandidatesForEverySizeTwoThroughEight(){
        BestMixService service=service();List<ReportRow> rows=new ArrayList<>();for(long run=1;run<=5;run++)for(int method=0;method<8;method++)rows.add(row(run,"M"+method,Direction.UP,"101"));
        assertThat(service.calculate(rows,5)).extracting(BestMixService.Candidate::size).contains(2,3,4,5,6,7,8);
        assertThat(service.calculate(rows,6)).isEmpty();
    }

    @Test void deterministicOrderIsWilsonThenRawRateThenSamplesDirectionalSizeAndIdentity(){
        var size2=candidate(2,List.of("A","B"),100,90,80,.84);var size3=candidate(3,List.of("C","D","E"),200,178,180,.85);var size7=candidate(7,List.of("F","G","H","I","J","K","L"),500,440,450,.855);
        assertThat(List.of(size2,size3,size7).stream().sorted(BestMixService.STRONGEST_FIRST).findFirst()).contains(size7);
        var higherRaw=candidate(2,List.of("A","B"),100,92,80,.85);var moreSamples=candidate(2,List.of("C","D"),300,270,250,.85);
        assertThat(List.of(moreSamples,higherRaw).stream().sorted(BestMixService.STRONGEST_FIRST).findFirst()).contains(higherRaw);
    }

    private BestMixService.Candidate candidate(int size,List<String> codes,long samples,long hits,long directional,double wilson){return new BestMixService.Candidate(size,codes,Collections.nCopies(size,1),codes,samples,hits,directional,wilson);}
    private BestMixService service(){return new BestMixService(mock(PredictionRepository.class),mock(BestMethodMixRepository.class),mock(CoinRepository.class),mock(AppSettingsRepository.class));}
    private static ReportRow row(long run,String method,Direction direction,String exit){return new R(run,1L,"BTC","BTCUSDT",method,method,1,Instant.EPOCH.plusSeconds(run),direction,new BigDecimal("100"),3600,new BigDecimal(exit),Outcome.CORRECT);}

    static class Fixture{
        final PredictionRepository predictions=mock(PredictionRepository.class);final BestMethodMixRepository mixes=mock(BestMethodMixRepository.class);final CoinRepository coins=mock(CoinRepository.class);final AppSettingsRepository settings=mock(AppSettingsRepository.class);final BestMixService service;
        Fixture(int minimum,String rate){Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);AppSettings configuration=new AppSettings(900,900);configuration.updateMixSignals(minimum,configuration.getTpSlLevels(),new BigDecimal(rate),true);when(coins.findById(1L)).thenReturn(Optional.of(coin));when(settings.findById(1)).thenReturn(Optional.of(configuration));when(mixes.save(any())).thenAnswer(i->i.getArgument(0));service=new BestMixService(predictions,mixes,coins,settings);}
    }
    record R(Long runId,Long coinId,String coinSymbol,String coinPair,String methodName,String strategyCode,int strategyVersion,Instant predictedAt,Direction predictedDirection,BigDecimal priceAtPrediction,long horizonSeconds,BigDecimal priceAtGrading,Outcome outcome)implements ReportRow{public Long getRunId(){return runId;}public Long getCoinId(){return coinId;}public String getCoinSymbol(){return coinSymbol;}public String getCoinPair(){return coinPair;}public String getMethodName(){return methodName;}public String getStrategyCode(){return strategyCode;}public int getStrategyVersion(){return strategyVersion;}public Instant getPredictedAt(){return predictedAt;}public Direction getPredictedDirection(){return predictedDirection;}public BigDecimal getPriceAtPrediction(){return priceAtPrediction;}public long getHorizonSeconds(){return horizonSeconds;}public BigDecimal getPriceAtGrading(){return priceAtGrading;}public Outcome getOutcome(){return outcome;}}
}
