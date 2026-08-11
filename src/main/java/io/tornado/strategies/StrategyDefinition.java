package io.tornado.strategies;

import io.tornado.persistence.Direction;
import org.ta4j.core.BarSeries;
import org.ta4j.core.rules.*;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.*;
import org.ta4j.core.indicators.aroon.*;
import org.ta4j.core.indicators.averages.*;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.bollinger.*;
import org.ta4j.core.indicators.ichimoku.*;
import org.ta4j.core.indicators.volume.*;
import org.ta4j.core.num.Num;
import java.util.function.Function;

public enum StrategyDefinition {
    SMA_20("SMA(20)", s->{var c=new ClosePriceIndicator(s);return pair(new CrossedUpIndicatorRule(c,new SMAIndicator(c,20)),new CrossedDownIndicatorRule(c,new SMAIndicator(c,20)),c,new SMAIndicator(c,20));}),
    EMA_20("EMA(20)", s->{var c=new ClosePriceIndicator(s);return pair(new CrossedUpIndicatorRule(c,new EMAIndicator(c,20)),new CrossedDownIndicatorRule(c,new EMAIndicator(c,20)),c,new EMAIndicator(c,20));}),
    WMA_20("WMA(20)", s->{var c=new ClosePriceIndicator(s);return pair(new CrossedUpIndicatorRule(c,new WMAIndicator(c,20)),new CrossedDownIndicatorRule(c,new WMAIndicator(c,20)),c,new WMAIndicator(c,20));}),
    RSI_14("RSI(14)",s->{var i=new RSIIndicator(new ClosePriceIndicator(s),14);return thresholds(i,30,70);}),
    MACD_12_26_9("MACD(12,26,9)",s->{var m=new MACDIndicator(new ClosePriceIndicator(s),12,26);var sig=new EMAIndicator(m,9);return pair(new CrossedUpIndicatorRule(m,sig),new CrossedDownIndicatorRule(m,sig),m,sig);}),
    STOCHASTIC_14("Stochastic(14)",s->{var k=new StochasticOscillatorKIndicator(s,14);return thresholds(k,20,80);}),
    CCI_20("CCI(20)",s->{var i=new CCIIndicator(s,20);return thresholds(i,-100,100);}),
    ADX_14("ADX/DMI(14)",s->{var plus=new PlusDIIndicator(s,14);var minus=new MinusDIIndicator(s,14);return pair(new CrossedUpIndicatorRule(plus,minus),new CrossedDownIndicatorRule(plus,minus),plus,minus);}),
    WILLIAMS_R_14("Williams %R(14)",s->{var i=new WilliamsRIndicator(s,14);return thresholds(i,-80,-20);}),
    BOLLINGER_20("Bollinger Bands(20,2)",s->{var c=new ClosePriceIndicator(s);var mid=new BollingerBandsMiddleIndicator(new SMAIndicator(c,20));var dev=new StandardDeviationIndicator(c,20);var up=new BollingerBandsUpperIndicator(mid,dev,s.numFactory().numOf(2));var low=new BollingerBandsLowerIndicator(mid,dev,s.numFactory().numOf(2));return new Rules(new UnderIndicatorRule(c,low),new OverIndicatorRule(c,up),c,mid);}),
    ROC_12("ROC(12)",s->{var i=new ROCIndicator(new ClosePriceIndicator(s),12);return thresholds(i,0,0);}),
    CHAIKIN_MONEY_FLOW("Chaikin Money Flow(20)",s->{var i=new ChaikinMoneyFlowIndicator(s,20);return thresholds(i,0,0);}),
    OBV("OBV trend",s->{var i=new OnBalanceVolumeIndicator(s);var avg=new SMAIndicator(i,10);return pair(new CrossedUpIndicatorRule(i,avg),new CrossedDownIndicatorRule(i,avg),i,avg);}),
    ICHIMOKU("Ichimoku Tenkan/Kijun",s->{var a=new IchimokuTenkanSenIndicator(s);var b=new IchimokuKijunSenIndicator(s);return pair(new CrossedUpIndicatorRule(a,b),new CrossedDownIndicatorRule(a,b),a,b);}),
    AROON_25("Aroon(25)",s->{var a=new AroonUpIndicator(s,25);var b=new AroonDownIndicator(s,25);return pair(new CrossedUpIndicatorRule(a,b),new CrossedDownIndicatorRule(a,b),a,b);}),
    PARABOLIC_SAR("Parabolic SAR",s->{var c=new ClosePriceIndicator(s);var p=new ParabolicSarIndicator(s);return pair(new CrossedUpIndicatorRule(c,p),new CrossedDownIndicatorRule(c,p),c,p);});

    private final String label; private final Function<BarSeries,Rules> factory;
    StrategyDefinition(String label,Function<BarSeries,Rules> factory){this.label=label;this.factory=factory;}
    public String label(){return label;}
    public StrategySignal evaluate(BarSeries series){Rules r=factory.apply(series);int i=series.getEndIndex();Direction d=r.up().isSatisfied(i)?Direction.UP:r.down().isSatisfied(i)?Direction.DOWN:r.left().getValue(i).isGreaterThanOrEqual(r.right().getValue(i))?Direction.UP:Direction.DOWN;return new StrategySignal(label,d);}
    private static Rules thresholds(org.ta4j.core.Indicator<Num> i,Number low,Number high){var l=new ConstantIndicator<>(i.getBarSeries(),i.getBarSeries().numFactory().numOf(low));var h=new ConstantIndicator<>(i.getBarSeries(),i.getBarSeries().numFactory().numOf(high));return new Rules(new UnderIndicatorRule(i,l),new OverIndicatorRule(i,h),i,new ConstantIndicator<>(i.getBarSeries(),i.getBarSeries().numFactory().numOf(0)));}
    private static Rules pair(org.ta4j.core.Rule up,org.ta4j.core.Rule down,org.ta4j.core.Indicator<Num> left,org.ta4j.core.Indicator<Num> right){return new Rules(up,down,left,right);}
    private record Rules(org.ta4j.core.Rule up,org.ta4j.core.Rule down,org.ta4j.core.Indicator<Num> left,org.ta4j.core.Indicator<Num> right){}
}
