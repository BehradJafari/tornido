package io.tornado.strategies;

import io.tornado.persistence.Direction;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.BarSeries;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyDefinitionTest {
    @Test void rocAndMoneyFlowUsePositiveAsUpAndNegativeAsDown(){
        assertThat(StrategyDefinition.ROC_12.evaluate(trending(true)).direction()).isEqualTo(Direction.UP);
        assertThat(StrategyDefinition.ROC_12.evaluate(trending(false)).direction()).isEqualTo(Direction.DOWN);
        assertThat(StrategyDefinition.CHAIKIN_MONEY_FLOW.evaluate(moneyFlow(true)).direction()).isEqualTo(Direction.UP);
        assertThat(StrategyDefinition.CHAIKIN_MONEY_FLOW.evaluate(moneyFlow(false)).direction()).isEqualTo(Direction.DOWN);
    }

    @Test void middleRangeOscillatorsDoNotCreateVotes(){
        BarSeries series=sideways();
        assertThat(StrategyDefinition.RSI_14.evaluate(series).direction()).isEqualTo(Direction.NEUTRAL);
        assertThat(StrategyDefinition.STOCHASTIC_14.evaluate(series).direction()).isEqualTo(Direction.NEUTRAL);
        assertThat(StrategyDefinition.WILLIAMS_R_14.evaluate(series).direction()).isEqualTo(Direction.NEUTRAL);
    }

    @Test void meanReversionOscillatorsReverseAtTheirExtremes(){
        assertThat(StrategyDefinition.RSI_14.evaluate(trending(false)).direction()).isEqualTo(Direction.UP);
        assertThat(StrategyDefinition.RSI_14.evaluate(trending(true)).direction()).isEqualTo(Direction.DOWN);
        assertThat(StrategyDefinition.STOCHASTIC_14.evaluate(trending(false)).direction()).isEqualTo(Direction.UP);
        assertThat(StrategyDefinition.STOCHASTIC_14.evaluate(trending(true)).direction()).isEqualTo(Direction.DOWN);
        assertThat(StrategyDefinition.WILLIAMS_R_14.evaluate(trending(false)).direction()).isEqualTo(Direction.UP);
        assertThat(StrategyDefinition.WILLIAMS_R_14.evaluate(trending(true)).direction()).isEqualTo(Direction.DOWN);
    }

    @Test void weakDirectionalMovementIsNotAnAdxSignal(){
        assertThat(StrategyDefinition.ADX_14.evaluate(sideways()).direction()).isEqualTo(Direction.NEUTRAL);
    }

    @Test void strongAdxAndDmiTrendProducesDirection(){
        assertThat(StrategyDefinition.ADX_14.evaluate(trending(true)).direction()).isEqualTo(Direction.UP);
        assertThat(StrategyDefinition.ADX_14.evaluate(trending(false)).direction()).isEqualTo(Direction.DOWN);
    }

    @Test void strategyIdentityIsStableAndIndependentlyVersioned(){
        assertThat(StrategyDefinition.RSI_14.code()).isEqualTo("RSI_14");assertThat(StrategyDefinition.RSI_14.version()).isEqualTo(2);assertThat(StrategyDefinition.ROC_12.version()).isEqualTo(2);assertThat(StrategyDefinition.MACD_12_26_9.version()).isEqualTo(1);
    }

    @Test void futureCandlesCannotChangeAnEarlierProfileSignal(){
        var rising=seriesWithFuture(false);var falling=seriesWithFuture(true);
        var a=StrategyDefinition.EMA_20.evaluateAt(rising,"20",50);var b=StrategyDefinition.EMA_20.evaluateAt(falling,"20",50);
        assertThat(a.direction()).isEqualTo(b.direction());
        assertThat(rising.getBar(50).getEndTime()).isEqualTo(falling.getBar(50).getEndTime());
    }

    private BarSeries trending(boolean up){
        var series=new BaseBarSeriesBuilder().withName("trend").build();
        for(int i=0;i<50;i++)add(series,up?100+i:150-i,1000);
        return series;
    }

    private BarSeries sideways(){
        var series=new BaseBarSeriesBuilder().withName("sideways").build();
        for(int i=0;i<60;i++)add(series,i%2==0?100:101,1000);
        return series;
    }

    private BarSeries moneyFlow(boolean positive){
        var series=new BaseBarSeriesBuilder().withName("flow").build();
        for(int i=0;i<40;i++){
            double close=positive?109:101;
            double open=positive?101:109;
            series.barBuilder().timePeriod(Duration.ofMinutes(5)).endTime(Instant.parse("2026-01-01T00:00:00Z").plusSeconds((i+1)*300L)).openPrice(open).highPrice(110).lowPrice(100).closePrice(close).volume(1000).add();
        }
        return series;
    }

    private BarSeries seriesWithFuture(boolean reverseFuture){var series=new BaseBarSeriesBuilder().withName("leakage-check").build();for(int i=0;i<80;i++){double close=i<=50?100+i:reverseFuture?150-(i-50)*8:100+i;add(series,close,1000);}return series;}

    private void add(BarSeries series,double close,double volume){
        int i=series.getBarCount();
        series.barBuilder().timePeriod(Duration.ofMinutes(5)).endTime(Instant.parse("2026-01-01T00:00:00Z").plusSeconds((i+1)*300L)).openPrice(close).highPrice(close+2).lowPrice(close-2).closePrice(close).volume(volume).add();
    }
}
