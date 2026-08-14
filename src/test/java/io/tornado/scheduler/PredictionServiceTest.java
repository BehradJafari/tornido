package io.tornado.scheduler;

import io.tornado.datafetch.BinanceMarketDataClient;
import io.tornado.persistence.*;
import io.tornado.reporting.*;
import io.tornado.strategies.StrategyProfileResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PredictionServiceTest {
    @Test void profilesMakeHorizonsAndStrategiesIndependentWhileCachingSharedSeries(){var coins=mock(CoinRepository.class);var predictions=mock(PredictionRepository.class);var runs=mock(AnalysisRunRepository.class);var market=mock(BinanceMarketDataClient.class);var resolver=mock(StrategyProfileResolver.class);Coin coin=new Coin("BTC","BTCUSDT");ReflectionTestUtils.setField(coin,"id",1L);when(coins.findAllByActiveTrueOrderBySymbol()).thenReturn(List.of(coin));Instant base=Instant.parse("2026-01-01T00:00:00Z");var series=new BaseBarSeriesBuilder().withName("trend").build();for(int i=0;i<80;i++)series.barBuilder().timePeriod(Duration.ofMinutes(15)).endTime(base.plusSeconds((i+1)*900L)).openPrice(100+i).highPrice(102+i).lowPrice(99+i).closePrice(101+i).volume(1000).add();when(market.candles(eq("BTCUSDT"),anyString())).thenReturn(series);when(market.price("BTCUSDT")).thenReturn(new BigDecimal("181"));var ema1h=StrategyHorizonProfile.fallback("EMA_20",1,3600,"15m","20",base);var ema4h=StrategyHorizonProfile.fallback("EMA_20",1,14400,"1h","20",base);var sma1h=StrategyHorizonProfile.fallback("SMA_20",1,3600,"30m","20",base);var obv1h=StrategyHorizonProfile.fallback("OBV",1,3600,"15m","10",base);when(resolver.resolve(anyCollection())).thenReturn(java.util.Map.of(1L,List.of(ema1h,ema4h,sma1h,obv1h)));when(runs.save(any())).thenAnswer(i->{AnalysisRun run=i.getArgument(0);ReflectionTestUtils.setField(run,"id",1L);return run;});List<Prediction>saved=new ArrayList<>();when(predictions.saveAll(any())).thenAnswer(i->{saved.addAll(i.getArgument(0));return saved;});new PredictionService(coins,predictions,runs,market,mock(BestMixService.class),mock(MixTradeSimulationService.class),resolver).snapshot("v3");assertThat(saved).extracting(Prediction::getCandleInterval).contains("15m","30m","1h");assertThat(saved.stream().filter(p->p.getStrategyCode().equals("EMA_20")).map(Prediction::getCandleInterval)).containsExactlyInAnyOrder("15m","1h");verify(market,times(1)).candles("BTCUSDT","15m");assertThat(saved).allSatisfy(p->assertThat(p.getSignalVersion()).isEqualTo(3));}
    @Test void gradesWithHistoricalPriceAtTheTargetTimestamp(){
        var coins=mock(CoinRepository.class);var predictions=mock(PredictionRepository.class);var runs=mock(AnalysisRunRepository.class);var market=mock(BinanceMarketDataClient.class);
        Instant predictedAt=Instant.now().minus(Duration.ofMinutes(10));
        Coin coin=new Coin("BTC","BTCUSDT");
        Prediction prediction=new Prediction(null,coin,"test",predictedAt,Direction.UP,new BigDecimal("100"),Duration.ofMinutes(1));
        when(predictions.findDue(eq(Outcome.PENDING),eq(2),any())).thenReturn(List.of(prediction));
        Instant target=predictedAt.plus(Duration.ofMinutes(1));
        when(market.priceAt("BTCUSDT",target)).thenReturn(new BinanceMarketDataClient.TimedPrice(new BigDecimal("100.30"),target.plusMillis(25),25));

        int graded=new PredictionService(coins,predictions,runs,market).gradeDue();

        assertThat(graded).isEqualTo(1);
        assertThat(prediction.getOutcome()).isEqualTo(Outcome.CORRECT);
        assertThat(prediction.getGradingPriceAt()).isEqualTo(target.plusMillis(25));
        assertThat(prediction.getTargetDelayMilliseconds()).isEqualTo(25);
        verify(market).priceAt("BTCUSDT",target);
        verify(market,never()).price(anyString());
    }

    @Test void neutralObservationsCreateNoPredictionRowsAndExecutionMetadataIsStored(){
        var coins=mock(CoinRepository.class);var predictions=mock(PredictionRepository.class);var runs=mock(AnalysisRunRepository.class);var market=mock(BinanceMarketDataClient.class);Coin coin=new Coin("BTC","BTCUSDT");when(coins.findAllByActiveTrueOrderBySymbol()).thenReturn(List.of(coin));
        var series=new BaseBarSeriesBuilder().withName("sideways").build();Instant base=Instant.parse("2026-01-01T00:00:00Z");for(int i=0;i<60;i++){double close=i%2==0?100:101;series.barBuilder().timePeriod(Duration.ofMinutes(5)).endTime(base.plusSeconds((i+1)*300L)).openPrice(close).highPrice(close+2).lowPrice(close-2).closePrice(close).volume(1000).add();}
        when(market.candles(eq("BTCUSDT"),anyString())).thenReturn(series);when(market.price("BTCUSDT")).thenReturn(new BigDecimal("102"));when(runs.save(any())).thenAnswer(inv->{AnalysisRun run=inv.getArgument(0);ReflectionTestUtils.setField(run,"id",1L);return run;});List<Prediction> saved=new ArrayList<>();when(predictions.saveAll(any())).thenAnswer(inv->{saved.addAll(inv.getArgument(0));return saved;});

        new PredictionService(coins,predictions,runs,market).snapshot("test");

        assertThat(saved).isNotEmpty();assertThat(saved).noneMatch(p->p.getStrategyCode().equals("RSI_14")||p.getStrategyCode().equals("STOCHASTIC_14")||p.getStrategyCode().equals("WILLIAMS_R_14"));Prediction sample=saved.getFirst();assertThat(sample.getStrategyVersion()).isPositive();assertThat(sample.getSignalAt()).isEqualTo(series.getLastBar().getEndTime());assertThat(sample.getSignalPrice()).isEqualByComparingTo("101");assertThat(sample.getPredictedAt()).isAfterOrEqualTo(sample.getSignalAt());assertThat(sample.getSignalVersion()).isEqualTo(3);assertThat(sample.getTargetAt()).isEqualTo(sample.getPredictedAt().plusSeconds(sample.getHorizonSeconds()));assertThat(saved.stream().filter(p->p.getStrategyCode().equals("EMA_20")&&p.getHorizonSeconds()==60).findFirst().orElseThrow().getCandleInterval()).isEqualTo("1m");assertThat(saved.stream().filter(p->p.getStrategyCode().equals("EMA_20")&&p.getHorizonSeconds()==14400).findFirst().orElseThrow().getCandleInterval()).isEqualTo("1h");verify(market,times(1)).candles("BTCUSDT","5m");
    }

    @Test void rejectedHistoricalPriceIsAuditableAndRemainsPendingForRetry(){
        var coins=mock(CoinRepository.class);var predictions=mock(PredictionRepository.class);var runs=mock(AnalysisRunRepository.class);var market=mock(BinanceMarketDataClient.class);Instant at=Instant.now().minus(Duration.ofMinutes(5));Prediction prediction=new Prediction(null,new Coin("BTC","BTCUSDT"),"test",at,Direction.UP,new BigDecimal("100"),Duration.ofMinutes(1));when(predictions.findDue(eq(Outcome.PENDING),eq(2),any())).thenReturn(List.of(prediction));when(market.priceAt(eq("BTCUSDT"),any())).thenThrow(new IllegalStateException("nearest trade exceeded delay"));

        assertThat(new PredictionService(coins,predictions,runs,market).gradeDue()).isZero();assertThat(prediction.getOutcome()).isEqualTo(Outcome.PENDING);assertThat(prediction.getGradingAttempts()).isEqualTo(1);assertThat(prediction.getLastGradingError()).contains("exceeded delay");
    }

    @Test void permanentlyFailedHistoricalPriceBecomesTerminalAfterFiveAttempts(){
        var coins=mock(CoinRepository.class);var predictions=mock(PredictionRepository.class);var runs=mock(AnalysisRunRepository.class);var market=mock(BinanceMarketDataClient.class);Instant at=Instant.now().minus(Duration.ofMinutes(5));Prediction prediction=new Prediction(null,new Coin("BTC","BTCUSDT"),"test",at,Direction.UP,new BigDecimal("100"),Duration.ofMinutes(1));when(predictions.findDue(eq(Outcome.PENDING),eq(2),any())).thenReturn(List.of(prediction));when(market.priceAt(eq("BTCUSDT"),any())).thenThrow(new IllegalStateException("historical trade unavailable"));var service=new PredictionService(coins,predictions,runs,market);

        for(int attempt=0;attempt<5;attempt++)service.gradeDue();

        assertThat(prediction.getOutcome()).isEqualTo(Outcome.UNGRADABLE);assertThat(prediction.getGradingAttempts()).isEqualTo(5);assertThat(prediction.getGradedAt()).isNotNull();
    }

    @Test void resolvesAndLogsOneLookupPerSharedCoinTarget(){
        var coins=mock(CoinRepository.class);var predictions=mock(PredictionRepository.class);var runs=mock(AnalysisRunRepository.class);var market=mock(BinanceMarketDataClient.class);Instant at=Instant.now().minus(Duration.ofMinutes(5));Coin coin=new Coin("ATOM","ATOMUSDT");Prediction a=new Prediction(null,coin,"A",at,Direction.UP,new BigDecimal("10"),Duration.ofMinutes(1)),b=new Prediction(null,coin,"B",at,Direction.DOWN,new BigDecimal("10"),Duration.ofMinutes(1));when(predictions.findDue(eq(Outcome.PENDING),eq(2),any())).thenReturn(List.of(a,b));when(market.priceAt(eq("ATOMUSDT"),any())).thenThrow(new IllegalStateException("sparse market"));

        new PredictionService(coins,predictions,runs,market).gradeDue();

        verify(market,times(1)).priceAt(eq("ATOMUSDT"),any());assertThat(a.getGradingAttempts()).isEqualTo(1);assertThat(b.getGradingAttempts()).isEqualTo(1);
    }
}
