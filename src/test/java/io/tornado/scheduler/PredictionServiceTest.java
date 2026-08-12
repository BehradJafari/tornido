package io.tornado.scheduler;

import io.tornado.datafetch.BinanceMarketDataClient;
import io.tornado.persistence.*;
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
    @Test void gradesWithHistoricalPriceAtTheTargetTimestamp(){
        var coins=mock(CoinRepository.class);var predictions=mock(PredictionRepository.class);var runs=mock(AnalysisRunRepository.class);var market=mock(BinanceMarketDataClient.class);
        Instant predictedAt=Instant.now().minus(Duration.ofMinutes(10));
        Coin coin=new Coin("BTC","BTCUSDT");
        Prediction prediction=new Prediction(null,coin,"test",predictedAt,Direction.UP,new BigDecimal("100"),Duration.ofMinutes(1));
        when(predictions.findByOutcomeAndSignalVersionOrderByPredictedAtAsc(Outcome.PENDING,2)).thenReturn(List.of(prediction));
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
        when(market.candles("BTCUSDT")).thenReturn(series);when(market.price("BTCUSDT")).thenReturn(new BigDecimal("102"));when(market.candleInterval()).thenReturn("5m");when(runs.save(any())).thenAnswer(inv->{AnalysisRun run=inv.getArgument(0);ReflectionTestUtils.setField(run,"id",1L);return run;});List<Prediction> saved=new ArrayList<>();when(predictions.save(any())).thenAnswer(inv->{Prediction p=inv.getArgument(0);saved.add(p);return p;});

        new PredictionService(coins,predictions,runs,market).snapshot("test");

        assertThat(saved).isNotEmpty();assertThat(saved).noneMatch(p->p.getStrategyCode().equals("RSI_14")||p.getStrategyCode().equals("STOCHASTIC_14")||p.getStrategyCode().equals("WILLIAMS_R_14"));Prediction sample=saved.getFirst();assertThat(sample.getStrategyVersion()).isPositive();assertThat(sample.getSignalAt()).isEqualTo(series.getLastBar().getEndTime());assertThat(sample.getSignalPrice()).isEqualByComparingTo("101");assertThat(sample.getPredictedAt()).isAfter(sample.getSignalAt());assertThat(sample.getCandleInterval()).isEqualTo("5m");assertThat(sample.getTargetAt()).isEqualTo(sample.getPredictedAt().plusSeconds(sample.getHorizonSeconds()));
    }

    @Test void rejectedHistoricalPriceIsAuditableAndRemainsPendingForRetry(){
        var coins=mock(CoinRepository.class);var predictions=mock(PredictionRepository.class);var runs=mock(AnalysisRunRepository.class);var market=mock(BinanceMarketDataClient.class);Instant at=Instant.now().minus(Duration.ofMinutes(5));Prediction prediction=new Prediction(null,new Coin("BTC","BTCUSDT"),"test",at,Direction.UP,new BigDecimal("100"),Duration.ofMinutes(1));when(predictions.findByOutcomeAndSignalVersionOrderByPredictedAtAsc(Outcome.PENDING,2)).thenReturn(List.of(prediction));when(market.priceAt(eq("BTCUSDT"),any())).thenThrow(new IllegalStateException("nearest trade exceeded delay"));

        assertThat(new PredictionService(coins,predictions,runs,market).gradeDue()).isZero();assertThat(prediction.getOutcome()).isEqualTo(Outcome.PENDING);assertThat(prediction.getGradingAttempts()).isEqualTo(1);assertThat(prediction.getLastGradingError()).contains("exceeded delay");
    }

    @Test void permanentlyFailedHistoricalPriceBecomesTerminalAfterFiveAttempts(){
        var coins=mock(CoinRepository.class);var predictions=mock(PredictionRepository.class);var runs=mock(AnalysisRunRepository.class);var market=mock(BinanceMarketDataClient.class);Instant at=Instant.now().minus(Duration.ofMinutes(5));Prediction prediction=new Prediction(null,new Coin("BTC","BTCUSDT"),"test",at,Direction.UP,new BigDecimal("100"),Duration.ofMinutes(1));when(predictions.findByOutcomeAndSignalVersionOrderByPredictedAtAsc(Outcome.PENDING,2)).thenReturn(List.of(prediction));when(market.priceAt(eq("BTCUSDT"),any())).thenThrow(new IllegalStateException("historical trade unavailable"));var service=new PredictionService(coins,predictions,runs,market);

        for(int attempt=0;attempt<5;attempt++)service.gradeDue();

        assertThat(prediction.getOutcome()).isEqualTo(Outcome.UNGRADABLE);assertThat(prediction.getGradingAttempts()).isEqualTo(5);assertThat(prediction.getGradedAt()).isNotNull();
    }
}
