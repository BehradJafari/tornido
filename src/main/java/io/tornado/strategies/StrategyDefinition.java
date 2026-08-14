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
import java.util.*;
import java.util.function.Function;

public enum StrategyDefinition {
    SMA_20("SMA(20)", s->{var c=new ClosePriceIndicator(s);return pair(new CrossedUpIndicatorRule(c,new SMAIndicator(c,20)),new CrossedDownIndicatorRule(c,new SMAIndicator(c,20)),c,new SMAIndicator(c,20));}),
    EMA_20("EMA(20)", s->{var c=new ClosePriceIndicator(s);return pair(new CrossedUpIndicatorRule(c,new EMAIndicator(c,20)),new CrossedDownIndicatorRule(c,new EMAIndicator(c,20)),c,new EMAIndicator(c,20));}),
    WMA_20("WMA(20)", s->{var c=new ClosePriceIndicator(s);return pair(new CrossedUpIndicatorRule(c,new WMAIndicator(c,20)),new CrossedDownIndicatorRule(c,new WMAIndicator(c,20)),c,new WMAIndicator(c,20));}),
    RSI_14("RSI Mean Reversion(14)",2,s->{var i=new RSIIndicator(new ClosePriceIndicator(s),14);return thresholds(i,30,70);}),
    MACD_12_26_9("MACD(12,26,9)",s->{var m=new MACDIndicator(new ClosePriceIndicator(s),12,26);var sig=new EMAIndicator(m,9);return pair(new CrossedUpIndicatorRule(m,sig),new CrossedDownIndicatorRule(m,sig),m,sig);}),
    STOCHASTIC_14("Stochastic Mean Reversion(14)",2,s->{var k=new StochasticOscillatorKIndicator(s,14);return thresholds(k,20,80);}),
    CCI_20("CCI Mean Reversion(20)",2,s->{var i=new CCIIndicator(s,20);return thresholds(i,-100,100);}),
    ADX_14("ADX/DMI(14)",2,s->{var plus=new PlusDIIndicator(s,14);var minus=new MinusDIIndicator(s,14);var adx=new ADXIndicator(s,14);var strength=new ConstantIndicator<>(s,s.numFactory().numOf(25));return neutral(new AndRule(new OverIndicatorRule(adx,strength),new OverIndicatorRule(plus,minus)),new AndRule(new OverIndicatorRule(adx,strength),new OverIndicatorRule(minus,plus)));}),
    WILLIAMS_R_14("Williams %R Mean Reversion(14)",2,s->{var i=new WilliamsRIndicator(s,14);return thresholds(i,-80,-20);}),
    BOLLINGER_20("Bollinger Mean Reversion(20,2)",s->{var c=new ClosePriceIndicator(s);var mid=new BollingerBandsMiddleIndicator(new SMAIndicator(c,20));var dev=new StandardDeviationIndicator(c,20);var up=new BollingerBandsUpperIndicator(mid,dev,s.numFactory().numOf(2));var low=new BollingerBandsLowerIndicator(mid,dev,s.numFactory().numOf(2));return pair(new UnderIndicatorRule(c,low),new OverIndicatorRule(c,up),c,mid);}),
    ROC_12("ROC Momentum(12)",2,s->zeroMomentum(new ROCIndicator(new ClosePriceIndicator(s),12))),
    CHAIKIN_MONEY_FLOW("Chaikin Money Flow Momentum(20)",2,s->zeroMomentum(new ChaikinMoneyFlowIndicator(s,20))),
    OBV("OBV trend",s->{var i=new OnBalanceVolumeIndicator(s);var avg=new SMAIndicator(i,10);return pair(new CrossedUpIndicatorRule(i,avg),new CrossedDownIndicatorRule(i,avg),i,avg);}),
    ICHIMOKU("Ichimoku Tenkan/Kijun",s->{var a=new IchimokuTenkanSenIndicator(s);var b=new IchimokuKijunSenIndicator(s);return pair(new CrossedUpIndicatorRule(a,b),new CrossedDownIndicatorRule(a,b),a,b);}),
    AROON_25("Aroon(25)",s->{var a=new AroonUpIndicator(s,25);var b=new AroonDownIndicator(s,25);return pair(new CrossedUpIndicatorRule(a,b),new CrossedDownIndicatorRule(a,b),a,b);}),
    PARABOLIC_SAR("Parabolic SAR",s->{var c=new ClosePriceIndicator(s);var p=new ParabolicSarIndicator(s);return pair(new CrossedUpIndicatorRule(c,p),new CrossedDownIndicatorRule(c,p),c,p);});

