package io.tornado.api;

import io.tornado.persistence.*;
import io.tornado.persistence.PredictionRepository.ReportRow;
import io.tornado.datafetch.BinanceMarketDataClient;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ReportService {
    private static final java.math.BigDecimal DEFAULT_TARGET=new java.math.BigDecimal("0.003");
    private final PredictionRepository predictions;
    private final AnalysisRunRepository runs;
    private final BinanceMarketDataClient market;
    private final AppSettingsRepository settings;
    @org.springframework.beans.factory.annotation.Autowired public ReportService(PredictionRepository predictions,AnalysisRunRepository runs,BinanceMarketDataClient market,AppSettingsRepository settings){this.predictions=predictions;this.runs=runs;this.market=market;this.settings=settings;}
    public ReportService(PredictionRepository predictions,AnalysisRunRepository runs,BinanceMarketDataClient market){this(predictions,runs,market,null);}

    public List<CoinReport> coinReports(int minSamples,long horizon){return coinReports(minSamples,horizon,Prediction.CURRENT_SIGNAL_VERSION);}
    public List<CoinReport> coinReports(int minSamples,long horizon,int signalVersion){
        return coinReports(minSamples,horizon,signalVersion,1);
    }
    public List<CoinReport> coinReports(int minSamples,long horizon,int signalVersion,int tpLevel){java.math.BigDecimal target=target(tpLevel);
        Map<String,Map<String,Score>> scores=new TreeMap<>();
        requireSupportedHorizon(horizon);
        requireSignalVersion(signalVersion);for(ReportRow p:predictions.findGradedReportRows(horizon,signalVersion)) scores.computeIfAbsent(p.getCoinSymbol(),x->new HashMap<>()).computeIfAbsent(p.getMethodName(),x->new Score()).add(p,target);
        List<CoinReport> result=new ArrayList<>();
        scores.forEach((coin,methods)->{var rows=methods.entrySet().stream().filter(e->e.getValue().total>=minSamples).map(e->new MethodAccuracy(e.getKey(),e.getValue().total,e.getValue().targetCorrect,e.getValue().targetHitRate(),e.getValue().directionalCorrect,e.getValue().directionalAccuracy())).sorted(Comparator.comparingDouble(MethodAccuracy::targetHitRate).reversed().thenComparing(Comparator.comparingLong(MethodAccuracy::samples).reversed())).toList();result.add(new CoinReport(coin,rows));});
        return result;
    }

    public List<MixAccuracy> mixReports(int minSamples,int size,long horizon){return mixReports(minSamples,size,horizon,Prediction.CURRENT_SIGNAL_VERSION);}
    public List<MixAccuracy> mixReports(int minSamples,int size,long horizon,int signalVersion){
        return mixReports(minSamples,size,horizon,signalVersion,1);
    }
    public List<MixAccuracy> mixReports(int minSamples,int size,long horizon,int signalVersion,int tpLevel){
        validateMixSize(size);
        requireSupportedHorizon(horizon);requireSignalVersion(signalVersion);
        return calculateMixes(predictions.findGradedReportRows(horizon,signalVersion),minSamples,size,target(tpLevel));
    }

    public List<CoinMixReport> coinMixReports(int minSamples,int size,long horizon){return coinMixReports(minSamples,size,horizon,Prediction.CURRENT_SIGNAL_VERSION);}
    public List<CoinMixReport> coinMixReports(int minSamples,int size,long horizon,int signalVersion){
        return coinMixReports(minSamples,size,horizon,signalVersion,1);
    }
    public List<CoinMixReport> coinMixReports(int minSamples,int size,long horizon,int signalVersion,int tpLevel){
        validateMixSize(size);requireSupportedHorizon(horizon);requireSignalVersion(signalVersion);Map<String,List<ReportRow>> byCoin=new TreeMap<>();predictions.findGradedReportRows(horizon,signalVersion).forEach(p->byCoin.computeIfAbsent(p.getCoinSymbol(),x->new ArrayList<>()).add(p));
        java.math.BigDecimal target=target(tpLevel);return byCoin.entrySet().stream().map(e->new CoinMixReport(e.getKey(),size,calculateMixes(e.getValue(),minSamples,size,target))).toList();
    }

    public SuperReport superReport(int minSamples,long horizon){return superReport(minSamples,horizon,Prediction.CURRENT_SIGNAL_VERSION);}
    public SuperReport superReport(int minSamples,long horizon,int signalVersion){
        return superReport(minSamples,horizon,signalVersion,1);
    }
    public SuperReport superReport(int minSamples,long horizon,int signalVersion,int tpLevel){java.math.BigDecimal target=target(tpLevel);
        requireSupportedHorizon(horizon);requireSignalVersion(signalVersion);List<ReportRow> graded=predictions.findGradedReportRows(horizon,signalVersion);
        Map<String,List<ReportRow>> byCoin=new TreeMap<>();graded.forEach(p->byCoin.computeIfAbsent(p.getCoinSymbol(),x->new ArrayList<>()).add(p));
        Map<String,List<ReportRow>> latest=new HashMap<>();runs.findTopByOrderByCreatedAtDesc().ifPresent(r->predictions.findLiveReportRows(r.getId(),horizon,signalVersion).forEach(p->latest.computeIfAbsent(p.getCoinSymbol(),x->new ArrayList<>()).add(p)));
        List<CoinOpportunity> coins=new ArrayList<>();
        byCoin.forEach((coin,rows)->{
            Map<String,Score> methodScores=new HashMap<>();rows.forEach(p->methodScores.computeIfAbsent(p.getMethodName(),x->new Score()).add(p,target));
            var bestMethod=methodScores.entrySet().stream().filter(e->e.getValue().total>=minSamples).max(Comparator.comparingDouble(e->wilson(e.getValue().targetCorrect,e.getValue().total))).orElse(null);
            var mixes=calculateMixes(rows,minSamples,3,target);var bestMix=mixes.isEmpty()?null:mixes.getFirst();
            long total=rows.size(),correct=rows.stream().filter(p->mixCorrect(p,p.getPredictedDirection()==Direction.UP,target)).count(),directionalCorrect=rows.stream().filter(p->directionCorrect(p,p.getPredictedDirection()==Direction.UP)).count();double adjusted=wilson(correct,total)*100;
            var live=latest.getOrDefault(coin,List.of());double upWeight=0,totalWeight=0;int weightedSignals=0;for(ReportRow p:live){Score s=methodScores.get(p.getMethodName());double weight=s==null||s.total<minSamples?0:Math.max(0,wilson(s.targetCorrect,s.total)-.5);if(weight==0)continue;weightedSignals++;totalWeight+=weight;if(p.getPredictedDirection()==Direction.UP)upWeight+=weight;}double upShare=totalWeight==0?.5:upWeight/totalWeight;String direction=totalWeight==0?"WAIT":upShare>=.5?"UP":"DOWN";double strength=totalWeight==0?0:Math.max(upShare,1-upShare)*100;
            coins.add(new CoinOpportunity(coin,total,correct,total==0?0:correct*100.0/total,directionalCorrect,total==0?0:directionalCorrect*100.0/total,adjusted,bestMethod==null?null:bestMethod.getKey(),bestMethod==null?0:bestMethod.getValue().targetHitRate(),bestMix==null?List.of():bestMix.methods(),bestMix==null?0:bestMix.targetHitRate(),bestMix==null?0:bestMix.samples(),direction,strength,weightedSignals));
        });
        coins.sort(Comparator.comparingDouble(CoinOpportunity::valueScore).reversed().thenComparing(Comparator.comparingLong(CoinOpportunity::samples).reversed()));
        return new SuperReport(java.time.Instant.now(),minSamples,tpLevel,target.movePointRight(2),coins,calculateMixes(graded,minSamples,3,target).stream().limit(10).toList());
    }

    public List<ExcelSliceRow> superExcelRows(){return superExcelRows(Prediction.CURRENT_SIGNAL_VERSION);}
    public List<ExcelSliceRow> superExcelRows(int signalVersion){return superExcelRows(signalVersion,1);}public List<ExcelSliceRow> superExcelRows(int signalVersion,int tpLevel){requireSignalVersion(signalVersion);java.math.BigDecimal selectedTarget=target(tpLevel);
        Map<String,Map<Long,List<ReportRow>>> grouped=new TreeMap<>();
        for(ReportRow p:predictions.findAllGradedReportRows(signalVersion))grouped.computeIfAbsent(p.getCoinSymbol(),x->new TreeMap<>()).computeIfAbsent(p.getHorizonSeconds(),x->new ArrayList<>()).add(p);
        List<ExcelSliceRow> result=new ArrayList<>();
        grouped.forEach((coin,slices)->slices.forEach((horizon,rows)->{var all=calculateMixesBySizes(rows,1,selectedTarget,1,2,3,4,5,6);List<BestMixBySize> best=new ArrayList<>();for(int size=1;size<=6;size++){var mixes=all.getOrDefault(size,List.of());best.add(new BestMixBySize(size,mixes.isEmpty()?null:mixes.getFirst()));}result.add(new ExcelSliceRow(coin,horizon,best));}));
        return result;
    }

    public MoneyReport moneyReport(MoneyRequest request){return moneyReport(request,Prediction.CURRENT_SIGNAL_VERSION);}
    public MoneyReport moneyReport(MoneyRequest request,int signalVersion){return moneyReport(request,signalVersion,1);}public MoneyReport moneyReport(MoneyRequest request,int signalVersion,int tpLevel){requireSignalVersion(signalVersion);java.math.BigDecimal selectedTarget=target(tpLevel);
        if(request.methods()==null||request.methods().size()<2||request.methods().size()>8)throw new IllegalArgumentException("select between 2 and 8 methods");
        if(request.tradeAmount()<=0||request.tradeAmount()>1_000_000)throw new IllegalArgumentException("trade amount must be positive and at most 1,000,000 USDT");
        if(request.leverage()<1||request.leverage()>125)throw new IllegalArgumentException("leverage must be between 1x and 125x");
        validateCost("taker fee",request.takerFeePercent());validateCost("slippage",request.slippagePercent());validateCost("spread",request.spreadPercent());validateCost("funding",request.fundingRatePercent());
        Set<String> selected=new TreeSet<>(request.methods());
        if(selected.size()<2)throw new IllegalArgumentException("select at least two distinct methods");
        requireSupportedHorizon(request.horizon());Map<GroupKey,List<ReportRow>> groups=new HashMap<>();
        predictions.findMoneyReportRows(request.coin().toUpperCase(Locale.ROOT),request.horizon(),selected,signalVersion).forEach(p->{if(p.getRunId()!=null)groups.computeIfAbsent(new GroupKey(p.getRunId(),p.getCoinId(),p.getHorizonSeconds()),x->new ArrayList<>()).add(p);});
        List<TradeCandidate> candidates=new ArrayList<>();for(List<ReportRow> rows:groups.values()){Map<String,ReportRow> byMethod=new HashMap<>();rows.forEach(p->byMethod.put(p.getMethodName(),p));if(!byMethod.keySet().containsAll(selected))continue;int ups=0;for(String method:selected)if(byMethod.get(method).getPredictedDirection()==Direction.UP)ups++;if(ups*2==selected.size())continue;ReportRow sample=byMethod.get(selected.iterator().next());if(sample.getPriceAtGrading()==null)continue;boolean up=ups*2>selected.size();candidates.add(new TradeCandidate(sample.getPredictedAt(),sample.getPredictedAt().plusSeconds(sample.getHorizonSeconds()),sample.getCoinPair(),up,sample.getPriceAtPrediction().doubleValue(),sample.getPriceAtGrading().doubleValue(),mixCorrect(sample,up,selectedTarget)));}
        candidates.sort(Comparator.comparing(TradeCandidate::time));
        List<MoneyTrade> trades=new ArrayList<>();double grossTotal=0,netTotal=0,totalCosts=0,realizedPeak=0,realizedDrawdown=0;int profitable=0,losing=0,breakEven=0,targetHits=0,liquidations=0,index=0;double notional=request.tradeAmount()*request.leverage();for(TradeCandidate c:candidates){double marketReturn=(c.exit()-c.entry())/c.entry(),directional=c.up()?marketReturn:-marketReturn,leveraged=directional*request.leverage();var range=market.priceRange(c.pair(),c.time(),c.target());double liquidationPrice=c.up()?c.entry()*(1.0-1.0/request.leverage()):c.entry()*(1.0+1.0/request.leverage());boolean liquidated=c.up()?range.low().doubleValue()<=liquidationPrice:range.high().doubleValue()>=liquidationPrice;double gross=liquidated?-request.tradeAmount():request.tradeAmount()*leveraged;double costs=notional*((2*request.takerFeePercent()+2*request.slippagePercent()+request.spreadPercent()+request.fundingRatePercent())/100.0);double net=liquidated?-request.tradeAmount():Math.max(-request.tradeAmount(),gross-costs);grossTotal+=gross;totalCosts+=liquidated?0:costs;netTotal+=net;realizedPeak=Math.max(realizedPeak,netTotal);realizedDrawdown=Math.max(realizedDrawdown,realizedPeak-netTotal);if(net>1e-9)profitable++;else if(net< -1e-9)losing++;else breakEven++;if(c.targetHit())targetHits++;if(liquidated)liquidations++;trades.add(new MoneyTrade(++index,c.time(),c.target(),c.up()?"LONG":"SHORT",c.entry(),c.exit(),marketReturn*100,liquidationPrice,gross,liquidated?0:costs,net,netTotal,liquidated));}
        int executed=trades.size(),peakConcurrent=peakConcurrency(candidates);double totalMargin=request.tradeAmount()*executed,peakMargin=request.tradeAmount()*peakConcurrent,profitWinRate=executed==0?0:profitable*100.0/executed,targetHitRate=executed==0?0:targetHits*100.0/executed,pnlToPeakMargin=peakMargin==0?0:netTotal*100/peakMargin,average=executed==0?0:netTotal/executed;return new MoneyReport(request.coin().toUpperCase(),request.horizon(),List.copyOf(selected),tpLevel,selectedTarget.movePointRight(2),"INDEPENDENT_TRADES_SIMPLE_LIQUIDATION",executed,request.tradeAmount(),request.leverage(),totalMargin,peakConcurrent,peakMargin,grossTotal,totalCosts,netTotal,pnlToPeakMargin,profitable,losing,breakEven,profitWinRate,targetHits,executed-targetHits,targetHitRate,liquidations,realizedDrawdown,average,trades);
    }
    private void validateCost(String name,double value){if(value<0||value>5)throw new IllegalArgumentException(name+" percent must be between 0 and 5");}
    private int peakConcurrency(List<TradeCandidate> candidates){record Event(java.time.Instant at,int delta){}List<Event> events=new ArrayList<>(candidates.size()*2);for(TradeCandidate candidate:candidates){events.add(new Event(candidate.time(),1));events.add(new Event(candidate.target(),-1));}events.sort(Comparator.comparing(Event::at).thenComparingInt(Event::delta));int active=0,peak=0;for(Event event:events){active+=event.delta();peak=Math.max(peak,active);}return peak;}

    private List<MixAccuracy> calculateMixes(List<ReportRow> source,int minSamples,int size){return calculateMixes(source,minSamples,size,DEFAULT_TARGET);}private List<MixAccuracy> calculateMixes(List<ReportRow> source,int minSamples,int size,java.math.BigDecimal target){return calculateMixesBySizes(source,minSamples,target,size).getOrDefault(size,List.of());}
    private Map<Integer,List<MixAccuracy>> calculateMixesBySizes(List<ReportRow> source,int minSamples,java.math.BigDecimal target,int... sizes){
        List<String> methodNames=source.stream().map(ReportRow::getMethodName).distinct().sorted().toList();
        if(methodNames.size()>63)throw new IllegalStateException("method mix calculation supports at most 63 methods");
        Map<String,Integer> methodIndexes=new HashMap<>();for(int i=0;i<methodNames.size();i++)methodIndexes.put(methodNames.get(i),i);
        Map<GroupKey,GroupSignals> groups=new HashMap<>();
        for(ReportRow p:source)if(p.getRunId()!=null)groups.computeIfAbsent(new GroupKey(p.getRunId(),p.getCoinId(),p.getHorizonSeconds()),x->new GroupSignals(p)).put(methodIndexes.get(p.getMethodName()),p.getPredictedDirection());
        Map<Integer,Map<Long,MixScore>> scoresBySize=new HashMap<>();for(int size:sizes)scoresBySize.put(size,new HashMap<>());
        for(GroupSignals group:groups.values())for(int size:sizes){if(size<1||Long.bitCount(group.methods)<size)continue;Map<Long,MixScore> scores=scoresBySize.get(size);combinations(group.methods,size,0L,mix->{int ups=Long.bitCount(mix&group.ups);MixScore score=scores.computeIfAbsent(mix,x->new MixScore());score.totalPredictions++;boolean sameDirection=ups==0||ups==size,predictedUp=ups*2>size,targetHit=group.targetHit(predictedUp,target),directionHit=group.directionHit(predictedUp);if(sameDirection){score.sameDirectionPredictions++;if(targetHit)score.sameDirectionCorrect++;}if(ups*2==size)return;score.samples++;if(targetHit)score.targetCorrect++;if(directionHit)score.directionalCorrect++;});}
        Map<Integer,List<MixAccuracy>> result=new HashMap<>();
        scoresBySize.forEach((size,scores)->result.put(size,scores.entrySet().stream().filter(e->e.getValue().samples>=minSamples).map(e->{MixScore s=e.getValue();return new MixAccuracy(methods(e.getKey(),methodNames),s.totalPredictions,s.sameDirectionPredictions,s.sameDirectionCorrect,s.samples,s.targetCorrect,s.targetHitRate(),s.directionalCorrect,s.directionalAccuracy());}).sorted(Comparator.comparingDouble(MixAccuracy::targetHitRate).reversed().thenComparing(Comparator.comparingLong(MixAccuracy::samples).reversed())).limit(100).toList()));
        return result;
    }
    private void combinations(long remaining,int needed,long selected,java.util.function.LongConsumer consumer){if(needed==0){consumer.accept(selected);return;}while(Long.bitCount(remaining)>=needed){long bit=Long.lowestOneBit(remaining);remaining^=bit;combinations(remaining,needed-1,selected|bit,consumer);}}
    private List<String> methods(long mask,List<String> names){List<String> result=new ArrayList<>(Long.bitCount(mask));while(mask!=0){long bit=Long.lowestOneBit(mask);result.add(names.get(Long.numberOfTrailingZeros(bit)));mask^=bit;}return result;}
    private double wilson(long correct,long total){if(total==0)return 0;double z=1.96,p=(double)correct/total,z2=z*z;return (p+z2/(2*total)-z*Math.sqrt((p*(1-p)+z2/(4*total))/total))/(1+z2/total);}
    private void validateMixSize(int size){if(size<2||size>4)throw new IllegalArgumentException("mix size must be 2, 3, or 4");}
    public static long requireSupportedHorizon(long horizon){if(!Set.of(60L,900L,1800L,3600L,14400L,43200L,86400L).contains(horizon))throw new IllegalArgumentException("horizon must be one of 60, 900, 1800, 3600, 14400, 43200, or 86400 seconds");return horizon;}
    public static int requireSignalVersion(int version){if(version!=Prediction.LEGACY_SIGNAL_VERSION&&version!=Prediction.CURRENT_SIGNAL_VERSION)throw new IllegalArgumentException("signalVersion must be 2 or 3");return version;}
    java.math.BigDecimal targetPercent(int level){if(level<1||level>3)throw new IllegalArgumentException("tpLevel must be 1, 2, or 3");return (settings==null?TpSlLevels.defaults():settings.findById(1).orElseThrow().getTpSlLevels()).tp(level);}private java.math.BigDecimal target(int level){return targetPercent(level).movePointLeft(2);}
    private boolean mixCorrect(ReportRow p,boolean predictedUp,java.math.BigDecimal target){if(p.getPriceAtGrading()==null)return false;var change=p.getPriceAtGrading().subtract(p.getPriceAtPrediction()).divide(p.getPriceAtPrediction(),12,java.math.RoundingMode.HALF_UP);return predictedUp?change.compareTo(target)>=0:change.compareTo(target.negate())<=0;}
    private boolean directionCorrect(ReportRow p,boolean predictedUp){if(p.getPriceAtGrading()==null)return false;int comparison=p.getPriceAtGrading().compareTo(p.getPriceAtPrediction());return predictedUp?comparison>0:comparison<0;}
    private class Score {long total,targetCorrect,directionalCorrect;void add(ReportRow p,java.math.BigDecimal target){total++;if(mixCorrect(p,p.getPredictedDirection()==Direction.UP,target))targetCorrect++;if(directionCorrect(p,p.getPredictedDirection()==Direction.UP))directionalCorrect++;}double targetHitRate(){return total==0?0:targetCorrect*100.0/total;}double directionalAccuracy(){return total==0?0:directionalCorrect*100.0/total;}}
    private static class MixScore {long totalPredictions,sameDirectionPredictions,sameDirectionCorrect,samples,targetCorrect,directionalCorrect;double targetHitRate(){return samples==0?0:targetCorrect*100.0/samples;}double directionalAccuracy(){return samples==0?0:directionalCorrect*100.0/samples;}}
    private static class GroupSignals {long methods,ups;final java.math.BigDecimal upwardReturn;GroupSignals(ReportRow p){upwardReturn=p.getPriceAtGrading()==null?null:p.getPriceAtGrading().subtract(p.getPriceAtPrediction()).divide(p.getPriceAtPrediction(),12,java.math.RoundingMode.HALF_UP);}void put(int index,Direction direction){long bit=1L<<index;methods|=bit;if(direction==Direction.UP)ups|=bit;else ups&=~bit;}boolean targetHit(boolean predictedUp,java.math.BigDecimal target){if(upwardReturn==null)return false;return (predictedUp?upwardReturn:upwardReturn.negate()).compareTo(target)>=0;}boolean directionHit(boolean predictedUp){if(upwardReturn==null)return false;return predictedUp?upwardReturn.signum()>0:upwardReturn.signum()<0;}}
    private record GroupKey(long runId,long coinId,long horizon){}
    private record TradeCandidate(java.time.Instant time,java.time.Instant target,String pair,boolean up,double entry,double exit,boolean targetHit){}
    public record MethodAccuracy(String method,long samples,long targetCorrect,double targetHitRate,long directionalCorrect,double directionalAccuracy){}
    public record CoinReport(String coin,List<MethodAccuracy> methods){}
    public record MixAccuracy(List<String> methods,long totalPredictions,long sameDirectionPredictions,long sameDirectionCorrect,long samples,long targetCorrect,double targetHitRate,long directionalCorrect,double directionalAccuracy){}
    public record CoinMixReport(String coin,int size,List<MixAccuracy> mixes){}
    public record CoinOpportunity(String coin,long samples,long targetCorrect,double targetHitRate,long directionalCorrect,double directionalAccuracy,double valueScore,String bestMethod,double bestMethodTargetHitRate,List<String> bestMix,double bestMixTargetHitRate,long bestMixSamples,String currentDirection,double consensusStrength,int weightedSignals){}
    public record SuperReport(java.time.Instant generatedAt,int minSamples,int tpLevel,java.math.BigDecimal targetPercent,List<CoinOpportunity> coins,List<MixAccuracy> topMixes){}
    public record BestMixBySize(int size,MixAccuracy mix){}
    public record ExcelSliceRow(String coin,long horizon,List<BestMixBySize> bestMixes){}
    public record MoneyRequest(String coin,long horizon,List<String> methods,double tradeAmount,int leverage,double takerFeePercent,double slippagePercent,double spreadPercent,double fundingRatePercent){}
    public record MoneyTrade(int number,java.time.Instant time,java.time.Instant targetTime,String side,double entryPrice,double exitPrice,double marketMovePercent,double approximateLiquidationPrice,double grossPnl,double costs,double netPnl,double cumulativeNetPnl,boolean liquidated){}
    public record MoneyReport(String coin,long horizon,List<String> methods,int tpLevel,java.math.BigDecimal targetPercent,String simulationModel,int executedTrades,double tradeAmount,int leverage,double totalMarginAllocated,int peakConcurrentTrades,double peakMarginRequired,double grossPnl,double totalCosts,double netPnl,double netPnlToPeakConcurrentMarginPercent,int profitableTrades,int losingTrades,int breakEvenTrades,double profitWinRate,int targetHits,int targetMisses,double targetHitRate,int liquidations,double realizedPnlDrawdown,double averageNetPnlPerTrade,List<MoneyTrade> trades){}
}