    private final String label; private final int version; private final Function<BarSeries,Rules> factory;
    StrategyDefinition(String label,Function<BarSeries,Rules> factory){this(label,1,factory);}
    StrategyDefinition(String label,int version,Function<BarSeries,Rules> factory){this.label=label;this.version=version;this.factory=factory;}
    public String label(){return label;}
    public String code(){return name();}
    public int version(){return version;}
    public StrategySignal evaluate(BarSeries series){return signal(series,factory.apply(series),series.getEndIndex());}
    public StrategySignal evaluate(BarSeries series,String parameterKey){return evaluateAt(series,parameterKey,series.getEndIndex());}
    public StrategySignal evaluateAt(BarSeries series,String parameterKey,int index){if(parameterKey==null||parameterKey.isBlank()||parameterKey.equals(defaultParameterKey()))return signal(series,factory.apply(series),index);Rules rules=switch(this){
        case SMA_20->movingAverage(series,parameterKey,"SMA");case EMA_20->movingAverage(series,parameterKey,"EMA");case WMA_20->movingAverage(series,parameterKey,"WMA");
        case RSI_14->thresholds(new RSIIndicator(new ClosePriceIndicator(series),period(parameterKey)),30,70);
        case MACD_12_26_9->{int[]p=tuple(parameterKey,3);var m=new MACDIndicator(new ClosePriceIndicator(series),p[0],p[1]);var sig=new EMAIndicator(m,p[2]);yield pair(new CrossedUpIndicatorRule(m,sig),new CrossedDownIndicatorRule(m,sig),m,sig);}
        case STOCHASTIC_14->thresholds(new StochasticOscillatorKIndicator(series,period(parameterKey)),20,80);
        case CCI_20->thresholds(new CCIIndicator(series,period(parameterKey)),-100,100);
        case ADX_14->{int p=period(parameterKey);var plus=new PlusDIIndicator(series,p);var minus=new MinusDIIndicator(series,p);var adx=new ADXIndicator(series,p);var strength=new ConstantIndicator<>(series,series.numFactory().numOf(25));yield neutral(new AndRule(new OverIndicatorRule(adx,strength),new OverIndicatorRule(plus,minus)),new AndRule(new OverIndicatorRule(adx,strength),new OverIndicatorRule(minus,plus)));}
        case WILLIAMS_R_14->thresholds(new WilliamsRIndicator(series,period(parameterKey)),-80,-20);
        case BOLLINGER_20->{int[]p=tuple(parameterKey,2);var c=new ClosePriceIndicator(series);var mid=new BollingerBandsMiddleIndicator(new SMAIndicator(c,p[0]));var dev=new StandardDeviationIndicator(c,p[0]);var up=new BollingerBandsUpperIndicator(mid,dev,series.numFactory().numOf(p[1]));var low=new BollingerBandsLowerIndicator(mid,dev,series.numFactory().numOf(p[1]));yield pair(new UnderIndicatorRule(c,low),new OverIndicatorRule(c,up),c,mid);}
        case ROC_12->zeroMomentum(new ROCIndicator(new ClosePriceIndicator(series),period(parameterKey)));
        case CHAIKIN_MONEY_FLOW->zeroMomentum(new ChaikinMoneyFlowIndicator(series,period(parameterKey)));
        case OBV->{var i=new OnBalanceVolumeIndicator(series);var avg=new SMAIndicator(i,period(parameterKey));yield pair(new CrossedUpIndicatorRule(i,avg),new CrossedDownIndicatorRule(i,avg),i,avg);}
        case AROON_25->{int p=period(parameterKey);var a=new AroonUpIndicator(series,p);var b=new AroonDownIndicator(series,p);yield pair(new CrossedUpIndicatorRule(a,b),new CrossedDownIndicatorRule(a,b),a,b);}
        case ICHIMOKU,PARABOLIC_SAR->factory.apply(series);
    };return signal(series,rules,index);}
    public String defaultParameterKey(){return switch(this){case SMA_20,EMA_20,WMA_20,CCI_20,CHAIKIN_MONEY_FLOW->"20";case BOLLINGER_20->"20,2";case RSI_14,STOCHASTIC_14,ADX_14,WILLIAMS_R_14->"14";case MACD_12_26_9->"12,26,9";case ROC_12->"12";case OBV->"10";case ICHIMOKU,PARABOLIC_SAR->"default";case AROON_25->"25";};}
    public List<String> parameterKeys(){return switch(this){case SMA_20,EMA_20,WMA_20->List.of("10","20","50");case RSI_14->List.of("7","14","21");case MACD_12_26_9->List.of("8,21,5","12,26,9");case STOCHASTIC_14->List.of("7","14","21");case CCI_20->List.of("14","20","30");case ADX_14->List.of("10","14","20");case WILLIAMS_R_14->List.of("7","14","21");case BOLLINGER_20->List.of("14,2","20,2");case ROC_12->List.of("6","12","24");case CHAIKIN_MONEY_FLOW->List.of("10","20","30");case OBV->List.of("5","10","20");case ICHIMOKU,PARABOLIC_SAR->List.of("default");case AROON_25->List.of("14","25","50");};}
    public Family family(){return switch(this){case RSI_14,STOCHASTIC_14,CCI_20,WILLIAMS_R_14,BOLLINGER_20->Family.MEAN_REVERSION;default->Family.TREND_MOMENTUM;};}
    private StrategySignal signal(BarSeries series,Rules r,int i){if(i<series.getBeginIndex()||i>series.getEndIndex())throw new IllegalArgumentException("Signal index is outside the available candle series");Direction d=r.up().isSatisfied(i)?Direction.UP:r.down().isSatisfied(i)?Direction.DOWN:r.fallback()==Fallback.NEUTRAL?Direction.NEUTRAL:r.left().getValue(i).isGreaterThanOrEqual(r.right().getValue(i))?Direction.UP:Direction.DOWN;return new StrategySignal(label,d);}
    private Rules movingAverage(BarSeries series,String key,String type){int p=period(key);var c=new ClosePriceIndicator(series);org.ta4j.core.Indicator<Num>avg=switch(type){case"SMA"->new SMAIndicator(c,p);case"EMA"->new EMAIndicator(c,p);default->new WMAIndicator(c,p);};return pair(new CrossedUpIndicatorRule(c,avg),new CrossedDownIndicatorRule(c,avg),c,avg);}
    private int period(String value){return Integer.parseInt(value);}
    private int[]tuple(String value,int expected){int[]r=Arrays.stream(value.split(",")).mapToInt(Integer::parseInt).toArray();if(r.length!=expected)throw new IllegalArgumentException("Invalid parameters for "+code()+": "+value);return r;}
    private static Rules thresholds(org.ta4j.core.Indicator<Num> i,Number low,Number high){var l=new ConstantIndicator<>(i.getBarSeries(),i.getBarSeries().numFactory().numOf(low));var h=new ConstantIndicator<>(i.getBarSeries(),i.getBarSeries().numFactory().numOf(high));return neutral(new UnderIndicatorRule(i,l),new OverIndicatorRule(i,h));}
    private static Rules zeroMomentum(org.ta4j.core.Indicator<Num> i){var zero=new ConstantIndicator<>(i.getBarSeries(),i.getBarSeries().numFactory().numOf(0));return neutral(new OverIndicatorRule(i,zero),new UnderIndicatorRule(i,zero));}
    private static Rules neutral(org.ta4j.core.Rule up,org.ta4j.core.Rule down){return new Rules(up,down,null,null,Fallback.NEUTRAL);}
    private static Rules pair(org.ta4j.core.Rule up,org.ta4j.core.Rule down,org.ta4j.core.Indicator<Num> left,org.ta4j.core.Indicator<Num> right){return new Rules(up,down,left,right,Fallback.RELATIVE);}
    private enum Fallback { RELATIVE, NEUTRAL }
    public enum Family { TREND_MOMENTUM, MEAN_REVERSION }
    private record Rules(org.ta4j.core.Rule up,org.ta4j.core.Rule down,org.ta4j.core.Indicator<Num> left,org.ta4j.core.Indicator<Num> right,Fallback fallback){}
}
